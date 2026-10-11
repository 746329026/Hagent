package com.hagent.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 身份解析拦截器 —— 把 {@link UserTokenResolver} 的产出挂到本次请求上。
 *
 * <h2>★ 为什么是拦截器而不是切面（这是本轮讨论得到的关键结论）</h2>
 *
 * <p>本项目同时有"模型调用的横切"（Advisor）与"HTTP 请求的横切"（拦截器），
 * 两者是不同的层次。把身份解析放进 Advisor 会同时坏三件事：
 *
 * <table border="1">
 *   <caption>身份解析为什么必须留在 HTTP 层</caption>
 *   <tr><th>#</th><th>理由</th><th>放 Advisor 的后果</th></tr>
 *   <tr><td>1</td><td><b>粒度不同</b>：认证是"每 HTTP 请求一次"，
 *       Advisor 是"每模型调用一次"</td>
 *       <td>阶段 8 工具循环里会被跑 N 次 —— 重复解析同一个 token</td></tr>
 *   <tr><td>2</td><td><b>数据来源不同</b>：认证需要 {@code HttpServletRequest}
 *       才能拿到请求头</td>
 *       <td>Advisor 只有 {@code context} 这个 Map，必须先手工把 token 搬进去
 *           —— 多一跳，且是字符串 key</td></tr>
 *   <tr><td>3</td><td><b>失败语义不同</b>：认证失败应表达成 HTTP 400/401</td>
 *       <td>Advisor 在 {@code ChatClient} 内部抛异常，会被当成"模型调用失败"，
 *           落进 {@code FailureMapper} 的上游错误语义（5xx）里</td></tr>
 * </table>
 *
 * <p>还有一条更实际的：拦截器在 Controller <b>之前</b>执行，
 * 身份解析失败时<b>一次向量检索都还没发生</b>；而 Advisor 在 Controller 之后，
 * 等于"先花了 embedding 和 SQL、再发现身份有问题"。
 *
 * <h2>★ 产出的传递方式：request attribute，不用 ThreadLocal</h2>
 *
 * <pre>
 *   UserContextInterceptor.preHandle
 *        └─ request.setAttribute(UserContext.REQUEST_ATTRIBUTE, ctx)
 *              └─ Controller: @RequestAttribute(...) UserContext ctx
 *                    └─ ChatAppService.chat(message, conversationId, ctx)   ← 方法参数
 *                          └─ .advisors(a -&gt; a.param(UserContext.CONTEXT_KEY, ctx))
 * </pre>
 *
 * <p><b>为什么不用 {@code ThreadLocal}</b>：本项目的流式接口是
 * {@code SseEmitter} + {@code Flux.subscribe()}，后续的数据推送发生在
 * Reactor 线程上。<b>ThreadLocal 只在原请求线程有效</b>，
 * 一旦有人在订阅回调里读身份就会静默拿到 null ——
 * 而 null 身份在本项目里意味着"降级为匿名"（见 {@link UserContext#ANONYMOUS}），
 * 于是表现为"偶发地什么都查不到"，极难排查。
 *
 * <p>request attribute 没有这个问题：它在请求建立时就写死，
 * 且从拦截器到 Controller 是<b>显式的方法参数传递</b>（编译期检查），
 * 不依赖任何隐式的作用域假设。
 *
 * <h2>★ 为什么只拦 {@code /chat/**}</h2>
 *
 * <p>把它注册在全部 {@code /**} 上会让健康检查、静态资源也走"非法 token 即失败"
 * 的路径 —— 而那些端点与身份无关。按 URL 模式注册的好处正在于此：
 * 将来新增 {@code /knowledge/**}、{@code /workflow/**} 时，
 * <b>只在 {@code WebConfig} 里加一条 pattern</b>，解析逻辑一行不改。
 * 这也正是"身份解析该留在 HTTP 层"的另一个体现。
 */
@Slf4j
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserTokenResolver tokenResolver;

    /**
     * 在 Controller 执行前解析身份并挂到请求上。
     *
     * <p>始终返回 {@code true}：<b>本项目不做"未认证就拒绝"</b>。
     * 缺失凭证 → 匿名（最小权限）；凭证非法 → 抛异常交给全局异常处理器。
     * 判定"能不能看某篇文档"的事情不在这里，在检索的 SQL 条件里
     * （决策 1：过滤是 SQL 的 WHERE 子句，不是独立步骤）。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String headerValue = request.getHeader(UserTokenResolver.TOKEN_HEADER);
        UserContext context = tokenResolver.resolve(headerValue);
        request.setAttribute(UserContext.REQUEST_ATTRIBUTE, context);
        log.debug("[鉴权] {} {} → userId={} role={}",
                request.getMethod(), request.getRequestURI(), context.userId(), context.role());
        return true;
    }
}
