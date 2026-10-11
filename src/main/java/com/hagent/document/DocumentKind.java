package com.hagent.document;

/**
 * 知识库文档的<b>种类</b> —— 本项目中"一份文档该怎么被处理"的唯一判据。
 *
 * <h2>★ 为什么需要这个枚举（它到底解决什么问题）</h2>
 *
 * <p>不做这个字段也能入库。但不同种类的文档对"切分"的需求完全不同：
 *
 * <table border="1">
 *   <caption>两类文档的差异</caption>
 *   <tr><th>文档</th><th>长度</th><th>相关性</th><th>结构</th></tr>
 *   <tr>
 *     <td>公司介绍</td>
 *     <td>短（几千字）</td>
 *     <td><b>几乎任何问题都可能相关</b></td>
 *     <td>可能没有标题</td>
 *   </tr>
 *   <tr>
 *     <td>员工手册</td>
 *     <td>长（几万字）</td>
 *     <td><b>只在问政策时相关</b></td>
 *     <td>按章节组织</td>
 *   </tr>
 * </table>
 *
 * <p>如果只用一套切分规则：按标题切会让"没有标题的公司介绍"整篇变成一块或切得很碎；
 * 一律固定字数切会把"员工手册"的章节从中间切断、检索时命中半句话。
 *
 * <h2>★ 核心设计：策略跟着枚举走，而不是散在代码里</h2>
 *
 * <p>本枚举<b>把每种种类的处理策略作为常量的一部分</b>（见 {@link #chunkingStrategy}
 * 与 {@link #defaultLevel}）。这样做是为了防一个具体的陷阱：
 *
 * <pre>
 *   反例：枚举里定义 { POLICY, COMPANY_PROFILE }，
 *        切分策略写在 DocumentIngestService 的 if-else 里，
 *        默认密级写在另一个 switch 里，检索策略写在第三个地方。
 *   → 新增一种文档种类，要改三处；漏改任何一处都不会报错，只会行为不对。
 * </pre>
 *
 * <p>本项目在阶段 3 已经确认过这个陷阱的存在（"双字典"：枚举与提示词里的取值列表
 * 没有任何编译期联系，失步时静默失效）。这里用<b>同一个枚举承载策略</b>来消除它 ——
 * 新增种类时，编译器会强制你在枚举常量里填齐所有策略字段。
 *
 * <h2>★ 诚实声明：一个尚未实现的策略</h2>
 *
 * <p>{@link #COMPANY_PROFILE} 这类"短、且几乎总是相关"的文档，
 * <b>最终不该走检索</b> —— 它应该<b>每次都直接注入</b> prompt（常驻背景知识）。
 * 理由：走检索意味着"用户问公司成立年份时必须恰好命中"，
 * 而常驻注入则是"永远在那儿"，对这类内容更合适，也省掉一次向量检索。
 *
 * <p><b>本阶段不实现常驻注入</b>：它需要"注入哪几篇、注入占多少 token 预算、
 * 与检索结果冲突时谁优先"这些策略，而现在没有真实需求来驱动它们 ——
 * 提前做就是画蛇添足（见 {@code WORKING-AGREEMENT.md} 第 7 条）。
 * 所以这里<b>只留下字段与说明，不留下半成品实现</b>：本阶段
 * {@code COMPANY_PROFILE} 与其他种类一样走检索。
 */
public enum DocumentKind {

    /**
     * 公司介绍、企业概况、对外宣传材料。
     *
     * <p>特征：篇幅短、内容稳定、几乎任何问题都可能相关。
     * 切分策略为"整篇一块" —— 因为它的信息是<b>整体性</b>的
     * （"公司是做什么的"需要读完整段才知道），切开反而丢上下文。
     */
    COMPANY_PROFILE(
            "公司介绍", ChunkingStrategy.WHOLE_DOCUMENT, AccessLevel.PUBLIC),

    /**
     * 规章制度、员工手册、管理办法。
     *
     * <p>特征：篇幅长、按章节组织、只在问对应政策时相关。
     * 切分策略为"按标题切" —— 标题是作者标注的语义边界，比按字数硬切合理。
     */
    POLICY(
            "规章制度", ChunkingStrategy.BY_HEADING, AccessLevel.INTERNAL),

    /**
     * 常见问题与解答。
     *
     * <p>特征：天然是"一问一答"的小块，几乎不需要切分。
     * 切分策略为"按标题切"（每个问题作为一个小节）。
     */
    FAQ(
            "常见问题", ChunkingStrategy.BY_HEADING, AccessLevel.INTERNAL),

    /**
     * 产品文档、操作手册、技术说明。
     *
     * <p>特征：篇幅长、按功能组织、结构可能不规整。
     * 切分策略为"按标题切"，没有标题时自动降级（见 {@link ChunkingStrategy}）。
     */
    PRODUCT_DOC(
            "产品文档", ChunkingStrategy.BY_HEADING, AccessLevel.INTERNAL);

    /** 中文显示名，用于日志与出处展示。 */
    private final String displayName;

    /** 切分策略 —— 决定这份文档怎么被切成块。 */
    private final ChunkingStrategy chunkingStrategy;

    /** 默认密级 —— frontmatter 未显式指定 level 时使用。 */
    private final AccessLevel defaultLevel;

    DocumentKind(String displayName, ChunkingStrategy chunkingStrategy, AccessLevel defaultLevel) {
        this.displayName = displayName;
        this.chunkingStrategy = chunkingStrategy;
        this.defaultLevel = defaultLevel;
    }

    public String displayName() {
        return displayName;
    }

    public ChunkingStrategy chunkingStrategy() {
        return chunkingStrategy;
    }

    public AccessLevel defaultLevel() {
        return defaultLevel;
    }

    /**
     * 从字符串解析种类，用于读取 .md 的 frontmatter。
     *
     * <p><b>为什么不静默回退到某个默认种类</b>：切分策略与默认密级都由种类决定，
     * 静默回退会让"写错的种类"变成"用另一套策略入库 + 另一套密级"。
     * 更糟的是它不仅影响这篇文档，还会让<b>同一批文档里不同文档用了不同策略</b>，
     * 而检索侧完全看不出来。所以这里明确抛异常。
     *
     * @throws IllegalArgumentException 取值不在允许范围内时
     */
    public static DocumentKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "文档种类（kind）不能为空。允许的取值：" + java.util.Arrays.toString(values()));
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        for (DocumentKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "未知的文档种类：" + raw + "。允许的取值：" + java.util.Arrays.toString(values()));
    }
}
