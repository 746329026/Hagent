package com.hagent.security;

import com.hagent.common.ErrorCode;
import com.hagent.exception.AgentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 身份在 HTTP 边界上的行为：把"身份组件的抛出"与"HTTP 响应的形态"放进同一个断言。
 *
 * <p>补的是单元测试与集成测试之间那道缝。{@link UserTokenResolver} 抛异常、
 * {@code GlobalExceptionHandler} 把它翻译成 HTTP —— 两者此前从未在同一个用例里出现，
 * 于是非法 token 被兜底成 500（调用方的错报成服务端故障）而全套测试全绿。
 * 以后改动身份相关的异常类型，这里会立刻失败。
 *
 * <p>用 JDK {@link HttpClient} 发真实请求：{@code RANDOM_PORT} 起真实容器，
 * 经过真实 Filter → Interceptor → Controller → 全局异常处理器，{@code context-path=/api} 真的生效；
 * MockMvc 会绕过一部分容器行为，且 4.x 搬家后的 MockMvc / TestRestTemplate 模块本项目未依赖。
 *
 * <p>只断言确定性路径："无 token → 匿名 → 调模型"需要真实模型（占位 key 得到 502），
 * 依赖网络，由手工端到端实测覆盖；本类只断言拦截器这一层：必须放行，并把身份挂到请求属性上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class ChatEndpointIdentityTest {

    /**
     * 随机端口 —— 用 {@code local.server.port} 这个稳定属性名，而不是可能搬包的 {@code @LocalServerPort}。
     */
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserTokenResolver tokenResolver;

    /**
     * 发一次真实 HTTP 请求。路径带 {@code /api} 前缀 —— 真实容器里 {@code context-path} 会生效；
     * 正文显式 UTF-8 编码，避开客户端编码陷阱。
     */
    private HttpResponse<String> postChat(String tokenHeaderValue) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"hello\"}", StandardCharsets.UTF_8));
        if (tokenHeaderValue != null) {
            builder.header(UserTokenResolver.TOKEN_HEADER, tokenHeaderValue);
        }
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // 一、非法 token 的 HTTP 形态（本条就是漏检的那个缺口）

    @Test
    @DisplayName("★★ 非法 token（无分隔符）→ 真实 HTTP 400 + 4xxxx 错误码，不是 500 兜底")
    void malformedTokenYields400Not500() throws Exception {
        HttpResponse<String> response = postChat("badtoken");

        assertThat(response.statusCode())
                .as("★ 这是漏检的那个缺口：抛异常的是身份组件、翻译成 HTTP 的是全局处理器，"
                        + "此前没有任何用例同时覆盖这两者。实际响应体=%s", response.body())
                .isEqualTo(400);
        assertThat(response.body()).contains(String.valueOf(ErrorCode.INVALID_PARAM.getCode()));
    }

    @Test
    @DisplayName("★★ 未知角色名 → 同样是 400（不是一类错一个 500、一个 400）")
    void unknownRoleAlsoYields400() throws Exception {
        HttpResponse<String> response = postChat("zhangsan:ADMN");

        assertThat(response.statusCode())
                .as("实际响应体=%s", response.body())
                .isEqualTo(400);
        assertThat(response.body()).contains(String.valueOf(ErrorCode.INVALID_PARAM.getCode()));
    }

    @Test
    @DisplayName("★ 非法 token 要在【进入业务之前】就被拒 —— 一次检索都不该发生")
    void malformedTokenIsRejectedBeforeBusinessLogic() {
        // 拦截器在 Controller 之前执行，所以身份解析失败时一次 embedding、一次向量查询都还没发生。
        // 若把身份解析放在切面里（Controller 之后），就会"先花掉检索成本、再发现凭证有问题"。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserTokenResolver.TOKEN_HEADER, "badtoken");

        assertThatThrownBy(() -> new UserContextInterceptor(tokenResolver)
                .preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AgentException.class);
    }

    // 二、拦截器本身的契约：始终放行 + 把身份挂到请求属性

    @Test
    @DisplayName("★ 未携带凭证 → 放行，并把【匿名】挂到请求属性上（决策 21：不拒绝）")
    void missingTokenProceedsAsAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        boolean proceed = new UserContextInterceptor(tokenResolver)
                .preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed)
                .as("拦截器始终放行 —— 本项目不做『未认证就拒绝』")
                .isTrue();
        assertThat(request.getAttribute(UserContext.REQUEST_ATTRIBUTE))
                .as("属性必须被设置，否则 Controller 的 @RequestAttribute 会解析失败")
                .isEqualTo(UserContext.ANONYMOUS);
    }

    @Test
    @DisplayName("★ 携带合法凭证 → 放行，并把解析出的身份挂到请求属性上")
    void validTokenProceedsWithResolvedIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserTokenResolver.TOKEN_HEADER, "zhangsan:EMPLOYEE");

        boolean proceed = new UserContextInterceptor(tokenResolver)
                .preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(UserContext.REQUEST_ATTRIBUTE))
                .isInstanceOf(UserContext.class)
                .isEqualTo(new UserContext("zhangsan", Role.EMPLOYEE));
    }

    // REQUEST_ATTRIBUTE 是拦截器写、Controller 读的唯一出处；
    // 身份改为方法参数传递后不需要第二个常量与它同步，那条"防两处不同步"的用例随之失去意义。
}
