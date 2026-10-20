package com.hagent.document;

/**
 * 知识库文档的<b>密级</b> —— 决定"谁能检索到它"。
 *
 * <h2>★ 为什么用"有序等级"而不是"标签集合"</h2>
 *
 * <p>权限判定规则因此可以简化成<b>一次比较</b>：
 * <pre>
 *   文档可见  ⟺  doc.level.rank &lt;= user.clearance.rank
 * </pre>
 * 这条规则能<b>直接翻译成 SQL 的 WHERE 条件</b>（本项目走 pgvector 的
 * {@code SearchRequest.filterExpression}），在检索阶段就把无权的内容挡在数据库里。
 *
 * <p>若改用"标签集合"（如 {@code tags = [人事, 薪酬]}），判定就变成多值匹配，
 * 需要额外的关联结构，而本项目的密级语义是<b>明确的层级</b>，用等级更贴合。
 *
 * <h2>★ 为什么默认是 INTERNAL 而不是 PUBLIC</h2>
 *
 * <p>默认值决定了"<b>忘记打标签时会发生什么</b>"：
 * <ul>
 *   <li>默认 {@code PUBLIC} → 一篇忘了标密级的内部文档，<b>对所有人可见</b>；</li>
 *   <li>默认 {@code INTERNAL} → 同样情况下，它<b>只对内部可见</b>。</li>
 * </ul>
 * 两种失败的方向不对称：前者是"不该看到的人看到了"（安全事故），
 * 后者是"该看到的人看不到"（功能问题）。<b>所以默认值必须偏保守。</b>
 *
 * <p>这与本项目在 {@code MemoryConfigValidator} 上采取的立场一致：
 * 出错时选"明确失败"而不是"静默降级"；这里选"更小可见范围"而不是"更大可见范围"。
 *
 * <h2>当前阶段范围（诚实声明）</h2>
 *
 * <p>阶段 6 本轮只做<b>入库</b>：{@code level} 会被正确写入 pgvector 的 metadata，
 * 但<b>还不参与检索过滤</b> —— 过滤（{@code RetrievalFilter} → SQL WHERE）在下一轮实现。
 * 也就是说本轮能验证"密级被正确写入"，<b>不能</b>验证"越权被拦住"。
 */
public enum AccessLevel {

    /** 公开：公司对外宣传、公开的制度说明等，任何身份的调用方都可检索。 */
    PUBLIC(0),

    /** 内部（<b>默认值</b>）：仅公司内部可见的常规文档。 */
    INTERNAL(1),

    /** 机密：薪酬、人事、未公开的经营数据等，仅高权限用户可检索。 */
    CONFIDENTIAL(2);

    /**
     * 等级序号，用于比较。数值越小越公开。
     *
     * <p>用独立的 {@code rank} 而不是 {@code ordinal()}：枚举的 {@code ordinal}
     * 会被"插入新常量"或"调整顺序"意外改变，而它<b>一旦写进数据库就无法回改</b>
     * （已经存在 pgvector metadata 里的字符串要靠 {@link #name()} 反查）。
     * 显式声明数值，让"等级顺序"成为<b>我们主动定义</b>的事实，而不是依赖声明位置。
     */
    private final int rank;

    AccessLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 默认密级 —— 未显式指定时使用。见类注释里"为什么默认偏保守"。 */
    public static final AccessLevel DEFAULT = INTERNAL;

    /**
     * 这个文档能否被"拥有给定许可等级的用户"看到。
     *
     * <p>本阶段尚不参与检索，但先把判定规则写在这里 ——
     * 因为它是密级的<b>语义定义</b>，比起散落在将来的检索代码里，
     * 放在枚举内部更能保证"等级顺序"与"可见规则"永远一致。
     */
    public boolean visibleTo(AccessLevel userClearance) {
        if (userClearance == null) {
            return false;   // 拿不到用户许可 → 一律不可见（不猜、不放行）
        }
        return this.rank <= userClearance.rank;
    }

