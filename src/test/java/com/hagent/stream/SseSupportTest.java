package com.hagent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SseSupport 放行判定的回归测试。
 *
 * <p>放行判定是"统一响应 vs SSE"冲突的唯一防线：漏掉任何一个分支，对应的流式接口就会静默损坏
 * （前端没反应、服务端不报错），所以必须用测试锁死三种形态。
 */
class SseSupportTest {

    @Test
    void sseEmitterMustBypass() {
        // 流式端点返回 SseEmitter，必须放行
        assertThat(SseSupport.shouldBypassWrapping(new SseEmitter(), null)).isTrue();
    }

    @Test
    void publisherMustBypass() {
        // Spring MVC 也支持直接返回 Flux（Publisher 的子接口），必须放行
        assertThat(SseSupport.shouldBypassWrapping(Flux.just("a", "b"), null)).isTrue();
    }

    @Test
    void eventStreamMediaTypeMustBypass() {
        // 前两种最终都表现为 text/event-stream，这一层是兜底
        assertThat(SseSupport.shouldBypassWrapping("任意字符串内容", MediaType.TEXT_EVENT_STREAM)).isTrue();
    }

    @Test
    void ordinaryValueMustNotBypass() {
        // 普通返回值（如 JSON 对象、字符串）不能放行，必须被包装
        assertThat(SseSupport.shouldBypassWrapping("普通字符串", MediaType.APPLICATION_JSON)).isFalse();
        assertThat(SseSupport.shouldBypassWrapping(new Object(), MediaType.APPLICATION_JSON)).isFalse();
    }

    @Test
    void nullBodyWithNullMediaTypeMustNotBypass() {
        // 边界：body 和 mediaType 都为空，不能放行（否则什么都拦不住）
        assertThat(SseSupport.shouldBypassWrapping(null, null)).isFalse();
    }
}
