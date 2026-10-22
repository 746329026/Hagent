package com.hagent.security;

import com.hagent.common.ErrorCode;
import com.hagent.exception.AgentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析 {@code X-Hagent-Token: <userId>:<role>}，<b>只做"身份传递"这一件事</b>。
 *
 * <p>明文 token，无签名、无过期、无吊销 —— 知道格式就能伪造一个 {@code ADMIN}。
 * 这不是疏漏，是刻意的范围限制：本项目只做"身份传递链路"
 * （请求头 → 请求作用域 → 检索的 SQL 条件），签发凭证 / 校验签名 / 过期与吊销
 * 属于认证服务，不在范围内。所以本类的方法名、注释、日志只说"解析"、不说"认证通过"，
 * 以免被后来人误当成安全边界。
 *
 * <p>三种输入、两种处理 —— 这是本类唯一需要判断的语义：
 * <ul>
 *   <li>请求头<b>不存在</b>或为空白 → {@link UserContext#ANONYMOUS}。合法状态：
 *       匿名 = 最小权限，<b>不拒绝</b> —— 既偏保守，又不打断已有验证流程；</li>
 *   <li>请求头存在但<b>格式非法</b>（缺冒号、userId 为空、角色拼错）→ <b>抛出
 *       {@link AgentException}</b>，不静默降级。若把 {@code "ADMN"} 静默当匿名，
 *       调用方会以为"我是管理员却查不到东西"，而日志里毫无线索 ——
 *       两者在<b>安全性</b>上后果相近，在<b>可排障性</b>上完全相反：
 *       前者是正常分支，后者必须报出来。</li>
 * </ul>
 *
 * <p>抛 {@link AgentException} 而不是 {@code IllegalArgumentException}：给后者加全局处理器会把
 * <b>真正的程序错误也当成 400</b>（JDK / 框架到处抛它），等于掩盖 bug。所以"可归因于调用方"的
 * 异常必须携带明确错误码。分层上：领域枚举 {@link Role} 继续抛 {@code IllegalArgumentException}
 * （纯领域，不依赖 Web 错误码），由本类这个 HTTP 边界组件翻译 —— 翻译点只有一个。
 *
 * <p>状态码取 400 而非 401：错误码契约的 4xxxx 段只有参数 / 协议类错误。对格式错误的凭证，
 * 400"请求参数不合法"已足以让调用方知道"该改凭证，而不是重试"；新增 401 语义属于契约变更。
 *
 * <p>不做成接口 + 多实现：当前只有一个解析规则，在唯一实现上抽接口是纯开销；
 * 真要接认证服务时再抽，那时才有第二个实现来"校准"接口形状。
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
            // 未携带凭证 —— 合法，落到最小权限。用 debug 而不是 warn：
            // 匿名是正常路径，打成 warn 会把日志淹掉。
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
            // Role.parse 对未知取值抛 IllegalArgumentException —— 纯领域表达（枚举不依赖 Web 错误码），
            // 本类作为 HTTP 边界组件负责翻译。
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
