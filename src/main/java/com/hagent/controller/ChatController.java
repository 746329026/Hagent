package com.hagent.controller;

import com.hagent.app.ChatAppService;
import com.hagent.common.BaseResponse;
import com.hagent.dto.ChatRequest;
import com.hagent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口 —— 阶段 1 的核心入口。
 *
 * <p><b>薄控制器原则</b>：只做三件事 —— 收参数、调 app 层、组织返回。
 * 没有任何业务判断、不直接接触模型。
 *
 * <p><b>同步与流式都在这里</b>，但返回类型不同：
 * <ul>
 *   <li>{@link #chat} 返回 {@link BaseResponse}（统一契约包装）</li>
 *   <li>{@link #stream} 返回 {@link SseEmitter}（流式，不能包装，必须放行）</li>
 * </ul>
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAppService chatAppService;

    /**
     * 同步对话。
     */
    @PostMapping
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return BaseResponse.success(chatAppService.chat(request.message()));
    }

    /**
     * 流式对话 —— 返回 SSE 命名事件流。
     *
     * <p><b>返回 SseEmitter 而不是 BaseResponse</b>：这是流式接口，
     * 返回的是"一路往外吐的事件流"，不能被统一响应包装。
     * （统一响应包装器会通过 SseSupport.shouldBypassWrapping 对 SseEmitter 放行。）
     *
     * <p><b>为什么 produces 声明 text/event-stream</b>：告诉 Spring 与前端，
     * 这个响应的媒体类型是 SSE。前端用 EventSource 连接时也要求这个 Content-Type。
     *
     * <p><b>超时</b>：SseEmitter 默认 30 秒。多步 Agent 会远超这个值，
     * 所以这里显式传一个较长的超时（单位毫秒），与 application.yml 里的
     * spring.mvc.async.request-timeout 协同 —— 两者取较小值生效。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.stream(request.message(), emitter);
        return emitter;
    }
}
