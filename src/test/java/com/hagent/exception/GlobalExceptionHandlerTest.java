package com.hagent.exception;

import com.hagent.common.BaseResponse;
import com.hagent.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器的测试。
 *
 * <p><b>为什么值得测</b>：本类决定"同一个故障对外表现成什么"。
 * 这里每个用例都对应一次<b>实测发现的错误映射</b>，不是为凑覆盖率：
 * <ul>
 *   <li>路由不存在曾返回 500（应为 404）—— 调用方会误以为服务崩了；</li>
 *   <li>上游 401 曾返回 500（应为 502）—— 调用方会误重试永远不会成功的请求。</li>
 * </ul>
 *
 * <p><b>为什么直接 new 处理器、用真实异常对象，而不是用 MockMvc / @WebMvcTest</b>：
 * <ul>
 *   <li>本类的职责是"异常 → 响应"的<b>纯映射</b>，不依赖 Spring 上下文。
 *       用 MockMvc 要先起容器（几秒），而这里毫秒级就能跑完；</li>
 *   <li>更关键的是 —— <b>这些缺陷之所以漏到运行时，正是因为"看起来都对"</b>。
 *       直接断言映射结果，比断言"HTTP 经过框架处理后是多少"更贴近我们要保护的东西。</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ==================== 路由不存在 → 404（曾有缺陷：返回 500）====================

    @Test
    @DisplayName("★ 不存在的路径 → 404 + 40004，而不是 500（实测发现的既有缺陷）")
    void noResourceFoundMapsTo404() {
        NoResourceFoundException e = new NoResourceFoundException(
                HttpMethod.GET, "/api/chat/no-such-endpoint", "No static resource chat/no-such-endpoint.");

        ResponseEntity<BaseResponse<Void>> response = handler.handleNoResourceFound(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        // ⚠️ BaseResponse 是 record，访问器是 code() 而非 getCode()
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.API_NOT_FOUND.getCode());
        assertThat(response.getBody().code()).isEqualTo(40004);
    }

    @Test
    @DisplayName("★ 关键断言：404 绝不能是 5xx —— 否则调用方会重试一个永远不会成功的路径")
    void noResourceFoundIsNotServerError() {
        NoResourceFoundException e = new NoResourceFoundException(
                HttpMethod.POST, "/api/nonexistent", "");

        ResponseEntity<BaseResponse<Void>> response = handler.handleNoResourceFound(e);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
        assertThat(response.getBody().code()).isNotEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
    }

    @Test
    @DisplayName("对外话术取自 ErrorCode 固定文案，不回显路径细节")
    void noResourceFoundDoesNotEchoPath() {
        NoResourceFoundException e = new NoResourceFoundException(
                HttpMethod.GET, "/api/secret/internal/path", "");

        ResponseEntity<BaseResponse<Void>> response = handler.handleNoResourceFound(e);

        assertThat(response.getBody().message()).isEqualTo("接口不存在");
        // 路径会进日志（便于定位），但不该原样回显给调用方
        assertThat(response.getBody().message()).doesNotContain("secret");
    }

    // ==================== 方法不支持 → 405（实测发现的第二个同类缺口）====================

    @Test
    @DisplayName("★ 用 GET 访问 POST 接口 → 405 + 40005，而不是 500（浏览器地址栏访问就会触发）")
    void methodNotSupportedMapsTo405() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<BaseResponse<Void>> response = handler.handleMethodNotSupported(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(40005);
        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
    }

    @Test
    @DisplayName("★ 必须回 Allow 头 —— 这是 405 相对 500 的核心价值（告诉调用方该用什么方法）")
    void methodNotSupportedCarriesAllowHeader() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<BaseResponse<Void>> response = handler.handleMethodNotSupported(e);

        // 没有 Allow 头，调用方只知道错了、不知道该怎么改
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
    }

    @Test
    @DisplayName("方法不支持但框架没给出可用方法时 → 不崩，仍返回 405")
    void methodNotSupportedWithoutSupportedMethodsStillWorks() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("TRACE");

        ResponseEntity<BaseResponse<Void>> response = handler.handleMethodNotSupported(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo(40005);
    }

    // ==================== 请求格式不支持 → 415 ====================

    @Test
    @DisplayName("★ Content-Type 不对 → 415 + 40006，而不是 500")
    void mediaTypeNotSupportedMapsTo415() {
        HttpMediaTypeNotSupportedException e =
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<BaseResponse<Void>> response = handler.handleMediaTypeNotSupported(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().code()).isEqualTo(40006);
        // 与 405 回 Allow 同理：告诉调用方该用什么格式
        assertThat(response.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
    }

    // ==================== 协议级错误的统一契约 ====================

    @Test
    @DisplayName("★ 三个协议级错误码都归 4xxxx 分段（调用方看到 4xxxx 就知道不用看服务端日志）")
    void protocolErrorsAreClientSideCodes() {
        assertThat(ErrorCode.API_NOT_FOUND.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.getCode()).isBetween(40000, 49999);

        assertThat(ErrorCode.API_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.getHttpStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("★ 兜底处理器仍存在，且仍是 5xxxx —— 补分支不能把兜底削弱")
    void fallbackStillReturnsInternalError() {
        ResponseEntity<BaseResponse<Void>> response =
                handler.handleUnexpected(new IllegalStateException("某种没人预料到的问题"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        // 兜底话术必须保持"不说细节"
        assertThat(response.getBody().message()).isEqualTo("服务内部错误");
        assertThat(response.getBody().message()).doesNotContain("没人预料到");
    }
}
