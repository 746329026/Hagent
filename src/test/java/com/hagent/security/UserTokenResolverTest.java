package com.hagent.security;

import com.hagent.common.ErrorCode;
import com.hagent.document.AccessLevel;
import com.hagent.exception.AgentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>自定义 token 的解析规则</b> —— 重点在"缺失"与"非法"必须分开处理。
 *
 * <h2>★ 这两种输入看起来很像，处理方式必须相反</h2>
 *
 * <pre>
 *   缺失（请求头不存在/空白）→ 匿名（仅可见 PUBLIC），不报错   ← 合法的正常路径
 *   非法（有值但解析不出）    → 抛异常                        ← 必须让人看见
 * </pre>
 *
 * <p>如果把两者都当匿名，调用方写错角色名（{@code "ADMN"}）时会以为
 * "我是管理员却查不到东西"，而日志里毫无线索 —— 这正是本项目反复踩的那类坑
 * （配置写了不生效、未知密级被静默当默认值）。决策 8 的立场是"不静默降级"。
 *
 * <p>反过来，如果把两者都当非法，则所有现有的验证流程（curl、前端演示都不带 token）
 * 会全部 401 —— 决策 21 明确选择"匿名 = 最小权限，不拒绝"。
 */
class UserTokenResolverTest {

    private final UserTokenResolver resolver = new UserTokenResolver();

    // ========================================================================
    // 一、缺失 = 匿名（合法）
    // ========================================================================

    @Test
    @DisplayName("★ 请求头不存在 → 匿名，不是报错")
    void missingHeaderBecomesAnonymous() {
        UserContext context = resolver.resolve(null);

        assertThat(context).isEqualTo(UserContext.ANONYMOUS);
        assertThat(context.role()).isEqualTo(Role.ANONYMOUS);
        assertThat(context.clearance())
                .as("匿名的许可等级必须是最低的 PUBLIC —— 默认偏保守")
                .isEqualTo(AccessLevel.PUBLIC);
        assertThat(context.anonymous()).isTrue();
    }

    @Test
    @DisplayName("★ 请求头是空白 → 同样按匿名处理（空白与缺失在语义上是一回事）")
    void blankHeaderBecomesAnonymous() {
        assertThat(resolver.resolve("   ")).isEqualTo(UserContext.ANONYMOUS);
        assertThat(resolver.resolve("")).isEqualTo(UserContext.ANONYMOUS);
    }

    // ========================================================================
    // 二、正常解析
    // ========================================================================

    @Test
    @DisplayName("★ zhangsan:EMPLOYEE → 员工，可见 INTERNAL")
    void parsesEmployeeToken() {
        UserContext context = resolver.resolve("zhangsan:EMPLOYEE");

        assertThat(context.userId()).isEqualTo("zhangsan");
        assertThat(context.role()).isEqualTo(Role.EMPLOYEE);
        assertThat(context.clearance()).isEqualTo(AccessLevel.INTERNAL);
        assertThat(context.anonymous()).isFalse();
    }

    @Test
    @DisplayName("★ 角色名不区分大小写（admin / Admin / ADMIN 都能解析）")
    void roleParsingIsCaseInsensitive() {
        assertThat(resolver.resolve("root:admin").role()).isEqualTo(Role.ADMIN);
        assertThat(resolver.resolve("root:Admin").role()).isEqualTo(Role.ADMIN);
        assertThat(resolver.resolve("root:ANONYMOUS").role()).isEqualTo(Role.ANONYMOUS);
    }

    @Test
    @DisplayName("★ 两侧空白会被去掉（请求头里多打一个空格不该导致失败）")
    void trimsWhitespaceAroundParts() {
        UserContext context = resolver.resolve("  zhangsan : EMPLOYEE  ");

        assertThat(context.userId()).isEqualTo("zhangsan");
        assertThat(context.role()).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    @DisplayName("★ userId 里含冒号 → 按第一个冒号切分，剩余部分不是合法角色因而失败（不悄悄取到 EMPLOYEE）")
    void splitsOnFirstSeparatorOnly() {
        assertThatThrownBy(() -> resolver.resolve("dept:team:EMPLOYEE"))
                .as("实现取第一个冒号，剩余部分为 team:EMPLOYEE。"
                        + "这里刻意不做『取最后一段』的宽容解析 —— 那会把一个写错的 token "
                        + "变成一个能用的 token，掩盖调用方的错误")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("team:EMPLOYEE");
    }

    // ========================================================================
    // 三、非法 = 抛异常（不静默降级）
    // ========================================================================

    @Test
    @DisplayName("★ 角色拼错（ADMN）→ 抛异常，不静默当匿名")
    void unknownRoleIsRejectedNotDowngraded() {
        assertThatThrownBy(() -> resolver.resolve("zhangsan:ADMN"))
                .as("若静默当匿名，调用方会以为『我是管理员却查不到东西』，而日志里毫无线索")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("ADMN");
    }

    @Test
    @DisplayName("★ 缺少分隔符 → 抛异常，且错误信息里给出修法（要么移除该头、要么写对）")
    void missingSeparatorIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("zhangsan"))
                .as("错误信息必须给出修法，否则使用者只能猜")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("格式非法")
                .hasMessageContaining("移除");
    }

    @Test
    @DisplayName("★ userId 为空（:EMPLOYEE）→ 抛异常")
    void emptyUserIdIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(":EMPLOYEE"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    @DisplayName("★ 角色为空（zhangsan:）→ 抛异常")
    void emptyRoleIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("zhangsan:"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("格式非法");
    }

    // ========================================================================
    // 四、★★ 异常必须携带错误码（否则会落进 500 兜底）
    // ========================================================================

    @Test
    @DisplayName("★★ 非法 token 必须是 400 语义（INVALID_PARAM），不能是 500 兜底")
    void invalidTokenCarriesClientErrorCodeNotServerError() {
        // ★ 这条断言是【端到端实测逼出来的】：
        //   第一版这里抛的是 IllegalArgumentException，10 个单元测试全绿，
        //   但真实 HTTP 回来的是 500 {"code":50000,"message":"服务内部错误"} ——
        //   因为 GlobalExceptionHandler 里没有 IllegalArgumentException 的处理器，
        //   它落进了 Exception.class 兜底。
        //
        //   调用方的错被报成服务端故障 → 调用方会去重试或告警，
        //   而"token 写错了"重试一万次也不会成功。
        for (String badToken : List.of("badtoken", "zhangsan:ADMN", ":EMPLOYEE", "zhangsan:")) {
            assertThatThrownBy(() -> resolver.resolve(badToken))
                    .as("token=%s", badToken)
                    .isInstanceOf(AgentException.class)
                    .extracting(e -> ((AgentException) e).getErrorCode())
                    .as("必须是 4xxxx（可归因于调用方），而不是 5xxxx")
                    .isEqualTo(ErrorCode.INVALID_PARAM);
        }

        assertThat(ErrorCode.INVALID_PARAM.getHttpStatus().value())
                .as("400 —— 调用方看到它就知道该改凭证，而不是重试")
                .isEqualTo(400);
    }
}
