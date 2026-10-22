package com.hagent.exception;

import com.hagent.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败分类器 —— 把"模型调用失败"翻译成<b>调用方能据以行动</b>的错误码。
 *
 * <p><b>三种异常形态必须全处理</b>（只处理第一种时端到端验证会失败）：
 * <pre>
 *   ① WebClientResponseException   底层直接收到错误响应，自带 getStatusCode()
 *   ② WebClientRequestException    请求根本没发出去（连接被拒 / DNS 失败 / 读超时）
 *   ③ NonTransientAiException      框架重试层包装后的 —— 实际被抛出的就是它
 *      TransientAiException
 * </pre>
 * ①② 与 ③ 各留一条分支：形态取决于走到了哪一层（未进重试层抛 ①②，进了才包装成 ③），
 * 少写一条就漏掉一整类故障。
 *
 * <p>③ 的框架约束：Spring AI 构造这两个异常只用单参数构造器、<b>不带 cause</b>，
 * 原始异常被丢弃，HTTP 状态码只存在于消息文本里（{@code "HTTP 401 - {响应体}"}），
 * 所以只能解析文本 —— 消息由框架生成而非用户输入，格式相对稳定。
 *
 * <p>解析不到状态码时用"可重试性"兜底：{@link NonTransientAiException} → {@code MODEL_CALL_FAILED}，
 * {@link TransientAiException} → {@code MODEL_UNAVAILABLE}；并把"解析失败"写进日志详情，
 * 让格式变动可被发现而不是悄悄降级。
 *
 * <p><b>没有可 catch 的共同父类</b>：Spring AI 2.0.1 里 {@code NonTransientAiException} 与
 * {@code ToolExecutionException} 都直接继承 {@link RuntimeException} 且互不相关，
 * 只能按真实抛出的类型逐个映射 —— 这也是规则集中在本类的原因（分散写必漏）。
 *
 * <p>做成纯函数 + 返回值：不依赖 Spring 上下文与日志实现，每个分支都能直接单测；
 * 新增一种上游失败只需这里加一条规则。
 */
@Slf4j
public final class FailureMapper {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /**
     * 从框架异常消息里取 HTTP 状态码，形如 {@code "HTTP 401 - {body}"}。
     *
     * <p>用 {@code \\b} 界定，避免把响应体里的数字误当成状态码；
     * 只接受 3 位数字，进一步收窄误匹配。
     */
    private static final Pattern HTTP_STATUS_IN_MESSAGE =
            Pattern.compile("\\bHTTP\\s+(\\d{3})\\b");

    private static final int HTTP_SERVER_ERROR_MIN = 500;

    private FailureMapper() {
    }

    /**
     * 本异常是否属于"模型调用失败"（由全局异常处理器单独处理，不落进兜底）。
     *
     * <p>四种都算，缺一不可 —— 每种都对应真实抛出路径：
     * <ul>
     *   <li>{@link WebClientResponseException} —— 底层直接收到错误响应；</li>
     *   <li>{@link WebClientRequestException} —— 请求没发出去（它<b>不是</b>前者子类，只 catch 前者会漏掉"连不上"）；</li>
     *   <li>{@link NonTransientAiException} / {@link TransientAiException} —— 框架重试层包装，
     *       <b>实际被抛到全局处理器的是这两个</b>。</li>
     * </ul>
     */
    public static boolean isModelCallFailure(Throwable e) {
        return e instanceof WebClientResponseException
                || e instanceof WebClientRequestException
                || e instanceof NonTransientAiException
                || e instanceof TransientAiException;
    }

    /**
     * 分类。顺序按"信息量从多到少"：能拿到状态码对象的先处理，只能解析文本的后处理。
     */
    public static FailureMapping map(Throwable e) {
        if (e instanceof WebClientResponseException responseException) {
            return fromHttpStatus(responseException.getStatusCode().value(), detail("", e));
        }
        if (e instanceof WebClientRequestException requestException) {
            return fromRequestFailure(requestException);
        }
        if (e instanceof NonTransientAiException || e instanceof TransientAiException) {
            return fromAiRetryException(e);
        }
        // 不属于模型调用范畴：调用方不应该把这类交给本类。
        // 明确给"未分类"结果，而不是假装分类成功。
        log.warn("[失败分类] 遇到非模型调用异常，本类不做分类：{}", e.getClass().getName());
        return FailureMapping.unclassified(e);
    }

    // 第三种形态：框架重试层包装（实际真正抛出的）

    /**
     * 来自 {@code NonTransientAiException} / {@code TransientAiException}。
     *
     * <p>先尝试从消息文本解析状态码（解析到就得到精确分类）；解析不到时退化为
     * "按可重试性二分"，并把失败原因写进日志详情 —— 让"格式变了、解析失败"可被发现。
     */
    private static FailureMapping fromAiRetryException(Throwable e) {
        boolean retryable = e instanceof TransientAiException;
        Integer status = parseHttpStatus(e.getMessage());

        if (status != null) {
            return fromHttpStatus(status, detail("", e));
        }

        // 解析失败：用"可重试性"这个二分信号兜底，并明确标注解析失败
        ErrorCode code = retryable ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.MODEL_CALL_FAILED;
        return FailureMapping.of(code,
                "无法从消息解析 HTTP 状态码 | 可重试=" + retryable + " | 异常=" + e.getClass().getSimpleName(),
                "框架异常消息里没有可识别的『HTTP 状态码』，已按【可重试性】归类（"
                        + (retryable ? "可重试 → 上游不可用" : "不可重试 → 调用方式问题")
                        + "）。若上游是凭据/限流问题，本条的归类会不够精确——"
                        + "请检查 Spring AI 的消息格式是否变化，并更新 FailureMapper 的解析规则。");
    }

