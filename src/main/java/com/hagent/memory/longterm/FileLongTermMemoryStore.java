package com.hagent.memory.longterm;

import com.hagent.common.Texts;
import com.hagent.config.LongTermMemoryProperties;
import com.hagent.document.AccessLevel;
import com.hagent.security.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 长期记忆的<b>文件实现</b> —— 一条记忆一个 {@code .md}，外加一份索引。
 *
 * <pre>
 * {dir}/{userId}/
 *   MEMORY.md                     索引（供人看；程序每次写都重生成）
 *   mem-3f2a9c1b.md               一条记忆（frontmatter + 正文）
 * </pre>
 *
 * <h2>★ 文件名为什么是标题的哈希，而不是标题本身</h2>
 *
 * <p>标题来自<b>模型</b>。若直接用它做文件名：
 * <ul>
 *   <li>{@code ../../application.yml} 这类标题就是一次<b>目录穿越</b>写入；</li>
 *   <li>中文/空格/引号在 Windows 上还有一批不可见字符要处理。</li>
 * </ul>
 * 用 {@code sha256(title)} 的前 8 位十六进制当文件名，这两类问题<b>不是被校验挡住，
 * 而是根本构造不出来</b>（结果只可能是 {@code [0-9a-f]}）。
 *
 * <p>★ 顺带的好处：同名 → 同哈希 → <b>同一个文件</b> ⇒ upsert 天然成立，
 * 不需要"先查有没有、再决定新增还是改"。
 *
 * <p>⚠️ 代价要写清楚：8 位十六进制的碰撞概率不是零（约 2^-32），
 * 那时两条**不同标题**会落到同一个文件、互相覆盖。在"一个用户几十条记忆"的量级下
 * 这个概率可以忽略，但它不是零 —— 所以这里如实记下来，而不是假装不存在。
 *
 * <h2>★ 为什么 {@code MEMORY.md} 是"物化产物"而不是真相来源</h2>
 *
 * <p>真相是那些 {@code mem-*.md}（每条正文都在里面）。索引每次写操作后<b>重新生成</b>，
 * 于是它**不可能与条目漂移**。若把索引当权威（只增删索引、正文另存），
 * 两边迟早对不上，而症状是"索引里有、点进去没有"。
 *
 * <h2>★ 并发</h2>
 *
 * <p>单 JVM 内用一把锁串行化<b>写</b>（读不加锁：文件是原子替换的，读到的要么旧要么新）。
 * 与 {@code FileConversationRecordStore} 同一立场 —— 本实现是"零基础设施可用"的兜底，
 * 不是分布式存储。
 */
@Slf4j
public class FileLongTermMemoryStore implements LongTermMemoryStore {

    /** 索引文件名。 */
    static final String INDEX_FILE = "MEMORY.md";

    private static final String FILE_PREFIX = "mem-";
    private static final String FILE_SUFFIX = ".md";
    private static final String FRONTMATTER = "---";

    /**
     * 允许直接当目录名的 userId 形态。
     *
     * <p>★ 为什么必须校验：{@code userId} 来自请求头里的 token，是<b>客户端可控</b>的。
     * 不校验就等于允许调用方把记忆写进任意目录（{@code ..} / 绝对路径 / 盘符）。
     * 不合法时退化成哈希值，而不是拒绝请求 —— 拒绝会让"奇怪但合法的 userId"用不了，
     * 而哈希后每个 userId 仍然有<b>独立且确定</b>的目录。
     */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path rootDir;
    private final LongTermMemoryProperties properties;

    /** 写操作的锁（见类注释：读不加锁）。 */
    private final Object lock = new Object();

    public FileLongTermMemoryStore(LongTermMemoryProperties properties) {
        this.properties = properties;
        this.rootDir = Path.of(properties.getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建长期记忆目录：" + rootDir, e);
        }
        log.info("[长期记忆] 根目录={}  索引上限={} 字  单条正文上限={} 字",
                rootDir, properties.getIndexMaxChars(), properties.getContentMaxChars());
    }

    // ========================================================================
    // 读
    // ========================================================================

    @Override
    public List<MemoryEntry> list(UserContext user) {
        return readAll(userDir(user)).stream()
                .filter(entry -> entry.visibleTo(user.clearance()))
                .sorted(byUpdatedDesc())
                .toList();
    }

