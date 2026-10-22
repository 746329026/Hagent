package com.hagent.config;

import com.hagent.common.ConversationHeaders;
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
 * Web 层配置 —— CORS（跨域）与身份解析拦截器。
 *
 * <p><b>为什么需要 CORS</b>：前端是一个用 {@code EventSource} 直连本服务的 HTML 文件，
 * 若以 {@code file://} 打开、或跑在另一个端口（如 5500），这次请求就是<b>跨域请求</b>，
 * 浏览器会直接拦掉。所以 CORS 不是"可选优化"，是这条链路能不能通的前提。
 *
 * <p><b>为什么叫 WebConfig 而不是 CorsConfig</b>：Web 层要配的不止 CORS（还有拦截器等），
 * 类名要反映职责边界，一个 WebConfig 收口更清晰。
 *
 * <p><b>★ 一个刻意的安全决定：不用 {@code allowedOriginPatterns("*")}</b> ——
 * 对全网放开 + 允许携带凭证等于把自己的接口对任意网站开放。本项目改用<b>配置项白名单</b>
 * （{@code hagent.web.allowed-origins}），只有明确列出的源才放行。
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
     * {@code @Value("${hagent.web.allowed-origins}")} 注入：YAML 列表在 Environment 里是带下标的
     * （{@code allowed-origins[0]}…），{@code @Value} 只做"按键取值"，取不到 ——
     * 用 {@code @Value} 会直接启动失败；{@code @ConfigurationProperties} 才做"按结构绑定"。
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
                // OPTIONS 必须写：预检请求本身就是 OPTIONS；漏掉它，所有"非简单请求"会直接失败。
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                // 请求头可以用 "*" 而 origins 不能：CORS 规范里起源是安全边界，不允许通配；
                // 请求头只是"携带什么内容"，通配表示"请求什么就允许什么"。
                .allowedHeaders("*")
                // 浏览器默认只允许 JS 读取少数几个"安全"响应头，其余必须显式暴露，
                // 否则前端能拿到响应、却读不到这些头（而且不会报错，只是永远拿到 null）。
                //
                // X-Conversation-Id：会话 ID 通过响应头返回，前端下次请求带上它才能续用同一会话。
                // ⚠️ X-Request-Id 目前只是"预留"：服务端尚未实现 RequestId 过滤器，读它只会得到 null；
                //    保留是为了实现过滤器时不忘了加回来。
                .exposedHeaders("Content-Type", "X-Request-Id",
                        ConversationHeaders.ID)
                // 允许携带凭证（Cookie / Authorization 头 / TLS 客户端证书）。规范禁止它与
                // allowedOrigins("*") 共存 —— 这是"必须用白名单"的第二个理由（第一个是安全）。
                .allowCredentials(true)
                // 预检结果的浏览器缓存时长：开发要小（改配置立即生效），生产要大（省预检往返），
                // 所以外置成配置项。详见 WebProperties。
                .maxAge(corsMaxAgeSeconds);
    }

    /**
     * 身份解析拦截器 Bean。
     *
     * <p>声明成 Bean（而不是在 {@link #addInterceptors} 里内联 new）是为了可以在别处按类型引用
     * 同一个实例（比如在测试里）。
     *
     * <p><b>★ 它与 {@code AgentPipeline} 不在同一层，不要互相替代</b>：身份解析属于 <b>HTTP 层</b>
     * （每个请求进来解析一次，与"这次要不要调模型"无关）；{@code AgentPipeline} 属于
     * <b>模型调用层</b>（管"一次对话怎么编排"）。粒度不同（<b>请求级</b> vs <b>对话级</b>），
     * 所以既不能用拦截器代替管线里的步骤，也不能指望管线承担所有 HTTP 请求的身份解析。
     */
    @Bean
    public UserContextInterceptor identityInterceptor() {
        return new UserContextInterceptor(userTokenResolver);
    }

    /**
     * 注册身份解析拦截器。
     *
     * <p>★ 为什么只拦 {@code /chat/**}，不是 {@code /**}：身份解析只在"要拿身份做权限判定"的
     * 端点上才有意义。拦全部路径会让健康检查与静态资源也走"非法 token 即失败"的分支 ——
     * 那些端点与身份无关，一次错误的请求头就会让它们不可用。按 URL 模式注册的好处是：
     * 将来新增 {@code /knowledge/**}、{@code /workflow/**} 时<b>只需在这里加一条 pattern</b>，
     * 解析逻辑与 Controller 一行都不改。
     *
     * <p><b>★ 顺序上的硬要求</b>：身份解析失败必须在<b>进入管线之前</b>发生。推给管线就变成
     * "HTTP 层的鉴权问题塞进模型调用层"，而且<b>只有走对话链路的端点才会被检查到</b> ——
     * 将来新增的端点会悄悄绕过它。
     *
     * <p>★ 这里不做"未认证就拒绝"：拦截器<b>始终放行</b> —— 未携带凭证 → 匿名（仅可见 PUBLIC）；
     * 凭证非法 → 抛异常交给全局异常处理器。判定"能不能看某篇文档"不在这一层，
     * 而在检索的 SQL 条件里（匿名 = 最小权限，不拒绝）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor())
                .addPathPatterns("/chat/**");
        log.info("[Web] 身份解析拦截器已注册：/chat/**（未携带凭证 = 匿名，仅可见 PUBLIC）");
    }
}
