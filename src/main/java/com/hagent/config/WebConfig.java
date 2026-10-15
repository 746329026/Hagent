package com.hagent.config;

import com.hagent.controller.ChatController;
import com.hagent.security.UserContextInterceptor;
import com.hagent.security.UserTokenResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 层配置 —— 目前只做 CORS（跨域）。
 *
 * <p><b>为什么需要它</b>：我们的"极简前端"形态是——一个 HTML 文件用
 * {@code EventSource} 直连 {@code localhost:8123}。如果这个 HTML 是以
 * {@code file://} 打开、或跑在另一个端口（如 5500），那么这次请求就是<b>跨域请求</b>，
 * 浏览器会直接拦掉。所以 CORS 不是"可选优化"，是这条链路能不能通的前提。
 *
 * <p><b>为什么单独建一个 WebConfig，而不像参考实现那样叫 GlobalCorsConfig</b>：
 * Web 层要配的东西不止 CORS（后续还要配异步请求超时、拦截器、消息转换器等）。
 * 按"类名要反映职责边界"的原则，CorsConfig 这个名字会限制它只能放 CORS 相关代码，
 * 后面加别的配置就得再新建一个类，最终散成一堆。一个 WebConfig 收口更清晰。
 *
 * <p><b>★ 一个刻意的安全决定：不用 {@code allowedOriginPatterns("*")}</b>
 * 参考实现里写的是 {@code allowedOriginPatterns("*").allowCredentials(true)}，
 * 这是反模式：<b>对全网放开 + 允许携带凭证</b>，等于把自己的接口对任意网站开放。
 * 本项目改用<b>配置项白名单</b>（{@code hagent.web.allowed-origins}），
 * 只有明确列出的源才放行 —— 与"一切可调参数外置"同源。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({WebProperties.class, StreamProperties.class})
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;
    private final int corsMaxAgeSeconds;

    /** 身份解析拦截器 —— 见下面 {@link #identityInterceptor()} 的说明。 */
    private final UserTokenResolver userTokenResolver;

    /**
     * ★ 用 {@link WebProperties}（{@code @ConfigurationProperties}）而不是
     * {@code @Value("${hagent.web.allowed-origins}")} 注入：
     * YAML 列表在 Environment 里是带下标的（{@code allowed-origins[0]}…），
     * {@code @Value} 只做"按键取值"，取不到；{@code @ConfigurationProperties}
     * 才做"按结构绑定"。实测 {@code @Value} 方式会直接导致启动失败。
     */
    public WebConfig(WebProperties webProperties, UserTokenResolver userTokenResolver) {
        this.allowedOrigins = webProperties.getAllowedOrigins();
        this.corsMaxAgeSeconds = webProperties.getMaxAgeSeconds();
        this.userTokenResolver = userTokenResolver;
        log.info("[Web] CORS 白名单={}  预检缓存={}s", allowedOrigins, corsMaxAgeSeconds);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 白名单：只有列出来的源才允许跨域访问
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                // OPTIONS 必须写：预检请求本身就是 OPTIONS。
                // 漏掉它，所有"非简单请求"（如带 JSON 体的 POST）会直接失败。
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                // 允许前端携带任意自定义请求头。
                // 这里能用 "*" 而 origins 不能，原因在 CORS 规范：起源是安全边界，不允许通配；
                // 请求头只是"携带什么内容"，用通配表示"请求什么就允许什么"。
                .allowedHeaders("*")
                // 暴露给前端的响应头。浏览器默认只允许 JS 读取少数几个"安全"响应头，
                // 其余必须显式暴露，否则前端能拿到响应、却读不到这些头。
                //
                // X-Conversation-Id：阶段 6 引入。会话 ID 通过响应头返回给前端，
                // 前端下次请求带上它才能续用同一个会话（看到历史）。
                // ★ 必须在这里暴露，否则前端读不到 —— 而且不会报错，只是永远拿到 null。
                //
                // ⚠️ X-Request-Id 目前只是"预留"：服务端尚未实现 RequestId 过滤器，
                //    因此前端现在读它只会得到 null，且不会报错。
                //    保留原因：删掉容易在实现过滤器时忘了加回来，导致前端读不到又一轮排查。
                .exposedHeaders("Content-Type", "X-Request-Id",
                        ChatController.CONVERSATION_ID_HEADER)
                // 允许携带凭证（Cookie / Authorization 头 / TLS 客户端证书）。
                // 规范禁止 allowCredentials(true) 与 allowedOrigins("*") 共存，
                // 这是"必须用白名单"的第二个理由（第一个理由是安全上不该对全网开放）。
                .allowCredentials(true)
                // 预检结果的浏览器缓存时长。外置成配置项，因为开发与生产的合理取值相反：
                // 开发要小（改配置立即生效），生产要大（省预检往返）。详见 WebProperties 注释。
                .maxAge(corsMaxAgeSeconds);
    }

    /**
     * 身份解析拦截器 Bean。
     *
     * <p>声明成 Bean（而不是在 {@link #addInterceptors} 里内联 new）是为了
     * 可以在别处按类型引用它（比如在测试里直接拿到同一个实例）。
     *
     * <p><b>★ 它与 {@code AgentPipeline} 不在同一层，不要互相替代</b>：
     * <ul>
     *   <li>身份解析属于 <b>HTTP 层</b>：每个请求进来解析一次，
     *       与"这次要不要调模型"完全无关；</li>
     *   <li>{@code AgentPipeline} 属于<b>模型调用层</b>：它管的是"一次对话怎么编排"，
     *       只在真正走到对话链路时才被调用。</li>
     * </ul>
     * 两者的粒度不同（一个是<b>请求级</b>，一个是<b>对话级</b>），
     * 所以既不能用拦截器代替管线里的步骤，也不能指望管线去承担所有 HTTP 请求的身份解析。
     */
    @Bean
    public UserContextInterceptor identityInterceptor() {
        return new UserContextInterceptor(userTokenResolver);
    }

    /**
     * 注册身份解析拦截器。
     *
     * <h3>★ 为什么只拦 {@code /chat/**}，不是 {@code /**}</h3>
     *
     * <p>身份解析只在"要拿身份去做权限判定"的端点上才有意义。
     * 若拦全部路径，健康检查与静态资源也会走"非法 token 即失败"的分支 ——
     * 而那些端点与身份无关，一次错误的请求头就会让它们不可用。
     *
     * <p>按 URL 模式注册的好处正在于此：将来新增 {@code /knowledge/**}、
     * {@code /workflow/**} 时，<b>只需在这里加一条 pattern</b>，
     * 解析逻辑与 Controller 一行都不改。这也是"身份解析该留在 HTTP 层"的具体优势：
     * <b>拦截器覆盖所有注册过的 HTTP 请求</b>（含将来新增的端点），
     * 而 {@code AgentPipeline} <b>只在对话链路里被调用</b> ——
     * 它只认"有人要来对话"，不认"请求打到了哪个 URL"。
     *
     * <p><b>★ 顺序上的一条硬要求</b>：身份解析失败必须在<b>进入管线之前</b>发生。
     * 若把这件事推给管线，就变成"HTTP 层的鉴权问题被塞进模型调用层"，
     * 而且<b>只有走对话链路的端点才会被检查到</b> —— 将来新增的端点会悄悄绕过它。
     *
     * <h3>★ 这里不做"未认证就拒绝"</h3>
     *
     * <p>拦截器<b>始终放行</b>：未携带凭证 → 匿名（仅可见 PUBLIC）；
     * 凭证非法 → 抛异常交给全局异常处理器。判定"能不能看某篇文档"不在这一层，
     * 而在检索的 SQL 条件里（决策 1）。这与决策 21"匿名 = 最小权限，不拒绝"一致。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor())
                .addPathPatterns("/chat/**");
        log.info("[Web] 身份解析拦截器已注册：/chat/**（未携带凭证 = 匿名，仅可见 PUBLIC）");
    }

    // ==========================================================================
    // 后续将在这里追加（对应架构设计里的其他 Web 层关注点，暂不实现）：
    //
    // 1) SSE 异步超时
    //    多步 Agent / RAG 检索可能远超 Tomcat 默认的 30s，需要调整异步请求超时。
    //    已在 application.yml 里通过 spring.mvc.async.request-timeout 配置，
    //    若后续需要在代码里动态设置（registerBean 方式），会加在这里。
    //
    // 2) 请求链路追踪（RequestId 过滤器）
    //    给每个请求打一个 ID，串起"入口日志 → 业务日志 → 模型调用日志"，
    //    这是可观测性的基础。对应 CORS 里已经预留的 X-Request-Id 响应头。
    //
    // 3) 静态资源映射
    //    阶段 3 做极简前端时，可能把静态 HTML 直接放在 resources/static 下由本服务提供，
    //    这样可以省掉跨域问题（同源）。届时再决定是否需要，不提前加。
    // ==========================================================================
}
