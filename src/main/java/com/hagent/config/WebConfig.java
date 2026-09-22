package com.hagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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
@EnableConfigurationProperties(WebProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    /**
     * ★ 用 {@link WebProperties}（{@code @ConfigurationProperties}）而不是
     * {@code @Value("${hagent.web.allowed-origins}")} 注入：
     * YAML 列表在 Environment 里是带下标的（{@code allowed-origins[0]}…），
     * {@code @Value} 只做"按键取值"，取不到；{@code @ConfigurationProperties}
     * 才做"按结构绑定"。实测 {@code @Value} 方式会直接导致启动失败。
     */
    public WebConfig(WebProperties webProperties) {
        this.allowedOrigins = webProperties.getAllowedOrigins();
        log.info("[Web] CORS 白名单已装载：{}", allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 白名单：只有列出来的源才允许跨域访问
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                // 暴露给前端的响应头。SSE 场景下前端常需要读到这些；
                // 默认情况下浏览器只允许 JS 读取少数几个"安全"响应头，其余必须显式暴露。
                .exposedHeaders("Content-Type", "X-Request-Id")
                // 允许携带凭证（Cookie / Authorization 头）。
                // 注意：allowCredentials(true) 与 allowedOrigins("*") 不能共存（规范禁止），
                // 这也是必须用白名单而不是通配符的另一个原因。
                .allowCredentials(true)
                .maxAge(3600);
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
