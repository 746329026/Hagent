package com.hagent.exception;

import com.hagent.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类器的测试。
 *
 * <p><b>为什么这个类值得测</b>：它决定了"一次模型调用失败对外表现成什么"。
 * 这里每个用例都对应一个<b>真实会发生的场景</b>，不是为凑覆盖率：
 * 401（实测遇到的）、429（限流）、5xx（上游挂掉）、
 * 连接被拒 / DNS 失败 / 读超时（网络问题的三种典型形态）。
 *
 * <p><b>为什么用 {@link MockClientHttpResponse} 而不是 mock 框架</b>：
 * 它是 Spring 自带的测试桩，能构造出<b>真实的</b>
 * {@code WebClientResponseException} 对象（带真实状态码与响应体），
 * 比用 mock 返回值更接近实际——mock 出来的异常可能连状态码都是假的。
 */
class FailureMapperTest {

    /** 构造一个带真实状态码的 WebClientResponseException，模拟"上游返回了错误响应"。 */
    private static WebClientResponseException responseWith(int status) {
        byte[] body = "{\"error\":{\"message\":\"upstream said no\"}}".getBytes(StandardCharsets.UTF_8);
        return WebClientResponseException.create(
                status,
                HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY,
                body,
                StandardCharsets.UTF_8);
    }

    /** 构造一个"请求根本没发出去"的异常，根因类型可控。 */
    private static WebClientRequestException requestFailure(Throwable rootCause) {
        return new WebClientRequestException(
                rootCause,
                HttpMethod.GET,
                URI.create("https://api.deepseek.com/chat/completions"),
                HttpHeaders.EMPTY);
    }

    // ==================== 识别：哪些异常算"模型调用失败" ====================

    @Test
    @DisplayName("收到错误响应 → 算模型调用失败")
    void responseExceptionIsModelFailure() {
        assertThat(FailureMapper.isModelCallFailure(responseWith(401))).isTrue();
    }

    @Test
    @DisplayName("请求没发出去 → 也算模型调用失败（它【不是】WebClientResponseException 的子类，容易漏）")
    void requestExceptionIsModelFailure() {
        assertThat(FailureMapper.isModelCallFailure(requestFailure(new ConnectException("refused")))).isTrue();
    }

    @Test
    @DisplayName("无关异常 → 不算，避免把业务异常误吞进模型失败分支")
    void unrelatedExceptionIsNotModelFailure() {
        assertThat(FailureMapper.isModelCallFailure(new IllegalStateException("boom"))).isFalse();
        assertThat(FailureMapper.isModelCallFailure(new NullPointerException())).isFalse();
    }

    @Test
    @DisplayName("★ 框架重试层包装 → 算模型调用失败（实测真正抛出的就是它）")
    void aiRetryWrapperIsModelFailure() {
        assertThat(FailureMapper.isModelCallFailure(
                new NonTransientAiException("HTTP 401 - {...}"))).isTrue();
        assertThat(FailureMapper.isModelCallFailure(
                new TransientAiException("HTTP 503 - {...}"))).isTrue();
    }

    // ==================== 第三种形态：框架重试层包装（实测真正抛出的）====================

    /**
     * 真实报文：DeepSeek 在 API Key 无效时返回的完整消息文本。
     * 这是从运行日志里原样抄下来的 —— 用它做测试，保证解析规则对得上真实格式。
     */
    private static final String REAL_DEEPSEEK_401_MESSAGE =
            "HTTP 401 - {\"error\":{\"message\":\"Authentication Fails, Your api key: ****alid is invalid"
                    + " (request_id: c1b429fd-dee1-49d8-9850-26061063df6d)\","
                    + "\"type\":\"authentication_error\",\"param\":null,\"code\":\"invalid_request_error\"}}";