    /** 从消息文本解析 HTTP 状态码；取不到返回 {@code null}（不抛异常、不猜）。 */
    private static Integer parseHttpStatus(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_STATUS_IN_MESSAGE.matcher(message);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                // 只接受合法的 HTTP 状态码区间，防把响应体里的三位数字当状态码
                return (value >= 100 && value <= 599) ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // 按 HTTP 状态码分段（第一、三种形态共用）

    /**
     * 按状态码分段。第一、三种形态共用同一套规则：状态码无论是"从对象取到"还是"从文本解析到"，
     * 分类必须一致，否则同一故障经由不同路径会得到不同错误码。
     */
    private static FailureMapping fromHttpStatus(int value, String baseDetail) {
        if (value == HTTP_UNAUTHORIZED || value == HTTP_FORBIDDEN) {
            return FailureMapping.of(ErrorCode.MODEL_AUTH_FAILED,
                    baseDetail + " | HTTP " + value,
                    "模型提供方拒绝了凭据（HTTP " + value + "）。"
                            + "检查 API Key 是否有效、是否有该模型权限、账户是否欠费。");
        }
        if (value == HTTP_TOO_MANY_REQUESTS) {
            return FailureMapping.of(ErrorCode.MODEL_RATE_LIMITED,
                    baseDetail + " | HTTP " + value,
                    "模型提供方限流（HTTP 429）。这是唯一『稍后重试可能成功』的模型故障，"
                            + "客户端可退避重试；频繁出现需检查配额。");
        }
        if (value >= HTTP_SERVER_ERROR_MIN) {
            return FailureMapping.of(ErrorCode.MODEL_UNAVAILABLE,
                    baseDetail + " | HTTP " + value,
                    "模型提供方自身故障（HTTP " + value + "）。我方无过错，"
                            + "需等待上游恢复或切换备用模型。");
        }
        // 其余 4xx：请求本身不被接受（模型名不支持、参数不合法等）。
        return FailureMapping.of(ErrorCode.MODEL_CALL_FAILED,
                baseDetail + " | HTTP " + value,
                "模型提供方拒绝了请求（HTTP " + value + "）。多为请求参数或模型名不受支持，"
                        + "属于我方调用方式的问题，重试无用。");
    }

    // 第二种形态：请求没发出去

    /**
     * 从<b>根因类型</b>判断是超时还是不可达。
     *
     * <p>必须看根因而非 message：{@code WebClientRequestException} 的 message 是自由文本，
     * 而 {@code cause} 是明确类型；依赖文本匹配会随框架改文案失效，依赖类型不会。
     */
    private static FailureMapping fromRequestFailure(WebClientRequestException e) {
        Throwable root = rootCause(e);

        if (root instanceof SocketTimeoutException || root instanceof TimeoutException
                || root instanceof InterruptedIOException) {
            return FailureMapping.of(ErrorCode.MODEL_TIMEOUT,
                    detail("请求未完成", e),
                    "连接模型提供方超时（根因 " + root.getClass().getSimpleName() + "）。"
                            + "可重试；持续出现需检查网络与超时配置。");
        }
        if (root instanceof UnknownHostException) {
            return FailureMapping.of(ErrorCode.MODEL_UNAVAILABLE,
                    detail("DNS 解析失败", e),
                    "无法解析模型提供方的域名（根因 UnknownHostException）。检查 DNS、代理与网络出口。");
        }
        if (root instanceof ConnectException) {
            return FailureMapping.of(ErrorCode.MODEL_UNAVAILABLE,
                    detail("连接被拒", e),
                    "连接模型提供方被拒绝（根因 ConnectException）。多为网络不通或代理配置错误。");
        }
        return FailureMapping.of(ErrorCode.MODEL_UNAVAILABLE,
                detail("请求发送失败", e),
                "无法连接模型提供方，根因类型尚未纳入已知集合：" + root.getClass().getName()
                        + "。可在 FailureMapper 中补一条更精确的规则。");
    }

    // 工具方法

    /** 组装脱敏日志详情：固定带上异常类名与根因类名，便于按类型排查。 */
    private static String detail(String prefix, Throwable e) {
        String head = prefix.isEmpty() ? "" : prefix + " | ";
        Throwable root = rootCause(e);
        return head + "异常=" + e.getClass().getSimpleName()
                + " | 根因=" + root.getClass().getSimpleName();
    }

    /** 沿 cause 链取到最深处；有环保护，避免异常链自引用导致死循环。 */
    private static Throwable rootCause(Throwable e) {
        Throwable current = e;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 32) {
            current = current.getCause();
        }
        return current;
    }
}
