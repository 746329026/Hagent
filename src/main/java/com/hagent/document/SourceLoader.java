package com.hagent.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库文档加载器 —— 读取 {@code .md} / {@code .txt}，解析头部的 frontmatter，产出 {@link LoadedDocument}。
 *
 * <p>只做纯文本格式：{@code .docx} / {@code .pdf} 的解析会引入 POI 等重依赖与一堆边界情况
 * （表格、图片、页眉页脚、扫描件），而 RAG 的真实难点在分块与检索，不在格式解析。
 *
 * <p>frontmatter 示例：
 * <pre>
 *   ---
 *   kind: POLICY
 *   title: 员工手册
 *   level: INTERNAL
 *   dept: HR
 *   validFrom: 2024-01-01
 *   ---
 *   # 员工手册
 *   正文……
 * </pre>
 *
 * <h2>★ 三个必须明确的取舍</h2>
 *
 * <p><b>1. 缺 frontmatter 时"能推断就推断、推不出就明确失败"</b>：{@code title} 取
 * frontmatter → 第一个 {@code #} 标题 → 文件名；{@code kind} <b>没有推断来源，必须显式给出</b>
 * （它决定切分策略与默认密级，猜错就是"用错策略入库"且检索侧看不出来）；{@code level} 取
 * frontmatter → {@code kind} 的默认密级（见 {@link AccessLevel}）。
 *
 * <p><b>2. 取值非法就报错，不静默回退</b>：写了 {@code level: 绝密} 却存成 INTERNAL，
 * 会让作者以为"标了绝密"而实际是内部 —— <b>标注失效且无人知晓</b>。故
 * {@link AccessLevel#parse} 与 {@link DocumentKind#parse} 都抛异常，由调用方决定整批失败还是跳过这一份。
 *
 * <p><b>3. 路径必须落在知识库根目录内（安全边界）</b>：防路径穿越 —— 直接拼接 {@code ../../}
 * 就能读到库外的系统文件。三步防住：{@code normalize()} 折叠 {@code ..} →
 * {@code toAbsolutePath().normalize()} → <b>断言以根目录开头</b>。
 * 只做第一步不够：符号链接、盘符大小写要靠绝对路径比较才能覆盖。
 */
@Slf4j
@Component
public class SourceLoader {

    /**
     * frontmatter 块：文件<b>最开头</b>的 {@code ---} 到下一个 {@code ---}。
     *
     * <p>{@code \A} 锚定文件开头是必须的 —— 否则正文中间的 {@code ---}（Markdown 的水平分隔线）
     * 会被当成 frontmatter 起点，把一大段正文解析成 YAML。
     */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*(?:\\n|\\z)", Pattern.DOTALL);

    /** 第一个一级标题（用于推断 title）。 */
    private static final Pattern FIRST_H1 =
            Pattern.compile("^#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private final Yaml yaml;

    public SourceLoader() {
        // 限制 YAML 解析规模：frontmatter 是元数据，不该出现超大文档或深层嵌套。
        // 不加限制时，恶意构造的别名炸弹可以让解析消耗巨量内存。
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(64 * 1024);
        this.yaml = new Yaml(options);
    }

    /**
     * 加载一份文档。
     *
     * @param root         知识库根目录（绝对路径）
     * @param source       相对根目录的来源路径（可用 {@code /} 或 {@code \}）
     * @param fallbackKind 文档未声明 kind 时使用的种类（调用方提供的目录级默认值）
     * @param extensions   允许的扩展名（<b>由配置提供</b>，不在此硬编码）
     * @throws IllegalArgumentException 路径越界、文件不存在、格式不支持、frontmatter 取值非法
     * @throws IOException              读取失败
     */
    public LoadedDocument load(Path root, String source, DocumentKind fallbackKind,
                              java.util.List<String> extensions) throws IOException {
        Path file = resolveWithinRoot(root, source);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("知识库文档不存在或不是普通文件：" + source);
        }
        // ★ 扩展名白名单只从配置来（hagent.rag.ingest.extensions），本类不硬编码：
        //   否则在配置里加 ".markdown" 后，批量入库会"先发现该文件、再拒绝它"，
        //   报错文案还指向一个跟配置无关的理由。
        String fileName = file.getFileName().toString().toLowerCase();
        boolean supported = extensions != null && extensions.stream()
                .anyMatch(ext -> ext != null && fileName.endsWith(ext.toLowerCase()));
        if (!supported) {
            throw new IllegalArgumentException(
                    "不支持的文件类型（收到：" + source + "）。"
                            + "允许的扩展名来自 hagent.rag.ingest.extensions：" + extensions
                            + "。docx/pdf 的解析会引入重依赖与大量边界情况，留到后续阶段。");
        }

        // ★ 显式 UTF-8：Windows 上 Files.readString 的默认编码是 GBK 而不是 UTF-8，
        //   不指定会让中文文档读进来就是乱码，且不报任何错。
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        return parse(raw, toSourceKey(source), fallbackKind);
    }

    /**
     * 路径安全解析 —— 见类注释"安全边界"。
     *
     * <p>越界判断放在这里而不是调用方：调用方会有多个（批量入库、单文件入库、
     * 将来的文件监听），每个都记得检查是不可能靠得住的。
     */
    Path resolveWithinRoot(Path root, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("来源路径不能为空");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(source).toAbsolutePath().normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "来源路径越界，已拒绝：source=" + source
                            + "（解析为 " + target + "，不在知识库根目录 " + normalizedRoot + " 内）。"
                            + "这是路径穿越防护 —— 允许它会读到知识库之外的文件。");
        }
        return target;
    }

    /**
     * 把来源路径规范成"文档身份"用的形式：统一分隔符为 {@code /}、去掉开头的 {@code ./}。
     *
     * <p>原因见 {@link LoadedDocument#source()} —— 同一份文档在不同平台必须得到
     * <b>同一个 source</b>，否则"按 source 删旧块"会删不掉，导致重复入库。
     */
    String toSourceKey(String source) {
        String key = source.replace('\\', '/');
        while (key.startsWith("./")) {
            key = key.substring(2);
        }
        return key;
    }

    /**
     * 解析正文与 frontmatter。抽成包内可见方法，便于单测直接喂字符串（不需要真实文件）。
     */
    LoadedDocument parse(String raw, String sourceKey, DocumentKind fallbackKind) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        String body = raw;

        Matcher matcher = FRONTMATTER.matcher(raw);
        if (matcher.find()) {
            frontmatter.putAll(parseYaml(matcher.group(1), sourceKey));
            body = raw.substring(matcher.end());
        }

        DocumentKind kind = frontmatter.containsKey(DocumentMetadata.FIELD_KIND)
                ? DocumentKind.parse(asString(frontmatter.get(DocumentMetadata.FIELD_KIND)))
                : requireFallbackKind(fallbackKind, sourceKey);

        String title = firstNonBlank(
                asString(frontmatter.get(DocumentMetadata.FIELD_TITLE)),
                inferTitleFromBody(body),
                deriveTitleFromSource(sourceKey));
        if (title == null) {
            throw new IllegalArgumentException(
                    "无法确定文档标题（来源=" + sourceKey + "）：frontmatter 没有 title、"
                            + "正文没有一级标题、文件名也无从推断。"
                            + "标题是出处的最后兜底，不能为空。");
        }

        AccessLevel level = frontmatter.containsKey(DocumentMetadata.FIELD_LEVEL)
                ? AccessLevel.parse(asString(frontmatter.get(DocumentMetadata.FIELD_LEVEL)))
                : kind.defaultLevel();

        return new LoadedDocument(
                sourceKey,
                title,
                kind,
                level,
                asString(frontmatter.get(DocumentMetadata.FIELD_DEPT)),
                parseDate(frontmatter.get(DocumentMetadata.FIELD_VALID_FROM),
                        DocumentMetadata.FIELD_VALID_FROM, sourceKey),
                parseDate(frontmatter.get(DocumentMetadata.FIELD_VALID_TO),
                        DocumentMetadata.FIELD_VALID_TO, sourceKey),
                body.strip(),
                frontmatter);
    }

    private DocumentKind requireFallbackKind(DocumentKind fallbackKind, String sourceKey) {
        if (fallbackKind == null) {
            throw new IllegalArgumentException(
                    "文档未声明 kind，且调用方没有提供默认种类（来源=" + sourceKey + "）。"
                            + "kind 决定切分策略与默认密级，猜错的后果是「用错策略入库」且检索侧看不出来，"
                            + "所以必须明确指定。");
        }
        return fallbackKind;
    }

    private Map<String, Object> parseYaml(String yamlText, String sourceKey) {
        Object parsed;
        try {
            parsed = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "frontmatter 不是合法 YAML（来源=" + sourceKey + "）：" + e.getMessage(), e);
        }
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "frontmatter 必须是键值对（来源=" + sourceKey + "），实际是：" + parsed.getClass().getSimpleName());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 从正文的第一个一级标题推断标题 —— 这是 .md 文档最自然的标题来源。 */
    private String inferTitleFromBody(String body) {
        Matcher m = FIRST_H1.matcher(body);
        return m.find() ? m.group(1).strip() : null;
    }

    /** 从文件名推断标题：{@code docs/员工手册.md} → {@code 员工手册}。 */
    private String deriveTitleFromSource(String sourceKey) {
        int slash = sourceKey.lastIndexOf('/');
        String name = slash >= 0 ? sourceKey.substring(slash + 1) : sourceKey;
        int dot = name.lastIndexOf('.');
        String withoutExt = dot > 0 ? name.substring(0, dot) : name;
        return withoutExt.isBlank() ? null : withoutExt.strip();
    }

    /**
     * 解析日期。
     *
     * <p><b>YAML 陷阱</b>：{@code validFrom: 2024-01-01} 不加引号时，YAML 1.1 的隐式类型解析
     * 会把它当成时间戳字面量转成 {@code java.util.Date}，于是 {@code String.valueOf(...)} 拿到
     * {@code Date.toString()} 那种英文格式，再 {@code LocalDate.parse} 必然失败。
     * 正确写法是加引号：{@code validFrom: "2024-01-01"} —— 引号是<b>类型声明</b>，不是装饰。
     *
     * <p>本方法<b>同时接受</b>日期对象（{@code Date} / {@code LocalDate} / {@code LocalDateTime} /
     * {@code Calendar}，直接转换）与字符串（按 {@code yyyy-MM-dd} 解析），作者写不写引号都能工作；
     * 但无法解析时<b>明确报错</b>，不静默忽略 —— 被忽略的生效日期表现为"标了却没生效"，
     * 而这类问题只会在将来做时间过滤时才暴露。
     */
    private LocalDate parseDate(Object value, String field, String sourceKey) {
        if (value == null) {
            return null;
        }
        // YAML 隐式类型解析可能给出日期对象（见方法注释）
        if (value instanceof java.time.LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof java.util.Calendar calendar) {
            return calendar.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }

        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        try {
            // 兼容"2024-01-01T00:00"或带时间部分的写法：只取日期段
            if (trimmed.length() > 10 && trimmed.charAt(10) == 'T') {
                trimmed = trimmed.substring(0, 10);
            }
            return LocalDate.parse(trimmed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "frontmatter 的 " + field + " 不是合法日期（来源=" + sourceKey + "）：" + text
                            + "。要求格式 yyyy-MM-dd（建议加引号写成 \"" + text + "\"，"
                            + "避免 YAML 把它当成时间戳字面量），例如 \"2024-01-01\"。", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.strip();
            }
        }
        return null;
    }
}
