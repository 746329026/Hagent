package com.hagent.document;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库块的<b>元数据 schema</b> —— 字段名与语义的唯一出处。
 *
 * <p>元数据是"入库时写、检索时读"，两段代码完全不相干。若字段名是散落的字符串字面量：
 * 入库写 {@code source}、检索查 {@code sourcePath}，过滤条件永远不命中，
 * <b>而 SQL 不会报错</b>（jsonb 里没有这个键，条件就是 false）；改字段名时漏一处即静默失效。
 * 所以字段名提成 {@code FIELD_*} 常量，"记录 → 存储格式"的转换收口在 {@link #toMap()}，
 * 入库与检索都只引用这里 —— <b>字段名只有一个出处</b>。
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

    // ★ 字段名常量 —— 入库写、检索读，都引用这里（唯一出处）

    /** 来源路径（相对知识库根目录）。如同"文档的身份"。 */
    public static final String FIELD_SOURCE = "source";
    /** 文档标题。出处的最终兜底，不可为空。 */
    public static final String FIELD_TITLE = "title";
    /** 章节标题（.md 的最近一级标题，或"第 N 段"）。允许为空。 */
    public static final String FIELD_SECTION = "section";
    /** 块在文档内的序号，从 0 开始。 */
    public static final String FIELD_CHUNK_INDEX = "chunkIndex";
    /**
     * 块总数。构造器用它做自洽断言（{@code index < total}），
     * 入库集成测试也直查它校验块序号连续 —— 不是"只写不读"的字段。
     */
    public static final String FIELD_CHUNK_TOTAL = "chunkTotal";
    /** 块级内容哈希（SHA-256，十六进制）。 */
    public static final String FIELD_CONTENT_HASH = "contentHash";
    /** 文档级内容哈希（全文 + 关键 frontmatter 的 SHA-256）；文档没变就整篇跳过。 */
    public static final String FIELD_DOC_HASH = "docHash";
    /** 文档种类，用于追溯"这篇是怎么被处理的"。 */
    public static final String FIELD_KIND = "kind";
    /** 密级，检索前过滤按它生成 WHERE 条件。 */
    public static final String FIELD_LEVEL = "level";
    /** 归属部门；空字符串表示全公司。目前只存不用。 */
    public static final String FIELD_DEPT = "dept";
    /** 生效日期；未设置时为空字符串。目前只存不用。 */
    public static final String FIELD_VALID_FROM = "validFrom";
    /** 失效日期；未设置时为空字符串。目前只存不用。 */
    public static final String FIELD_VALID_TO = "validTo";

    /**
     * 紧凑构造器 —— 把"必填字段"的约束变成<b>对象创建时就会失败</b>。
     *
     * <p>不靠调用方自觉：{@code source} 缺失会让"按文档删除"失效，{@code title} 缺失会让出处
     * 无法展示。这类问题<b>写入时不报错，只在将来某个检索结果里表现异常</b>，必须在最早时刻拦住。
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
     * <p>值类型混用 String / int / 空字符串，是因为 jsonb 里数字与字符串不自动转换，
     * 而过滤走 {@code FilterExpressionBuilder} 的 {@code eq} / {@code in} / {@code gte}：
     * <ul>
     *   <li>枚举以 {@link Enum#name() name()} 存<b>字符串</b>（可读、可手工 SQL 查询）；</li>
     *   <li>{@code chunkIndex} / {@code chunkTotal} 存<b>数字</b>（将来可能按序号范围查询）；</li>
     *   <li>日期存 ISO-8601 <b>字符串</b>：字典序等于时间序，
 *       可以直接用 {@code gte} / {@code lte} 比较，且不受时区影响；</li>
     *   <li>可选字段的空值统一为<b>空字符串</b>（不是 null）——
     *       jsonb 里 {@code "dept": ""} 可以被 {@code ne("")} 稳定匹配，
     *       而 {@code null} 在 SQL 三值逻辑下会带来意外的匹配结果。</li>
     * </ul>
     *
     * <p>用 {@link LinkedHashMap} 保持 schema 顺序：人工查询时按字段顺序显示，比哈希顺序易读。
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