    @Test
    @DisplayName("★★ 真实 DeepSeek 401 报文 → 认证失败 502（这是本次修正要解决的核心场景）")
    void realDeepSeek401MessageMapsToAuthFailed() {
        FailureMapping mapping = FailureMapper.map(
                new NonTransientAiException(REAL_DEEPSEEK_401_MESSAGE));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
        assertThat(mapping.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(mapping.logDetail()).contains("HTTP 401");
    }

    @Test
    @DisplayName("★ 消息里的 request_id 是 8-4-4-4-12 格式，不能被误当成状态码")
    void uuidInBodyIsNotMistakenForStatusCode() {
        // request_id 里含 "26061063" 等三位以上数字段，且整串有连字符
        FailureMapping mapping = FailureMapper.map(
                new NonTransientAiException(REAL_DEEPSEEK_401_MESSAGE));

        // 只要分类正确，就说明没把 body 里的数字当状态码
        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
    }

    @Test
    @DisplayName("包装形态的 429 → 限流")
    void wrappedRateLimitMapsToRateLimited() {
        assertThat(FailureMapper.map(new NonTransientAiException("HTTP 429 - {}")).errorCode())
                .isEqualTo(ErrorCode.MODEL_RATE_LIMITED);
    }

    @Test
    @DisplayName("包装形态的 5xx → 上游不可用")
    void wrappedServerErrorMapsToUnavailable() {
        assertThat(FailureMapper.map(new TransientAiException("HTTP 503 - {}")).errorCode())
                .isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("★ 消息解析不出状态码时 → 按【可重试性】二分，并明确标注解析失败")
    void unparsableMessageFallsBackToRetryability() {
        // NonTransient = 重试无意义
        FailureMapping nonTransient = FailureMapper.map(new NonTransientAiException("连接被重置了"));
        assertThat(nonTransient.errorCode()).isEqualTo(ErrorCode.MODEL_CALL_FAILED);
        assertThat(nonTransient.logDetail()).contains("无法从消息解析");
        assertThat(nonTransient.operatorHint()).contains("可重试性");

        // Transient = 重试可能成功
        FailureMapping transientOne = FailureMapper.map(new TransientAiException("临时故障"));
        assertThat(transientOne.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("消息为 null / 空 → 不抛异常，走兜底")
    void nullOrBlankMessageDoesNotThrow() {
        assertThat(FailureMapper.map(new NonTransientAiException((String) null)).errorCode())
                .isEqualTo(ErrorCode.MODEL_CALL_FAILED);
        assertThat(FailureMapper.map(new NonTransientAiException("   ")).errorCode())
                .isEqualTo(ErrorCode.MODEL_CALL_FAILED);
    }

    @Test
    @DisplayName("★ 非法状态码（如 HTTP 999）不被接受，走兜底而不是造出一个假状态码")
    void outOfRangeStatusCodeIsRejected() {
        FailureMapping mapping = FailureMapper.map(new NonTransientAiException("HTTP 999 - {}"));

        // 999 超出 100~599，应视为解析失败 → 按可重试性归类
        assertThat(mapping.logDetail()).contains("无法从消息解析");
    }

    // ==================== 按 HTTP 状态码分段 ====================

    @Test
    @DisplayName("★ 401 → 认证失败（就是实测遇到的那个场景），HTTP 502 而非 500")
    void unauthorizedMapsToAuthFailed() {
        FailureMapping mapping = FailureMapper.map(responseWith(401));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
        assertThat(mapping.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        // 关键：不能再是"服务内部错误"——那会让客户端误以为可以重试
        assertThat(mapping.errorCode()).isNotEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(mapping.logDetail()).contains("401");
        assertThat(mapping.operatorHint()).contains("API Key");
    }

    @Test
    @DisplayName("403 → 与 401 同类（凭据/权限问题，都不可重试）")
    void forbiddenMapsToAuthFailed() {
        assertThat(FailureMapper.map(responseWith(403)).errorCode()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
    }

    @Test
    @DisplayName("★ 429 → 限流，503；这是唯一『稍后重试有用』的模型故障")
    void tooManyRequestsMapsToRateLimited() {
        FailureMapping mapping = FailureMapper.map(responseWith(429));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_RATE_LIMITED);
        assertThat(mapping.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapping.operatorHint()).contains("重试");
    }

    @Test
    @DisplayName("★ 5xx → 上游自身故障，不是我们崩了")
    void serverErrorMapsToUnavailable() {
        assertThat(FailureMapper.map(responseWith(500)).errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(FailureMapper.map(responseWith(503)).errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("其它 4xx（如模型名不支持）→ 调用方式问题，502；重试无用")
    void otherClientErrorMapsToCallFailed() {
        FailureMapping mapping = FailureMapper.map(responseWith(400));
        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_CALL_FAILED);
        assertThat(mapping.operatorHint()).contains("重试无用");
    }

    // ==================== 按根因类型分段（请求没发出去）====================

    @Test
    @DisplayName("★ 读超时 → 504；必须靠【根因类型】判断，不能匹配 message 文本")
    void socketTimeoutMapsToTimeout() {
        FailureMapping mapping = FailureMapper.map(
                requestFailure(new SocketTimeoutException("Read timed out")));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(mapping.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(mapping.logDetail()).contains("SocketTimeoutException");
    }

    @Test
    @DisplayName("异步超时（TimeoutException）→ 同样归为超时")
    void timeoutExceptionMapsToTimeout() {
        assertThat(FailureMapper.map(requestFailure(new TimeoutException("timed out"))).errorCode())
                .isEqualTo(ErrorCode.MODEL_TIMEOUT);
    }

    @Test
    @DisplayName("DNS 解析失败 → 上游不可达 503")
    void unknownHostMapsToUnavailable() {
        FailureMapping mapping = FailureMapper.map(
                requestFailure(new UnknownHostException("api.deepseek.com")));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(mapping.operatorHint()).contains("DNS");
    }

    @Test
    @DisplayName("连接被拒 → 上游不可达 503")
    void connectRefusedMapsToUnavailable() {
        FailureMapping mapping = FailureMapper.map(
                requestFailure(new ConnectException("Connection refused")));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(mapping.logDetail()).contains("ConnectException");
    }

    @Test
    @DisplayName("未知根因类型 → 仍归为不可达，但日志里写明『需补规则』并带上类名")
    void unknownRootCauseStillClassifiedAndFlagged() {
        FailureMapping mapping = FailureMapper.map(
                requestFailure(new IllegalStateException("某种没见过的问题")));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        // 这两条断言是防"静默兜底"：缺口必须被记录，而不是悄悄吞掉
        assertThat(mapping.logDetail()).contains("IllegalStateException");
        assertThat(mapping.operatorHint()).contains("补一条");
    }

    // ==================== 深度与环保护 ====================

    @Test
    @DisplayName("根因在异常链深处（多层包装）也能取到")
    void rootCauseFoundThroughNestedWrapping() {
        Throwable deep = new ConnectException("deep");
        Throwable wrapped = new RuntimeException(new IllegalStateException(deep));

        FailureMapping mapping = FailureMapper.map(requestFailure(wrapped));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(mapping.logDetail()).contains("ConnectException");
    }

    @Test
    @DisplayName("★ 异常链自引用时不死循环（生产事故里出现过自引用异常）")
    void selfReferencingCauseChainDoesNotHang() {
        Throwable a = new RuntimeException("a");
        Throwable b = new RuntimeException("b", a);
        // 人为制造 a -> b -> a 的环
        a.initCause(b);

        // 只要能返回就说明有保护；这里不该抛异常也不该卡住
        FailureMapping mapping = FailureMapper.map(requestFailure(a));
        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
    }

    // ==================== 未纳入分类的诚实处理 ====================

    @Test
    @DisplayName("★ 非模型调用异常 → 明确标为『未纳入分类』，而不是假装分类成功")
    void nonModelExceptionIsReportedAsUnclassified() {
        FailureMapping mapping = FailureMapper.map(new IllegalStateException("不归我管"));

        assertThat(mapping.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(mapping.operatorHint()).contains("缺少针对该异常类型的规则");
        assertThat(mapping.logDetail()).contains("IllegalStateException");
    }

    // ==================== 脱敏纪律 ====================

    @Test
    @DisplayName("★ 日志详情里不含上游响应体全文（防回显泄露）")
    void logDetailDoesNotLeakResponseBody() {
        FailureMapping mapping = FailureMapper.map(responseWith(401));

        assertThat(mapping.logDetail()).doesNotContain("upstream said no");
        // 但必须保留足够定位问题的信息
        assertThat(mapping.logDetail()).contains("401");
        assertThat(mapping.logDetail()).contains("Unauthorized");
    }

    @Test
    @DisplayName("对外话术取自 ErrorCode 固定文案，不含任何异常细节")
    void clientFacingMessageIsFromErrorCode() {
        FailureMapping mapping = FailureMapper.map(responseWith(401));

        assertThat(mapping.errorCode().getMessage()).isEqualTo("模型服务认证失败，请检查 API Key 配置");
        assertThat(mapping.errorCode().getMessage()).doesNotContain("upstream");
    }
}
