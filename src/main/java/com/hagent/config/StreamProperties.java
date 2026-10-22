package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流式（SSE）相关配置项，绑定 {@code hagent.stream.*}。
 *
 * <p>单独建类而不塞进其它 Properties：这些参数服务的是"<b>流式通道本身</b>"
 * （超时、心跳、重连间隔），与管线、Web 层、模型都无关。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.stream")
public class StreamProperties {

    /**
     * SSE 连接的服务端超时（毫秒）。
     *
     * <p><b>取值依据</b>：SseEmitter 默认 30 秒，而多步 Agent / RAG 检索动辄超过它；
     * 超时会被"静默掐断"，前端表现为连接中断，且不一定有明确报错。
     *
     * <p>★ 与 {@code spring.mvc.async.request-timeout} <b>两者取较小值生效</b>，必须协调，不能只调一个。
     */
    private long sseTimeoutMs = 600_000L;

    /**
     * 心跳间隔（毫秒）。
     *
     * <p><b>为什么要心跳</b>：SSE 是长连接，中间网关/Nginx 常按"60 秒无数据"判定空闲并断连；
     * 多步 Agent 的某一步可能很慢（如一次长时间检索），期间没有任何业务事件，连接就会被中间层判死。
     *
     * <p>间隔要小于链路上最短的空闲超时（常见 60s），取 15s 留足余量。
     */
    private long heartbeatIntervalMs = 15_000L;

    /**
     * 告诉浏览器的重连间隔（毫秒）—— 对应 SSE 的 {@code retry} 字段。
     *
     * <p>连接断开后浏览器默认约 3 秒就重连；若服务端正忙或刚崩（如模型侧 5xx），
     * 立刻重连只会加剧压力。这是 SSE 规范提供的"服务端指导客户端退避"手段。
     *
     * <p>只在整个流开始时下发一次，浏览器会记住该值。
     */
    private long reconnectDelayMs = 5_000L;
}
