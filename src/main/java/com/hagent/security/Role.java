package com.hagent.security;

import com.hagent.document.AccessLevel;

import java.util.Arrays;

/**
 * 调用方的<b>角色</b> —— 决定"他能检索到哪些密级的文档"。
 *
 * <h2>★ 它解决什么问题</h2>
 *
 * <p>知识库里既有 {@code PUBLIC} 的公司介绍，也有 {@code CONFIDENTIAL} 的薪酬制度。
 * 如果没有"角色"这个概念，检索就<b>只有一种视角</b>：要么谁都能查到全部（越权），
 * 要么谁都查不到机密（功能缺失）。角色是"把用户映射到许可等级"的那一层。
 *
 * <h2>★ 为什么角色直接携带许可等级，而不是另建一张映射表</h2>
 *
 * <p>反例（本项目一直在防的"双字典"陷阱）：
 * <pre>
 *   enum Role   { ANONYMOUS, EMPLOYEE, ADMIN }
 *   Map&lt;Role, AccessLevel&gt; CLEARANCE = ...   ← 另一本字典
 * </pre>
 * 两本字典失步时（新增一个角色但忘了加映射）<b>不会报错</b>，
 * 只会让这个角色<b>拿不到任何密级</b>，表现为"这个人什么也查不到"。
 *
 * <p>把 {@code clearance} 作为枚举常量的字段，新增角色时编译器强制填齐 ——
 * 这与 {@link com.hagent.document.DocumentKind} 承载切分策略是同一个手法。
 *
 * <h2>★ 与 {@link AccessLevel#visibleTo} 的关系（两处都在表达同一条规则）</h2>
 *
 * <p>规则本身只有一条：{@code doc.level.rank <= user.clearance.rank}。
 * 它有两个使用形态：
 * <ul>
 *   <li>{@link AccessLevel#visibleTo} —— 对<b>单篇文档</b>做判定（入库自检、单元测试用）；</li>
 *   <li>{@link AccessLevel#visibleLevels} —— 产出<b>等级清单</b>，
 *       供检索时翻译成 SQL 的 {@code level IN (...)}（主链路用）。</li>
 * </ul>
 * 两者都在 {@link AccessLevel} 内部，且后者由前者派生 —— <b>规则只有一个出处</b>，
 * 不存在"比较式判定"和"枚举式过滤"给出不同答案的可能。
 */
public enum Role {

    /**
     * 匿名访问者 —— <b>没有携带任何身份凭证</b>的调用方。
     *
     * <p>许可等级是最低的 {@code PUBLIC}：只看得到对外公开的内容。
     * 见下方"匿名为什么不拒绝"。
     */
    ANONYMOUS("匿名访问者", AccessLevel.PUBLIC),

    /** 普通员工 —— 公司内部文档可见，机密不可见。 */
    EMPLOYEE("普通员工", AccessLevel.INTERNAL),

    /** 管理员 —— 全部密级可见。 */
    ADMIN("管理员", AccessLevel.CONFIDENTIAL);

    /** 中文显示名，用于日志。 */
    private final String displayName;

    /** 该角色对应的许可等级 —— 直接决定他能看到哪些密级。 */
    private final AccessLevel clearance;

    Role(String displayName, AccessLevel clearance) {
        this.displayName = displayName;
        this.clearance = clearance;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 该角色的许可等级。
     *
     * <p><b>★ 刻意不提供"取不到就返回 EMPLOYEE"之类的兜底</b>：
     * 未认证的调用方由 {@link #ANONYMOUS} 显式表示，而不是靠 null 或异常。
     * "匿名"是一个<b>正常且明确的状态</b>，不是一个缺失值。
     */
    public AccessLevel clearance() {
        return clearance;
    }

    /**
     * 从字符串解析角色，用于解析自定义 token。
     *
     * <p><b>为什么未知取值必须抛异常</b>（本项目的一致性立场）：
     * 如果 {@code "ADMN"}（拼错）静默降级成 {@code ANONYMOUS}，
     * 调用方会以为自己是管理员却发现什么都查不到，而<b>日志里没有任何线索</b>。
     * 抛出异常则能在入口处立刻暴露"token 写错了"。
     *
     * <p>注意这与"<b>没有</b> token 时的处理"是两件事：
     * 没有 token 是合法的（→ {@link #ANONYMOUS}），拼错的 token 不是。
     * 详见 {@link UserTokenResolver}。
     *
     * @throws IllegalArgumentException 取值不在允许范围内时
     */
    public static Role parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "角色不能为空。允许的取值：" + Arrays.toString(values()));
        }
        String normalized = raw.trim().toUpperCase();
        for (Role role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException(
                "未知的角色：" + raw + "。允许的取值：" + Arrays.toString(values()));
    }
}
