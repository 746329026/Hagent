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
 * <b>身份在 HTTP 边界上的行为</b> —— 补的是"单元测试与集成测试之间那道缝"。
 *
 * <h2>★ 为什么必须单独有这一个测试类（一次真实漏检）</h2>
 *
 * <p>第一版实现里 {@link UserTokenResolver} 对非法 token 抛
 * {@code IllegalArgumentException}。当时：
 * <ul>
 *   <li>{@code UserTokenResolverTest} 10 个用例<b>全绿</b>（它断言的就是"抛这个异常"）；</li>
 *   <li>全量 177 个用例<b>全绿</b>。</li>
 * </ul>
 * 而真实 HTTP 请求的结果是：
 *
 * <pre>
 *   POST /api/chat   X-Hagent-Token: badtoken
 *   → HTTP 500  {"code":50000,"message":"服务内部错误"}      ← ❌ 调用方的错被报成服务端故障
 * </pre>
 *
 * <p><b>根因</b>：{@code GlobalExceptionHandler} 里没有
 * {@code IllegalArgumentException} 的处理器 → 落进 {@code Exception.class} 兜底 → 500。
 *
 * <p><b>为什么全套测试都没抓到</b>：现有测试覆盖了"组件内部行为"与"真库链路"，
 * 但没有任何一个用例<b>经过 MVC 的异常处理路径</b> ——
 * 抛异常的那个类和把它翻译成 HTTP 响应的那个类，<b>从未在同一个用例里同时出现</b>。
 * 这正是本项目反复强调的那条：<b>单测全绿 ≠ 功能可用</b>
 * （阶段 2 的 401 被报成 500 是同一类问题）。
 *
 * <p>所以本类补上这一层：<b>把"身份组件的抛出"与"HTTP 响应的形态"放进同一个断言</b>。
 * 以后再有人改动身份相关的异常类型，这里会立刻失败。
 *
 * <h2>★ 为什么用 JDK 的 {@link HttpClient} 直接发真实请求</h2>
 *
 * <p>三个原因：
 * <ol>
 *   <li><b>证据强度</b>：{@code webEnvironment = RANDOM_PORT} 起真实的 Servlet 容器，
 *       于是经过的是真实的 Filter → Interceptor → Controller → 全局异常处理器
 *       → 真实的消息转换器，并且 <b>{@code server.servlet.context-path=/api} 真的生效</b>。
 *       MockMvc 会绕过一部分容器行为 —— 而"能被绕过的那部分"恰恰最容易藏问题；</li>
 *   <li><b>不引入新依赖</b>：Spring Boot 4 把 {@code @AutoConfigureMockMvc} 挪到了
 *       {@code spring-boot-webmvc-test}、把 {@code TestRestTemplate} 挪到了
 *       {@code spring-boot-resttestclient}（本项目都未依赖）。
 *       为一个断言去加两个依赖不值得 —— 而 JDK 自带 HTTP 客户端；</li>
 *   <li><b>避免再踩"框架模块搬家"</b>：本类已经踩过一次
 *       （{@code @AutoConfigureMockMvc} 编译不过），用 JDK 类型就没有这个问题。</li>
 * </ol>
 *
 * <h2>★ 只测确定性路径</h2>
 *
 * <p>"未携带 token → 匿名 → 走到模型调用"需要真实模型（占位 key 会得到 502），
 * 依赖网络，不适合放进测试套件。它由"手工端到端实测"覆盖
 * （见 {@code docs/PROCEDURES.md} 本阶段的验证证据）。
 * 本类只断言<b>拦截器这一层</b>：它必须放行、并把身份挂到请求属性上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class ChatEndpointIdentityTest {

    /**
     * 随机端口 —— 用 {@code @Value} 而不是 {@code @LocalServerPort}：
     * 后者在 Spring Boot 4 里也可能换了包，而 {@code local.server.port} 这个属性名是稳定的。
     */
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserTokenResolver tokenResolver;

    /**
     * 发一次真实 HTTP 请求。
     *
     * <p>路径带 {@code /api} 前缀 —— 因为这是真实容器，
     * {@code server.servlet.context-path} 会生效（这也正是它比 MockMvc 更可信的地方）。
     * 正文显式用 UTF-8 编码，避开本项目已经踩过的客户端编码陷阱。
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

    // ========================================================================
    // 一、★ 非法 token 的 HTTP 形态（本条就是漏检的那个缺口）
    // ========================================================================

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
        // 拦截器在 Controller 之前执行，所以身份解析失败时
        // 一次 embedding、一次向量查询都还没发生。
        // 若把身份解析放在切面里（Controller 之后），就会"先花掉检索成本、再发现凭证有问题"。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserTokenResolver.TOKEN_HEADER, "badtoken");

        assertThatThrownBy(() -> new UserContextInterceptor(tokenResolver)
                .preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AgentException.class);
    }

    // ========================================================================
    // 二、拦截器本身的契约：始终放行 + 把身份挂到请求属性
    // ========================================================================

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

    // ========================================================================
    // ★ 阶段 8 删除了一个用例：requestAttributeAndContextKeyAreSameConstant
    //
    //   它断言 UserContext.REQUEST_ATTRIBUTE == UserContext.CONTEXT_KEY，
    //   存在的【唯一理由】是"两个常量取值必须一致，改一处忘一处不会报错"。
    //
    //   阶段 8 删掉了 CONTEXT_KEY（身份不再经 ChatClientRequest.context 传递，
    //   改为方法参数），于是"两处需要同步"这个问题被【结构性消灭】——
    //   只剩一个常量，无从不同步。断言因此失去存在意义，而不是"需要迁移"。
    //
    //   ★ 这正是一次重构在测试上的体现：一条防"两处不同步"的断言，
    //     随"第二处"一起消失。REQUEST_ATTRIBUTE 保留 —— 它仍是拦截器写、
    //     Controller 读的真实名字，是唯一出处。
    // ========================================================================
}
