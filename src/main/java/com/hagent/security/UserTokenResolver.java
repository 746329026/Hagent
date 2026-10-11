package com.hagent.security;

import com.hagent.common.ErrorCode;
import com.hagent.exception.AgentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自定义 token 的解析器 —— <b>只做"身份传递"这一件事</b>。
 *
 * <h2>★ token 的格式</h2>
 *
 * <pre>
 *   X-Hagent-Token: &lt;userId&gt;:&lt;role&gt;
 *   例：          X-Hagent-Token: zhangsan:EMPLOYEE
 * </pre>
 *
 * <h2>⚠️ 诚实声明：这是明文 token，不是生产级凭证</h2>
 *
 * <p>它<b>没有签名、没有过期、没有吊销</b> —— 任何人只要知道格式就能伪造一个
 * {@code ADMIN}。<b>这不是疏漏，是刻意的范围限制</b>（决策 20）：
 *
 * <pre>
 *   本项目要做的是：把"身份"从请求一路传到检索的 SQL 条件 —— 即"身份传递链路"。
 *   本项目不做的  ：签发凭证、校验签名、过期与吊销 —— 那是认证服务，是另一件事。
 * </pre>
 *
 * <p>所以本类的方法名、注释、日志都刻意不使用"认证通过"这类措辞 ——
 * 只写"解析"。措辞上是解析，就不容易被后来人误当成安全边界。
 *
 * <h2>★ 三种输入，两种处理 —— 这是本类唯一需要判断的语义</h2>
 *
 * <table border="1">
 *   <caption>缺失与非法必须分开处理</caption>
 *   <tr><th>输入</th><th>处理</th><th>为什么</th></tr>
 *   <tr>
 *     <td>请求头<b>不存在</b>或为空白</td>
 *     <td>返回 {@link UserContext#ANONYMOUS}</td>
 *     <td>合法状态。决策 21：匿名 = 最小权限，<b>不拒绝</b> ——
 *         既偏保守，又不打断已有验证流程</td>
 *   </tr>
 *   <tr>
 *     <td>请求头存在但<b>格式非法</b>（缺冒号、userId 为空、角色拼错）</td>
 *     <td><b>抛出异常</b> → 由全局异常处理器返回 400/401</td>
 *     <td>决策 8：不静默降级。若把 {@code "ADMN"} 静默当匿名，
 *         调用方会以为"我是管理员却查不到东西"，而日志里毫无线索</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么这条区分很重要</b>：项目里已经出现过两次同类问题
 * （配置写了不生效、未知密级被静默当默认值）。"缺失"和"写错"在
 * <b>安全性</b>上后果相近（都看不到东西），但在<b>可排障性</b>上完全相反 ——
 * 前者是正常分支，后者必须报出来。
 *
 * <h2>★ 抛什么异常：{@link AgentException} 而不是 {@code IllegalArgumentException}</h2>
 *
 * <p><b>这是端到端实测逼出来的修正，值得记牢。</b>第一版这里抛的是
 * {@code IllegalArgumentException}，单元测试全绿（10 个用例），
 * 但真实 HTTP 请求回来的是：
 *
 * <pre>
 *   POST /api/chat  X-Hagent-Token: badtoken
 *   → HTTP 500  {"code":50000,"message":"服务内部错误"}     ← ❌ 调用方的错被报成服务端故障
 * </pre>
 *
 * <p><b>根因</b>：{@code GlobalExceptionHandler} 里<b>没有</b>
 * {@code IllegalArgumentException} 的处理器，于是它落进 {@code Exception.class} 兜底 → 500。
 * 这正是 {@code PROCEDURES.md} 踩坑 #10 记录过的同一类问题
 * （协议级异常因"没有显式处理器"被报成 500），当时的结论是
 * "<b>补异常分支要按『一类』补</b>"。
 *
 * <p><b>为什么必须按"类"补而不是只修这一个抛点</b>：{@code IllegalArgumentException}
 * 在 Java 里被到处抛出（框架内部、JDK 内部都会抛），给它加一个全局处理器会把
 * <b>真正的程序错误也当成 400</b> —— 那是在掩盖 bug。
 * 所以正确做法是：<b>让"可归因于调用方"的异常携带明确的错误码</b>，
 * 由 {@link AgentException} 那条既有分支处理。
 *
 * <p>这与项目既有的分层一致：<b>领域枚举（如 {@code Role}）继续抛
 * {@code IllegalArgumentException}</b>（纯领域，不依赖 Web 错误码），
 * 由本类这个"HTTP 边界组件"负责翻译成 {@link AgentException}。
 * 翻译点只有一个，边界清晰。
 *
 * <p>⚠️ <b>状态码取 400 而不是 401</b>：项目的错误码契约里没有 401 语义
 * （{@code ErrorCode} 的 4xxxx 段只有参数/协议类错误）。
 * 对一个格式错误的凭证，400"请求参数不合法"足以让调用方知道
 * "该改凭证，而不是重试"。是否要新增一个 401 语义的错误码属于**契约变更**，
 * 留给本人决定（见 {@code PROCEDURES.md} 的"需要本人决策的事"）。
 *
 * <h2>为什么不做成接口 + 多实现</h2>
 *
 * <p>本项目当前只有一个解析规则，没有第二个实现（真实认证服务不在范围内）。
 * 在只有一个实现之上加接口层是纯开销（协议第 7 条的警戒信号）。
 * 将来真接入认证服务时再抽接口，那时才有第二个实现来"校准"接口形状。
 */
@Slf4j
@Component
public class UserTokenResolver {

    /** 承载身份的自定义请求头名。集中定义，避免裸字符串散落。 */
    public static final String TOKEN_HEADER = "X-Hagent-Token";

    /** userId 与 role 的分隔符。 */
    private static final String SEPARATOR = ":";

    /**
     * 从请求头取值解析身份。
     *
     * @param tokenHeaderValue 请求头的原始值；允许为 null（表示未携带）
     * @return 解析出的身份；未携带时返回 {@link UserContext#ANONYMOUS}（非 null）
     * @throws AgentException 携带了但格式非法时（错误码 {@link ErrorCode#INVALID_PARAM}，HTTP 400）
     */
    public UserContext resolve(String tokenHeaderValue) {
        if (tokenHeaderValue == null || tokenHeaderValue.isBlank()) {
            // 未携带凭证 —— 合法，落到最小权限。这里用 debug 而不是 warn：
            // 匿名是正常路径（当前所有验证流程都走它），打成 warn 会把日志淹掉。
            log.debug("[鉴权] 未携带 {} 头 → 按匿名处理（仅可见 PUBLIC）", TOKEN_HEADER);
            return UserContext.ANONYMOUS;
        }

        String raw = tokenHeaderValue.trim();
        int idx = raw.indexOf(SEPARATOR);
        if (idx <= 0 || idx == raw.length() - 1) {
            throw new AgentException(ErrorCode.INVALID_PARAM, String.format(
                    "%s 头格式非法：期望 <userId>%s<role>，实际为「%s」。"
                            + "若不打算携带身份，请**移除该请求头**而不是留一个空值或错值 —— "
                            + "移除是合法的匿名访问，写错则不合法。",
                    TOKEN_HEADER, SEPARATOR, raw));
        }

        String userId = raw.substring(0, idx).trim();
        String roleText = raw.substring(idx + 1).trim();
        if (userId.isEmpty()) {
            throw new AgentException(ErrorCode.INVALID_PARAM,
                    TOKEN_HEADER + " 头格式非法：userId 为空（" + raw + "）");
        }

        Role role;
        try {
            // Role.parse 对未知取值抛 IllegalArgumentException —— 那是"纯领域"的表达，
            // 不在这里改它（枚举不该依赖 Web 错误码）。本类作为 HTTP 边界组件负责翻译。
            role = Role.parse(roleText);
        } catch (IllegalArgumentException e) {
            throw new AgentException(ErrorCode.INVALID_PARAM, e.getMessage());
        }

        UserContext context = new UserContext(userId, role);
        log.debug("[鉴权] 已解析身份：userId={} role={} → 可见范围={}",
                context.userId(), role, context.clearance());
        return context;
    }
}
