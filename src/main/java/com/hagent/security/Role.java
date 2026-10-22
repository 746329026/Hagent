package com.hagent.security;

import com.hagent.document.AccessLevel;

import java.util.Arrays;

/**
 * 调用方的<b>角色</b> —— 决定"他能检索到哪些密级的文档"。
 *
 * <p>知识库里既有 {@code PUBLIC} 的公司介绍，也有 {@code CONFIDENTIAL} 的薪酬制度。
 * 没有角色概念，检索就<b>只有一种视角</b>：要么谁都能查到全部（越权），
 * 要么谁都查不到机密（功能缺失）。角色是"把用户映射到许可等级"的那一层。
 *
 * <p>为什么许可等级直接作为枚举常量的字段，而不是另建一张 {@code Map<Role, AccessLevel>}：
 * 两张表失步时（新增角色忘了加映射）<b>不会报错</b>，只会让该角色<b>拿不到任何密级</b>，
 * 表现为"这个人什么也查不到"；做成字段则编译器强制新增角色时填齐。
 *
 * <p>规则本身只有一条：{@code doc.level.rank <= user.clearance.rank}，在 {@link AccessLevel}
 * 内部有两个使用形态 —— {@link AccessLevel#visibleTo} 对<b>单篇文档</b>判定（入库自检、单元测试），
 * {@link AccessLevel#visibleLevels} 产出<b>等级清单</b>供检索翻译成 SQL 的 {@code level IN (...)}
 * （主链路）。后者由前者派生，<b>规则只有一个出处</b>，两种形态不可能给出不同答案。
 */
public enum Role {

    /**
     * 匿名访问者 —— <b>没有携带任何身份凭证</b>的调用方。
     *
     * <p>许可等级最低（{@code PUBLIC}）：只看得到对外公开的内容。
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
     * <p>刻意不提供"取不到就返回 EMPLOYEE"之类的兜底：未认证的调用方由 {@link #ANONYMOUS}
     * 显式表示，而不是靠 null 或异常 —— "匿名"是<b>正常且明确的状态</b>，不是缺失值。
     */
    public AccessLevel clearance() {
        return clearance;
    }

    /**
     * 从字符串解析角色，用于解析自定义 token。
     *
     * <p>未知取值<b>必须抛异常</b>：若 {@code "ADMN"}（拼错）静默降级成 {@code ANONYMOUS}，
     * 调用方会以为自己是管理员却发现什么都查不到，而<b>日志里没有任何线索</b>。
     * 注意这与"<b>没有</b> token"是两件事：没有 token 合法（→ {@link #ANONYMOUS}），拼错的不合法。
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