    @Override
    public Optional<MemoryEntry> find(String title, UserContext user) {
        String clean = cleanTitle(title);
        return readAll(userDir(user)).stream()
                .filter(entry -> entry.title().equals(clean))
                .filter(entry -> entry.visibleTo(user.clearance()))
                .findFirst();
    }

    @Override
    public String renderIndexFor(UserContext user) {
        List<MemoryEntry> visible = list(user);
        if (visible.isEmpty()) {
            return "";
        }
        String digest = renderForPrompt(visible);
        if (digest.length() <= properties.getIndexMaxChars()) {
            return digest;
        }
        // ★ 走到这里说明"上限被调小过"，而历史条目还按老上限存的。
        //   注入超长索引的代价是吃掉对话历史预算 —— 所以这里截断，但**必须留日志**，
        //   否则就成了"静默丢弃用户记忆"。
        List<MemoryEntry> kept = new ArrayList<>();
        for (MemoryEntry entry : visible) {
            List<MemoryEntry> candidate = new ArrayList<>(kept);
            candidate.add(entry);
            if (renderForPrompt(candidate).length() > properties.getIndexMaxChars()) {
                break;
            }
            kept.add(entry);
        }
        log.warn("[长期记忆] 索引超出上限 {} 字（实际 {} 字，可见 {} 条），本次只注入前 {} 条 —— "
                        + "【怎么办】调大 hagent.memory.long-term.index-max-chars，"
                        + "或让模型用 forgetMemory 清掉不再需要的条目",
                properties.getIndexMaxChars(), digest.length(), visible.size(), kept.size());
        return renderForPrompt(kept);
    }

    // ========================================================================
    // 写
    // ========================================================================

    @Override
    public SaveResult save(String title, String content, UserContext user) {
        String clean = cleanTitle(title);
        if (clean.isBlank()) {
            // 工具层已用 @ToolParam 校验；这里防御（空标题会退化成同一个文件名）
            return SaveResult.FORBIDDEN;
        }
        String body = content == null ? "" : content.strip();
        if (body.length() > properties.getContentMaxChars()) {
            body = Texts.truncateByCodePoints(body, properties.getContentMaxChars(), "…");
        }

        synchronized (lock) {
            Path dir = userDir(user);
            ensureDirectory(dir);
            List<MemoryEntry> all = readAll(dir);
            MemoryEntry existing = all.stream()
                    .filter(entry -> entry.title().equals(clean))
                    .findFirst().orElse(null);

            AccessLevel level = user.clearance();
            if (existing != null && !existing.visibleTo(level)) {
                // 同名条目存在但你看不到它 —— 拒绝覆盖。
                // 否则低权限身份可以把别人（不可见）的记忆冲掉，而它自己毫无察觉。
                log.warn("[长期记忆] 拒绝覆盖：标题「{}」的条目密级是 {}，当前身份密级是 {} 看不到它",
                        clean, existing.level(), level);
                return SaveResult.FORBIDDEN;
            }

            Instant now = Instant.now();
            MemoryEntry entry = new MemoryEntry(
                    clean,
                    body,
                    existing == null ? level : existing.level(),
                    existing == null ? now : existing.createdAt(),
                    now);

            // 先检查索引上限，再落盘 —— 顺序反了会留下"写进去但索引装不下"的条目。
            List<MemoryEntry> projected = new ArrayList<>(all);
            projected.removeIf(e -> e.title().equals(clean));
            projected.add(entry);
            String projectedDigest = renderForPrompt(projected.stream()
                    .filter(e -> e.visibleTo(level))
                    .sorted(byUpdatedDesc())
                    .toList());
            if (projectedDigest.length() > properties.getIndexMaxChars()) {
                log.info("[长期记忆] 索引会超出上限（{} > {}），拒绝写入「{}」",
                        projectedDigest.length(), properties.getIndexMaxChars(), clean);
                return SaveResult.INDEX_FULL;
            }

            writeEntry(dir.resolve(fileNameOf(clean)), entry);

            List<MemoryEntry> after = new ArrayList<>(all);
            after.removeIf(e -> e.title().equals(clean));
            after.add(entry);
            writeIndex(dir, after);

            log.info("[长期记忆] {}「{}」（密级={} 正文={} 字）：{}",
                    existing == null ? "新增" : "覆盖", clean, entry.level(),
                    entry.content().length(), user.userId());
            return existing == null ? SaveResult.CREATED : SaveResult.UPDATED;
        }
    }

