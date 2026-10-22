package com.hagent.document;

/**
 * 知识库文档的<b>种类</b> —— 决定"一份文档该怎么被处理"。
 *
 * <p>不同种类对切分的需求差别很大：公司介绍短、几乎任何问题都可能相关、常常没有标题；
 * 员工手册长、按章节组织、只在问政策时相关。若只用一套规则，前者会切得很碎，
 * 后者会从章节中间切断、检索时命中半句话。
 *
 * <p><b>策略跟着枚举走</b>：切分策略与默认密级是常量的一部分（{@link #chunkingStrategy}、
 * {@link #defaultLevel}）。若把它们写在入库服务的 if-else 或另一个 switch 里，
 * 新增种类要改多处，漏改不报错、只会行为不对；放在枚举里则由编译器强制填齐。
 */
public enum DocumentKind {

    /**
     * 公司介绍、企业概况、对外宣传材料。
     *
     * <p>篇幅短、内容稳定、几乎任何问题都可能相关 → "整篇一块"：它的信息是<b>整体性</b>的
     * （"公司是做什么的"需要读完整段才知道），切开反而丢上下文。
     */
    COMPANY_PROFILE(
            "公司介绍", ChunkingStrategy.WHOLE_DOCUMENT, AccessLevel.PUBLIC),

    /**
     * 规章制度、员工手册、管理办法。
     *
     * <p>篇幅长、按章节组织、只在问对应政策时相关 → "按标题切"：标题是作者标注的语义边界，
     * 比按字数硬切合理。
     */
    POLICY(
            "规章制度", ChunkingStrategy.BY_HEADING, AccessLevel.INTERNAL),

    /**
     * 常见问题与解答。
     *
     * <p>天然是"一问一答"的小块，几乎不需要切分 → "按标题切"（每个问题作为一个小节）。
     */
    FAQ(
            "常见问题", ChunkingStrategy.BY_HEADING, AccessLevel.INTERNAL),

    /**
     * 产品文档、操作手册、技术说明。
     *
     * <p>篇幅长、按功能组织、结构可能不规整 → "按标题切"，没有标题时自动降级
     * （见 {@link ChunkingStrategy}）。
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
     * <p><b>不静默回退</b>到某个默认种类：切分策略与默认密级都由种类决定，
     * 回退会让"写错的种类"变成"用另一套策略 + 另一套密级"入库，
     * 且同一批文档里策略不一致，检索侧完全看不出来 —— 故明确抛异常。
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
