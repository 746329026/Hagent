package com.hagent.memory.session;

import com.hagent.memory.MemoryMode;
import com.hagent.memory.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话目录的文件实现 —— 零基础设施兜底，与 {@code FileConversationRecordStore} 配套。
 *
 * <h2>★ 存储形态：一个 sidecar 文件，不是"一会话一文件"</h2>
 *
 * <p>记录层的文件实现是<b>一会话一文件</b>（{@code {id}.json}），但目录<b>不能</b>照抄：
 * 目录的操作是"{@link #list} 全部 + 按时间排序"，若一会话一文件，
 * 每次打开侧栏都要<b>遍历整个目录、逐个读文件</b>再排序 —— 会话一多就明显变慢。
 *
 * <p>所以目录落在一个 {@code _catalog.json} 里（下划线开头，不会与 UUID 文件名撞）：
 * <pre>
 *   { "conversations": { "<uuid>": { "title": "...", "mode": "chat",
 *                                    "createdAt": 1723…, "updatedAt": 1723… } } }
 * </pre>
 *
 * <h2>★ 代价（诚实声明）：全局锁</h2>
 *
 * <p>单文件意味着<b>所有会话共用一把锁</b> —— 这正是记录层当初拒绝单文件的理由
 * （"全部会话串行"）。这里接受它的原因：
 * <ul>
 *   <li>目录的写操作是<b>每回合一次</b>（更新活跃时间），比消息写入少一个数量级，
 *       而且它只是改一个 long；</li>
 *   <li>本实现本身就是"零基础设施兜底"，它的定位不是扛并发；</li>
 *   <li>换成"一会话一文件的目录项"会让读路径变成 O(会话数) 次文件操作，
 *       用一个更常见的慢换取一个更少见的并发问题 —— 不划算。</li>
 * </ul>
 *
 * <h2>★ 时间戳存 epoch 毫秒（long），不存 ISO 字符串</h2>
 *
 * <p>字符串要解析、要时区、要格式约定；long 是<b>绝对时间</b>，没有歧义。
 * 展示格式属于前端，不属于存储。
 */
@Slf4j
public class FileConversationCatalog implements ConversationCatalog {

    /** sidecar 文件名。下划线开头，与 {@code {uuid}.json} 的命名空间分开。 */
    private static final String CATALOG_FILE = "_catalog.json";

    private final Path rootDir;
    private final Path catalogFile;
    private final ObjectMapper objectMapper;

    /**
     * 全局锁 —— 单文件存储的必然代价（见类注释）。
     *
     * <p>用 {@code final Object} 而不是 {@code synchronized} 方法：后者锁的是 this，
     * 任何人都能拿到同一个对象再加锁，把锁的语义泄漏到类外。
     */
    private final Object lock = new Object();

    public FileConversationCatalog(MemoryProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.rootDir = Path.of(properties.getFileDir()).toAbsolutePath().normalize();
        this.catalogFile = rootDir.resolve(CATALOG_FILE);
        try {
            Files.createDirectories(rootDir);
            log.info("[记忆-会话目录-file] 目录文件已就绪：{}", catalogFile);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建会话目录所在目录：" + rootDir, e);
        }
    }

    @Override
    public List<Session> list(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, Entry> conversations;
        synchronized (lock) {
            conversations = read().conversations();
        }
        List<Session> sessions = new ArrayList<>(conversations.size());
        conversations.forEach((id, entry) -> sessions.add(toSession(id, entry)));
        // 最近活跃的在前；同一毫秒时按 id 兜底，保证顺序稳定（否则侧栏会随机抖动）
        sessions.sort(Comparator.comparing(Session::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Session::conversationId));
        return sessions.size() <= limit ? sessions : sessions.subList(0, limit);
    }

    @Override
    public Optional<Session> find(String conversationId) {
        synchronized (lock) {
            Entry entry = read().conversations().get(conversationId);
            return entry == null ? Optional.empty() : Optional.of(toSession(conversationId, entry));
        }
    }

