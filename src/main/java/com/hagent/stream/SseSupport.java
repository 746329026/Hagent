package com.hagent.stream;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.reactivestreams.Publisher;

/**
 * 统一响应包装的"放行判定"——决定哪些返回值<b>必须原样返回、不能被包装</b>。
 *
 * <p><b>背景（本项目反复确认过的一条核心矛盾）</b>：
 * "统一响应包装"（{@code ResponseBodyAdvice}）会把所有 Controller 返回值
 * 包成 {@code {code,message,data}}。而流式返回（SSE）是"一路往外吐数据"，
 * 一旦被包装，流的格式就被破坏，前端 EventSource 认不出来 ——
 * 表现为"接口没反应"，而服务端<b>可能不报错</b>。
 *
 * <p><b>所以必须放行。</b> 本类把"什么该放行"的判断<b>集中到一处</b>，
 * 供统一响应包装器调用。集中而非散落，是因为这个判断一旦漏掉某个分支，
 * 对应的流式接口就会静默损坏（参考实现里这个判断只有一处，改错就全线崩）。
 *
 * <p><b>为什么是三种（不是一种）</b>：Spring MVC 里"流式返回"有三条不同路径，
 * 各有各的"外观"：
 * <ul>
 *   <li>{@link SseEmitter} —— 本项目阶段 1/3 使用的 SSE 推送器（Java 类型）；</li>
 *   <li>{@link Publisher} —— Reactor 的发布者（{@code Flux} 的父接口），
 *        Spring MVC 也支持直接返回 {@code Flux}；</li>
 *   <li>{@link MediaType#TEXT_EVENT_STREAM} —— 前面两种最终都表现为这个 Content-Type。</li>
 * </ul>
 * 三个都要判断，是因为"流式"在三处都可能出现，漏一处就漏一条路径。
 * 这就是"三个都写"的原因——<b>不是凑数，是防御性覆盖</b>。
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