    /**
     * 某个许可等级<b>能看到的全部密级</b>，按 rank 升序。
     *
     * <h3>★ 为什么需要它：同一条规则的"检索形态"</h3>
     *
     * <p>{@link #visibleTo} 是<b>逐篇判定</b>（取回结果后再筛）；
     * 而本项目要求<b>检索前过滤</b> —— 把"用户能看到哪些密级"变成
     * pgvector 的 {@code SearchRequest.filterExpression}，翻译成 SQL 的
     * {@code metadata->>'level' IN (...)}，让无权的内容留在数据库里。
     *
     * <p>两者是<b>同一条规则的两个形态</b>，所以必须只有一处定义。
     * 这里用 {@code filter(visibleTo)} 从前者派生后者 ——
     * <b>不存在"比较式判定"与"枚举式过滤"给出不同答案"的可能</b>
     * （这正是把方法放在本枚举内部而不是散到检索代码里的理由）。
     *
     * <p>用 {@code IN} 而不是 {@code doc.rank <= user.rank} 的原因：
     * {@code metadata} 列里 {@code level} 存的是<b>字符串</b>（{@code "INTERNAL"}），
     * 不是数字，无法直接做数值比较；枚举出允许的取值是它在 SQL 里的自然形态。
     * 若将来密级种类变多，这个清单会变长，但规则不会变。
     *
     * <h3>★ null 的处理：返回空清单 = 一条都不可见</h3>
     *
     * <p>与 {@link #visibleTo} 保持一致（{@code null} → false）。
     * 调用方拿到空清单时<b>不应发起检索</b> —— 空清单意味着"没有可见的密级"，
     * 而把它当成"不过滤"会变成<b>全量可见</b>，方向恰好相反且是安全事故。
     * 因此 {@code RetrievalFilterBuilder} 对空清单是显式拒绝（抛异常），
     * 而不是产出一个"无害的空条件"。详见该类的注释。
     *
     * <p>注意这条路径在主链路上<b>不可达</b>：{@code UserContext} 的紧凑构造器
     * 已保证 role 非空，而每个 Role 都带一个非空 clearance。
     * 它是防御性设计，不是常规分支。
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
     * <p><b>为什么不静默回退到默认值</b>：如果 frontmatter 写了
     * {@code level: 绝密}（拼错或写了不存在的等级），静默当作 INTERNAL
     * 会让作者以为"我标了绝密"，而实际是内部 —— <b>标注失效且无人知晓</b>。
     * 这类"写了没生效"正是本项目全篇在防的问题，所以这里<b>明确抛异常</b>。
     *
     * @throws IllegalArgumentException 取值不在允许范围内时
     */
    public static AccessLevel parse(String raw) {
        return tryParse(raw).orElseThrow(() -> new IllegalArgumentException(
                "未知的密级取值：" + raw + "。允许的取值：" + java.util.Arrays.toString(values())));
    }

    /**
     * <b>宽松</b>解析："字符串 → 枚举"的规则仍然只有这一处，但不决定后果。
     *
     * <p>★ 阶段 12 第 2 步：这一步之前，三处各自实现了一遍"level 字符串怎么变成枚举"，
     * 而且<b>失败策略互相矛盾</b>（入库抛异常 / 检索静默回退 / 长期记忆 WARN 回退）。
     * 矛盾本身不是错 —— 是因为三处对脏数据的容忍度确实不同；错的是<b>规则</b>被抄了三遍。
     *
     * <p>于是拆成：<b>规则收在这里</b>（{@link #parse} 与 {@code tryParse} 共用同一段匹配逻辑），
     * <b>策略留在调用点</b>：
     * <ul>
     *   <li>入库 → {@link #parse}（<b>拒绝</b>）：作者写了 {@code level: 绝密} 却存成 INTERNAL，
     *       是最坏的静默失配，必须让它失败；</li>
     *   <li>读取既有数据 → {@code tryParse(...).orElse(DEFAULT)}（<b>不中断</b>）：
     *       一条脏记录不该让整轮对话失败，但调用点要能自己记 WARN。</li>
     * </ul>
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
