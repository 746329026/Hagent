package com.hagent.security;

import com.hagent.retrieval.KnowledgeSearch;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 身份解析拦截器 —— 把 {@link UserTokenResolver} 的产出挂到本次请求上。
 *
 * <p><b>为什么身份解析必须留在 HTTP 层</b>（放进模型调用层会同时坏三件事）：
 * <ol>
 *   <li><b>粒度不同</b>：认证是"每 HTTP 请求一次"，模型调用层是"每轮调用一次"，
 *       多步循环里会重复解析同一个 token；</li>
 *   <li><b>数据来源不同</b>：认证需要 {@code HttpServletRequest} 才能拿到请求头；管线里没有请求对象，
 *       身份只能靠隐式作用域（字符串 key / ThreadLocal）搬进去，编译器管不到；</li>
 *   <li><b>失败语义不同</b>：认证失败应表达成 HTTP 400/401，放进管线则会被当成"模型调用失败"，
 *       落进 {@code FailureMapper} 的上游错误语义（5xx）。</li>
 * </ol>
 * 还有更实际的一条：拦截器在 Controller <b>之前</b>执行，解析失败时一次检索都还没发生；
 * 放在管线里等于"先花了 embedding 和 SQL、再发现身份有问题"。
 *
 * <p><b>产出用 request attribute，不用 {@code ThreadLocal}</b>：流式接口是
 * {@code SseEmitter} + {@code Flux.subscribe()}，后续的数据推送发生在 Reactor 线程上，
 * 而 <b>ThreadLocal 只在原请求线程有效</b> —— 在订阅回调里读身份会静默拿到 null，
 * 而 null 身份在本项目里意味着降级为匿名（见 {@link UserContext#ANONYMOUS}），
 * 表现为"偶发地什么都查不到"，极难排查。
 *
 * <p>传递链路（全部经过编译期检查）：
 * <pre>
 *   preHandle: request.setAttribute(UserContext.REQUEST_ATTRIBUTE, ctx)
 *     └─ Controller: @RequestAttribute UserContext ctx
 *          └─ ChatAppService.chat(..., ctx) → AgentPipeline.run(..., ctx) → 检索
 * </pre>
 *
 * <p>只拦 {@code /chat/**}：注册在全部 {@code /**} 上会让健康检查、静态资源也走
 * "非法 token 即失败"的路径，而那些端点与身份无关；按 URL 模式注册后，
 * 新增 {@code /knowledge/**} 时只需在 {@code WebConfig} 里加一条 pattern，解析逻辑一行不改。
 */
@Slf4j
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserTokenResolver tokenResolver;

    /**
     * 在 Controller 执行前解析身份并挂到请求上。
     *
     * <p>始终返回 {@code true}：本项目不做"未认证就拒绝"。缺失凭证 → 匿名（最小权限）；
     * 凭证非法 → 抛异常交给全局异常处理器。判定"能不能看某篇文档"不在这里，
     * 而在检索的 SQL WHERE 条件里。
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