    @Override
    public boolean forget(String title, UserContext user) {
        String clean = cleanTitle(title);
        synchronized (lock) {
            Path dir = userDir(user);
            List<MemoryEntry> all = readAll(dir);
            MemoryEntry existing = all.stream()
                    .filter(entry -> entry.title().equals(clean))
                    .findFirst().orElse(null);
            if (existing == null) {
                return false;
            }
            if (!existing.visibleTo(user.clearance())) {
                // 与 find 一致：对外一律"不存在"，真实原因只写日志（防标题枚举）
                log.warn("[长期记忆] 拒绝删除：标题「{}」的条目密级 {} 高于当前身份的 {}",
                        clean, existing.level(), user.clearance());
                return false;
            }
            Path file = dir.resolve(fileNameOf(clean));
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("删除记忆失败：" + file, e);
            }
            List<MemoryEntry> after = new ArrayList<>(all);
            after.removeIf(entry -> entry.title().equals(clean));
            writeIndex(dir, after);
            log.info("[长期记忆] 已删除「{}」：{}", clean, user.userId());
            return true;
        }
    }

    // ========================================================================
    // 渲染
    // ========================================================================

    /**
     * 注入 prompt 的索引块。
     *
     * <p>三个部分各有原因：<b>标题+摘要</b>让模型知道有什么；
     * <b>使用规则</b>告诉它冲突时听谁的（否则它可能拿一条旧偏好压过用户当前的话）；
     * 末尾那句"可以 saveMemory 覆盖"是给模型一个<b>正当的更新路径</b> ——
     * 没有它，模型遇到变化的偏好只会两条都留着。
     */
    private String renderForPrompt(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆】以下是该用户以往对话中留下的偏好与事实：");
        for (MemoryEntry entry : entries) {
            sb.append("\n- ").append(entry.title()).append(" — ").append(entry.summary());
        }
        sb.append("\n使用规则：与本轮相关时优先遵循；若用户当前说法与记忆冲突，以当前说法为准，")
                .append("并用 saveMemory 覆盖同名条目；需要细节时用 readMemory 读取。");
        return sb.toString();
    }

    /** 落盘的索引（供人阅读，<b>不按密级过滤</b> —— 磁盘本来就能看到正文）。 */
    private String renderForDisk(List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 长期记忆索引\n\n");
        sb.append("> 本文件由程序在每次写入/删除后重新生成，**不要手改**（会被覆盖）。\n")
                .append("> 正文在同目录的 `mem-*.md`；注入模型的是本节选后的版本。\n\n");
        if (entries.isEmpty()) {
            sb.append("_（暂无记忆）_\n");
            return sb.toString();
        }
        for (MemoryEntry entry : entries.stream().sorted(byUpdatedDesc()).toList()) {
            sb.append("- ").append(entry.title()).append(" — ").append(entry.summary())
                    .append("（密级 ").append(entry.level())
                    .append(" · 更新于 ").append(entry.updatedAt())
                    .append("）\n");
        }
        return sb.toString();
    }

    private Comparator<MemoryEntry> byUpdatedDesc() {
        return Comparator.comparing(MemoryEntry::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(MemoryEntry::title);
    }

    // ========================================================================
    // 文件层
    // ========================================================================

    /**
     * 用户目录。
     *
     * <p>{@code userId} 来自 token（客户端可控），所以：合法形态直接用；
     * 否则退化成 {@code u-{hash}}。<b>两种都是确定的</b> —— 同一个 userId 永远落到同一个目录。
     */
    private Path userDir(UserContext user) {
        String raw = user == null ? null : user.userId();
        String segment = (raw != null && SAFE_SEGMENT.matcher(raw).matches())
                ? raw : "u-" + sha256Hex(raw == null ? "anonymous" : raw).substring(0, 12);
        Path dir = rootDir.resolve(segment).normalize();
        // 防御性断言：即便上面的校验有漏洞，也不允许离开根目录
        if (!dir.startsWith(rootDir)) {
            throw new IllegalStateException(
                    "记忆目录越界（拒绝写入）：" + dir + " 不在 " + rootDir + " 之内。"
                            + "这几乎一定是路径拼接出了问题，不要绕过它。");
        }
        return dir;
    }

    /** 标题 → 文件名（见类注释：只可能由十六进制字符组成，构造不出越界路径）。 */
    private String fileNameOf(String title) {
        return FILE_PREFIX + sha256Hex(title).substring(0, 8) + FILE_SUFFIX;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明 JDK 环境异常
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 标题的规范化：压成一行 + 截断。**唯一**的标题清洗点（读写都用它）。 */
    private String cleanTitle(String title) {
        return Texts.truncateByCodePoints(
                Texts.singleLine(title), properties.getTitleMaxChars(), "…");
    }

    private void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建记忆目录：" + dir, e);
        }
    }

    /** 读出目录下全部记忆（不过滤）。坏文件跳过并记 WARN —— 一个坏文件不该让整个记忆不可用。 */
    private List<MemoryEntry> readAll(Path dir) {
        if (Files.notExists(dir)) {
            return new ArrayList<>();
        }
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
                    continue;   // MEMORY.md 与杂物
                }
                MemoryEntry entry = readEntry(file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("[长期记忆] 读取目录失败，按空记忆处理：{} 原因={}", dir, e.getMessage());
            return new ArrayList<>();
        }
        return entries;
    }

    private MemoryEntry readEntry(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String normalized = raw.replace("\r\n", "\n");
            String[] lines = normalized.split("\n", -1);
            if (lines.length < 3 || !FRONTMATTER.equals(lines[0].trim())) {
                log.warn("[长期记忆] 跳过没有 frontmatter 的文件：{}", file.getFileName());
                return null;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            int index = 1;
            for (; index < lines.length; index++) {
                if (FRONTMATTER.equals(lines[index].trim())) {
                    index++;
                    break;
                }
                int colon = lines[index].indexOf(':');
                if (colon > 0) {
                    meta.put(lines[index].substring(0, colon).trim(),
                            lines[index].substring(colon + 1).trim());
                }
            }
            StringBuilder body = new StringBuilder();
            for (int i = index; i < lines.length; i++) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(lines[i]);
            }
            String title = meta.getOrDefault("title", file.getFileName().toString());
            AccessLevel level = parseLevelOrWarn(meta.get("level"), file);
            Instant createdAt = parseInstantOr(meta.get("created"), null);
            Instant updatedAt = parseInstantOr(meta.get("updated"),
                    java.nio.file.Files.getLastModifiedTime(file).toInstant());
            return new MemoryEntry(cleanTitle(title), body.toString().strip(), level,
                    createdAt, updatedAt);
        } catch (Exception e) {
            log.warn("[长期记忆] 跳过无法解析的记忆文件：{} 原因={}", file.getFileName(), e.toString());
            return null;
        }
    }

    private AccessLevel parseLevelOrWarn(String raw, Path file) {
        try {
            return AccessLevel.parse(raw);
        } catch (IllegalArgumentException e) {
            log.warn("[长期记忆] {} 的密级取值非法（{}），按默认密级 {} 处理",
                    file.getFileName(), raw, AccessLevel.DEFAULT);
            return AccessLevel.DEFAULT;
        }
    }

    private Instant parseInstantOr(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void writeEntry(Path file, MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRONTMATTER).append('\n');
        sb.append("title: ").append(entry.title()).append('\n');
        sb.append("level: ").append(entry.level()).append('\n');
        sb.append("created: ").append(entry.createdAt()).append('\n');
        sb.append("updated: ").append(entry.updatedAt()).append('\n');
        sb.append(FRONTMATTER).append('\n');
        sb.append(entry.content()).append('\n');
        writeAtomically(file, sb.toString());
    }

    private void writeIndex(Path dir, List<MemoryEntry> entries) {
        writeAtomically(dir.resolve(INDEX_FILE), renderForDisk(entries));
    }

    /**
     * 原子写入：临时文件 → {@code ATOMIC_MOVE}。
     *
     * <p>与 {@code FileConversationRecordStore} / {@code FileConversationCatalog} 同一做法：
     * 直接往目标文件写，写到一半进程被杀就留下<b>半截文件</b>，
     * 而半截的 frontmatter 会让这条记忆永久读不出来。
     *
     * <p>★ 显式 {@code UTF_8}：Windows 默认字符集是 GBK（协议 §9.4 第 3 条）——
     * 换台机器读同一份记忆就是乱码，且不可逆。
     */
    private void writeAtomically(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                log.warn("[长期记忆] ATOMIC_MOVE 不被支持，降级为普通替换（原子性无保障）：{}",
                        atomicFailed.getMessage());
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入记忆文件失败：" + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }
}
