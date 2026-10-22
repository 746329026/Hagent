package com.hagent.stream;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.reactivestreams.Publisher;

/**
 * 统一响应包装的"放行判定"—— 决定哪些返回值<b>必须原样返回、不能被包装</b>。
 *
 * <p>矛盾点：{@code ResponseBodyAdvice} 会把所有 Controller 返回值包成
 * {@code {code,message,data}}，而 SSE 是"一路往外吐数据"，一旦被包装流的格式就被破坏、
 * 前端 EventSource 认不出来 —— 表现为"接口没反应"，而服务端<b>可能不报错</b>。
 *
 * <p>判断必须集中在这一处：一旦漏掉某个分支，对应的流式接口就会静默损坏。
 * 三种形态都要判断，因为 Spring MVC 的流式返回有三条路径、各有各的"外观"，漏一处就漏一条路径：
 * <ul>
 *   <li>{@link SseEmitter} —— 本项目使用的 SSE 推送器（Java 类型）；</li>
 *   <li>{@link Publisher} —— Reactor 的发布者（{@code Flux} 的父接口），Spring MVC 也支持直接返回 {@code Flux}；</li>
 *   <li>{@link MediaType#TEXT_EVENT_STREAM} —— 前两种最终都表现为这个 Content-Type。</li>
 * </ul>
 */
public final class SseSupport {

    private SseSupport() {
        // 工具类，禁止实例化
    }

    /**
     * 判断这个返回值是否属于"必须放行"的流式返回。
     *
     * @param body              Controller 的返回值
     * @param selectedMediaType 本次响应实际选用的媒体类型
     * @return true = 放行（原样返回，不包装）
     */
    public static boolean shouldBypassWrapping(Object body, MediaType selectedMediaType) {
        // 1) 显式用了 SseEmitter
        if (body instanceof SseEmitter) {
            return true;
        }
        // 2) 返回了 Reactor 的发布者（Flux / Mono）
        if (body instanceof Publisher<?>) {
            return true;
        }
        // 3) 媒体类型是 text/event-stream（前两种最终都体现为它）
        return selectedMediaType != null
                && MediaType.TEXT_EVENT_STREAM.includes(selectedMediaType);
    }
}
