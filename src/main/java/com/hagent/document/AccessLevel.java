package com.hagent.document;

/**
 * 知识库文档的<b>密级</b> —— 决定"谁能检索到它"。
 *
 * <p>用"有序等级"而非"标签集合"：判定可压缩成一次比较
 * {@code doc.level.rank <= user.clearance.rank}，能直接翻译成 pgvector 的
 * {@code SearchRequest.filterExpression}（SQL 的 {@code metadata->>'level' IN (...)}），
 * 在检索阶段就把无权的内容挡在数据库里。
 *
 * <p>默认 {@link #INTERNAL} 而不是 {@code PUBLIC}：两种失败的方向不对称 ——
 * 忘标密级时"不该看的看到了"是安全事故，"该看的看不到"只是功能问题，故默认必须偏保守。
 */
public enum AccessLevel {

    /** 公开：公司对外宣传、公开的制度说明等，任何身份的调用方都可检索。 */
    PUBLIC(0),

    /** 内部（<b>默认值</b>）：仅公司内部可见的常规文档。 */
    INTERNAL(1),

    /** 机密：薪酬、人事、未公开的经营数据等，仅高权限用户可检索。 */
    CONFIDENTIAL(2);

    /**
     * 等级序号，用于比较；数值越小越公开。
     *
     * <p>显式声明而不复用 {@code ordinal()}：ordinal 会被"插入新常量/调整顺序"意外改变，
     * 而等级一旦写进 pgvector metadata 就无法回改。
     */
    private final int rank;

    AccessLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 默认密级 —— 未显式指定时使用。见类注释"为什么默认偏保守"。 */
    public static final AccessLevel DEFAULT = INTERNAL;

    /**
     * 本文档能否被"拥有给定许可等级的用户"看到。
     *
     * <p>这是密级可见规则的<b>唯一定义处</b>（{@link #visibleLevels} 由它派生），
     * 故放在枚举内部而不是散到检索代码里，保证"等级顺序"与"可见规则"永远一致。
     */
    public boolean visibleTo(AccessLevel userClearance) {
        if (userClearance == null) {
            return false;   // 拿不到用户许可 → 一律不可见（不猜、不放行）
        }
        return this.rank <= userClearance.rank;
    }

    /**
     * 某个许可等级<b>能看到的全部密级</b>，按 rank 升序 —— 同一条规则的"检索形态"。
     *
     * <p>{@link #visibleTo} 逐篇判定，本方法供<b>检索前过滤</b>用：把清单翻译成
     * {@code metadata->>'level' IN (...)}，让无权内容留在数据库里。两者必须同源，
     * 所以这里用 {@code filter(visibleTo)} 派生，避免"比较式判定"与"枚举式过滤"给出不同答案
     * （用 {@code IN} 而非数值比较，是因为 metadata 里的 level 存的是字符串）。
     *
     * <p>{@code null} → 空清单，与 {@link #visibleTo} 一致。<b>空清单不是"不过滤"</b>：
     * 当成"全量可见"方向恰好相反，是安全事故。故调用方拿到空清单不应发起检索，
     * {@code RetrievalFilterBuilder} 对空清单显式抛异常（主链路上不可达，属防御性设计）。
     *
     * @param userClearance 用户许可等级；允许为 null
     * @return 可见的密级清单（可能为空），按 rank 升序
     */
    public static java.util.List<AccessLevel> visibleLevels(AccessLevel userClearance) {
        return java.util.Arrays.stream(values())
                .filter(level -> level.visibleTo(userClearance))
                .toList();
    }

    /**
     * 从字符串解析密级，用于读取 .md 的 frontmatter。
     *
     * <p><b>不静默回退到默认值</b>：写了 {@code level: 绝密}（拼错或不存在）却存成 INTERNAL，
     * 会让作者以为"标了绝密"而实际是内部 —— 标注失效且无人知晓，故这里明确抛异常。
     *
     * @throws IllegalArgumentException 取值不在允许范围内时
     */
    public static AccessLevel parse(String raw) {
        return tryParse(raw).orElseThrow(() -> new IllegalArgumentException(
                "未知的密级取值：" + raw + "。允许的取值：" + java.util.Arrays.toString(values())));
    }

    /**
     * <b>宽松</b>解析：匹配规则仍与 {@link #parse} 共用一处，但不决定失败后果。
     *
     * <p>分工：<b>规则收在这里，策略留在调用点</b> ——
     * 入库走 {@link #parse}（<b>拒绝</b>，脏标注必须失败）；
     * 读取既有数据走 {@code tryParse(...).orElse(DEFAULT)}（<b>不中断</b>，
     * 一条脏记录不该让整轮对话失败，由调用点自己记 WARN）。
     *
     * @return 空表示"取值非法"；空白输入返回 {@link #DEFAULT}（与 {@link #parse} 一致）
     */
    public static java.util.Optional<AccessLevel> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.of(DEFAULT);
        }
        String normalized = raw.trim().toUpperCase();
        for (AccessLevel level : values()) {
            if (level.name().equals(normalized)) {
                return java.util.Optional.of(level);
            }
        }
        return java.util.Optional.empty();
    }
}
