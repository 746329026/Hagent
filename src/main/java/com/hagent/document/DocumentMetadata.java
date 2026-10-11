package com.hagent.document;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库块的<b>元数据 schema</b> —— 字段名与语义的唯一出处。
 *
 * <h2>★ 这个类存在的理由：防"双字典"陷阱</h2>
 *
 * <p>元数据是"<b>入库时写、检索时读</b>"的领域 —— 写和读发生在
 * 两段完全不相干的代码里：
 * <pre>
 *   入库：DocumentMetadata.of(...) → toMap() → 写进 pgvector 的 metadata 列
 *   检索：FilterExpressionBuilder.eq("source", ...) → 变成 SQL 的 WHERE
 * </pre>
 *
 * <p>如果字段名是散落的字符串字面量，那么：
 * <ul>
 *   <li>入库写 {@code source}、检索查 {@code sourcePath} → 过滤条件永远不命中，
 *       <b>而 SQL 不会报错</b>（jsonb 里没有这个键，条件就是 false）；</li>
 *   <li>改字段名时全靠全局搜索，漏一处就静默失效。</li>
 * </ul>
 *
 * <p>所以本类把字段名提成 {@code FIELD_*} 常量，并把"记录 → 存储格式"的转换
 * 收口在 {@link #toMap()}。入库与检索都引用这里的常量，<b>字段名只有一个出处</b>。
 * 这与 {@code MessageCodec}（消息序列化的唯一出处）、
 * {@code MemoryTrimmer}（裁剪规则的唯一出处）是同一个思路。
 *
 * <h2>★ 字段分组（每个字段都对应一个具体的代码行为）</h2>
 *
 * <table border="1">
 *   <caption>字段与用途</caption>
 *   <tr><th>分组</th><th>字段</th><th>谁用</th></tr>
 *   <tr><td rowspan="2"><b>标识</b></td>
 *       <td>{@code source}</td>
 *       <td>幂等定位 + 按文档删除 + 出处展示</td></tr>
 *   <tr><td>{@code title}</td><td>出处展示（<b>不允许为空</b>）</td></tr>
 *   <tr><td rowspan="4"><b>出处</b></td>
 *       <td>{@code section}</td><td>出处精确到章节；<b>允许为空</b></td></tr>
 *   <tr><td>{@code chunkIndex}</td><td>顺序排列 + 出处兜底定位</td></tr>
 *   <tr><td>{@code contentHash}</td><td>块级指纹</td></tr>
 *   <tr><td>{@code docHash}</td><td>文档级指纹（文档没变就整篇跳过）</td></tr>
 *   <tr><td rowspan="2"><b>策略</b></td>
 *       <td>{@code kind}</td><td>追溯"这篇是怎么被处理的"</td></tr>
 *   <tr><td>{@code level}</td><td>检索过滤（<b>下一轮</b>实现）</td></tr>
 *   <tr><td rowspan="3"><b>预留</b></td>
 *       <td>{@code dept}</td><td>部门维度（<b>只存不用</b>）</td></tr>
 *   <tr><td>{@code validFrom}</td><td>生效日期（<b>只存不用</b>）</td></tr>
 *   <tr><td>{@code validTo}</td><td>失效日期（<b>只存不用</b>）</td></tr>
 * </table>
 *
 * <h2>★ 空值语义（这比"按文档定制结构"重要得多）</h2>
 *
 * <p>本项目刻意采用<b>统一 schema + 按 {@link DocumentKind} 分策略</b>，
 * 而不是"每种文档一套字段"。所以必须把"字段为空代表什么"定义清楚：
 *
 * <ul>
 *   <li>{@code section} 为空 → 该文档没有可直接引用的章节，
 *       拼 prompt 时降级为"第 N 块"（由 {@code chunkIndex} 兜底）；</li>
 *   <li>{@code dept} 为空 → <b>全公司可见</b>（不是"谁都看不到"）；</li>
 *   <li>{@code validFrom} / {@code validTo} 为空 → <b>未设置 = 永久有效</b>
 *       （避免"忘了填日期导致查不到"）；</li>
 *   <li>{@code title} <b>不允许为空</b> —— 它是出处的最后兜底，
 *       缺了模型就无法说来源，所以入库时缺失应当直接失败。</li>
 * </ul>
 */
public record DocumentMetadata(
        String source,
        String title,
        String section,
        int chunkIndex,
        int chunkTotal,
        String contentHash,
        String docHash,
        DocumentKind kind,
        AccessLevel level,
        String dept,
        LocalDate validFrom,
        LocalDate validTo) {

    // ========================================================================
    // ★ 字段名常量 —— 入库写、检索读，都引用这里（唯一出处）
    // ========================================================================

    /** 来源路径（相对知识库根目录）。如同"文档的身份"。 */
    public static final String FIELD_SOURCE = "source";
    /** 文档标题。出处的最终兜底，不可为空。 */
    public static final String FIELD_TITLE = "title";
    /** 章节标题（.md 的最近一级标题，或"第 N 段"）。允许为空。 */
    public static final String FIELD_SECTION = "section";
    /** 块在文档内的序号，从 0 开始。 */
    public static final String FIELD_CHUNK_INDEX = "chunkIndex";
    /** 该文档一共被切成几块。 */
    public static final String FIELD_CHUNK_TOTAL = "chunkTotal";
    /** 块级内容哈希（SHA-256，十六进制）。 */
    public static final String FIELD_CONTENT_HASH = "contentHash";
    /** 文档级内容哈希（全文 + 关键 frontmatter 的 SHA-256）。 */
    public static final String FIELD_DOC_HASH = "docHash";
    /** 文档种类。 */
    public static final String FIELD_KIND = "kind";
    /** 密级。 */
    public static final String FIELD_LEVEL = "level";
    /** 归属部门；空字符串表示全公司。 */
    public static final String FIELD_DEPT = "dept";
    /** 生效日期；null 表示未设置。 */
    public static final String FIELD_VALID_FROM = "validFrom";
    /** 失效日期；null 表示未设置。 */
    public static final String FIELD_VALID_TO = "validTo";

    /**
     * 紧凑构造器 —— 把"必填字段"的约束变成<b>对象创建时就会失败</b>。
     *
     * <p>为什么不靠调用方自觉：{@code source} 缺失会让"按文档删除"失效，
     * {@code title} 缺失会让出处无法展示。这两者的共同点是
     * <b>不会在写入时报错，只会在将来某个检索结果里表现异常</b> ——
     * 所以必须在最早的时刻拦住。
     */
    public DocumentMetadata {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source（来源路径）不能为空 —— 它是文档的身份，缺了就无法按文档删除或更新");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title（文档标题）不能为空 —— 它是出处的最后兜底，缺了模型无法说明来源");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind（文档种类）不能为空 —— 它决定切分策略与默认密级");
        }
        if (level == null) {
            throw new IllegalArgumentException(
                    "level（密级）不能为空 —— 静默使用默认值会让「标了密级却没生效」无法被发现");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex 不能为负：" + chunkIndex);
        }
        if (chunkTotal <= 0 || chunkIndex >= chunkTotal) {
            throw new IllegalArgumentException(
                    "chunkIndex/chunkTotal 不自洽：index=" + chunkIndex + " total=" + chunkTotal);
        }
        // 空字符串与 null 在 jsonb 里是两个不同的值，统一成空字符串，
        // 避免"检索时按 dept='' 过滤"漏掉那些写成 null 的记录
        section = section == null ? "" : section;
        dept = dept == null ? "" : dept;
    }

    /**
     * 转成 pgvector 的 {@code metadata} 列（jsonb）所需的结构。
     *
     * <p><b>为什么值类型混用 String 与 int / null</b>：
     * {@code FilterExpressionBuilder} 支持 {@code eq} / {@code in} / {@code gte} 等比较，
     * 而 jsonb 里的数字与字符串是不同的类型，比较时不会自动转换。
     * 所以：
     * <ul>
     *   <li>枚举以 {@link Enum#name() name()} 存<b>字符串</b>（可读、可手工 SQL 查询）；</li>
     *   <li>{@code chunkIndex} / {@code chunkTotal} 存<b>数字</b>（将来可能按序号范围查询）；</li>
     *   <li>日期存 ISO-8601 <b>字符串</b>（{@code LocalDate.toString()}）——
     *       字符串形式的 {@code "2024-01-01"} 在字典序上等于时间序，
 *       可以直接用 {@code gte} / {@code lte} 比较，且不受时区影响；</li>
     *   <li>可选字段的空值统一为<b>空字符串</b>（不是 null）——
     *       jsonb 里 {@code "dept": ""} 可以被 {@code ne("")} 稳定匹配，
     *       而 {@code null} 在 SQL 三值逻辑下会带来意外的匹配结果。</li>
     * </ul>
     *
     * <p>用 {@link LinkedHashMap} 保持字段顺序：它让 pgvector 里的 jsonb
     * 在人工查询时<b>按 schema 顺序显示</b>，比哈希顺序易读。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(FIELD_SOURCE, source);
        map.put(FIELD_TITLE, title);
        map.put(FIELD_SECTION, section);
        map.put(FIELD_CHUNK_INDEX, chunkIndex);
        map.put(FIELD_CHUNK_TOTAL, chunkTotal);
        map.put(FIELD_CONTENT_HASH, contentHash == null ? "" : contentHash);
        map.put(FIELD_DOC_HASH, docHash == null ? "" : docHash);
        map.put(FIELD_KIND, kind.name());
        map.put(FIELD_LEVEL, level.name());
        map.put(FIELD_DEPT, dept);
        map.put(FIELD_VALID_FROM, validFrom == null ? "" : validFrom.toString());
        map.put(FIELD_VALID_TO, validTo == null ? "" : validTo.toString());
        return map;
    }
}