    @Override
    public void touch(String conversationId, MemoryMode mode, String titleSeed) {
        MemoryMode safeMode = mode == null ? MemoryMode.DEFAULT : mode;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Catalog file = read();
            Entry existing = file.conversations().get(conversationId);
            Entry next = existing == null
                    ? new Entry(ConversationCatalog.titleOf(titleSeed), safeMode.wireName(), now, now)
                    // ★ 已存在：只更新 updatedAt。标题与模式原地不动（接口契约）。
                    : new Entry(existing.title(), existing.mode(), existing.createdAt(), now);
            if (existing != null && next.equals(existing)) {
                return; // 同一毫秒内的重复 touch：不写盘
            }
            file.conversations().put(conversationId, next);
            write(file);
        }
    }

    @Override
    public boolean rename(String conversationId, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        synchronized (lock) {
            Catalog file = read();
            Entry existing = file.conversations().get(conversationId);
            if (existing == null) {
                return false;
            }
            file.conversations().put(conversationId, new Entry(
                    ConversationCatalog.titleOf(title), existing.mode(),
                    existing.createdAt(), System.currentTimeMillis()));
            write(file);
            return true;
        }
    }

    @Override
    public void delete(String conversationId) {
        synchronized (lock) {
            Catalog file = read();
            if (file.conversations().remove(conversationId) != null) {
                write(file);
            }
        }
    }

    // ==================== 内部方法 ====================

    private Session toSession(String conversationId, Entry entry) {
        return new Session(conversationId, entry.title(), entry.mode(),
                Instant.ofEpochMilli(entry.createdAt()), Instant.ofEpochMilli(entry.updatedAt()));
    }

    /**
     * 读出目录文件。
     *
     * <p>文件不存在 / 内容为空 / JSON 损坏 都返回<b>空目录</b>并记 WARN，
     * 而不是抛异常 —— 与 {@code FileConversationRecordStore#readStored} 同一立场：
     * 一个坏文件不该让整个应用的侧栏打不开。
     *
     * <p>★ 但"损坏"与"空"在日志里<b>必须能区分</b>：前者是事故（目录丢了），
     * 后者是正常的新环境。都打同一句"读不到"会让事故看起来像首次运行。
     */
    private Catalog read() {
        if (Files.notExists(catalogFile)) {
            return new Catalog(new LinkedHashMap<>());
        }
        try {
            String json = Files.readString(catalogFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new Catalog(new LinkedHashMap<>());
            }
            Catalog parsed = objectMapper.readValue(json, Catalog.class);
            if (parsed == null || parsed.conversations() == null) {
                return new Catalog(new LinkedHashMap<>());
            }
            return new Catalog(new LinkedHashMap<>(parsed.conversations()));
        } catch (Exception e) {
            log.warn("[记忆-会话目录-file] ★ 目录文件损坏，按空目录处理（侧栏会看不到历史会话）：{} 原因={}",
                    catalogFile, e.getMessage());
            return new Catalog(new LinkedHashMap<>());
        }
    }

    /** 原子写入：临时文件 → ATOMIC_MOVE（与记录层同一做法，理由见那个类）。 */
    private void write(Catalog catalog) {
        Path tmp = catalogFile.resolveSibling(CATALOG_FILE + ".tmp");
        try {
            String json = objectMapper.writeValueAsString(catalog);
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, catalogFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                log.warn("[记忆-会话目录-file] ATOMIC_MOVE 不被支持，降级为普通替换（原子性无保障）：{}",
                        atomicFailed.getMessage());
                Files.move(tmp, catalogFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入会话目录失败：" + catalogFile, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    /**
     * 目录文件的结构。
     *
     * @param conversations 会话 ID → 目录项。<b>用 Map 而不是 List</b>：
     *                      按 ID 查/改是主要操作，Map 让 {@code touch} 与 {@code rename}
     *                      不必先线性搜索一遍
     */
    public record Catalog(Map<String, Entry> conversations) {
    }

    /**
     * 一个会话的目录项（持久化形态）。
     *
     * <p>它是 record 且字段全是"可序列化的基本类型"（String / long），
     * 所以 Jackson 不需要额外模块（Instant 就需要 JSR310 模块，见类注释）。
     */
    public record Entry(String title, String mode, long createdAt, long updatedAt) {
    }
}
