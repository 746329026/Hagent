package com.hagent.controller;

import com.hagent.common.ConversationHeaders;

import com.hagent.app.ChatAppService;
import com.hagent.common.BaseResponse;
import com.hagent.common.ErrorCode;
import com.hagent.config.StreamProperties;
import com.hagent.dto.ChatRequest;
import com.hagent.dto.ChatResponse;
import com.hagent.exception.AgentException;
import com.hagent.memory.MemoryMode;
import com.hagent.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 对话接口 —— <b>只有两条入口</b>。
 *
 * <pre>
 *   POST /chat         阻塞：一次返回完整回复（含思考过程）
 *   POST /stream-sse   流式：SSE 逐事件推送
 *                      （step / reasoning / message / tool_call / tool_result / retrieved / done / error）
 *   GET  /stream-sse   同上，供浏览器 EventSource 使用（会话 ID 走 query，有泄露风险）
 * </pre>
 *
 * <p>会话的列表 / 历史回放 / 改名 / 删除<b>不碰模型</b>，放在 {@link ConversationController}。
 *
 * <p><b>★ 模式不是硬编码</b>：{@code ChatRequest.mode}（{@code chat} / {@code workflow}）
 * 决定本次的历史预算（5k vs 50k token），并随会话落库（{@code chat_conversation.mode}）——
 * <b>首次确定后不再由请求改变</b>，否则客户端可以每轮换一次模式，让预算来回跳且事后查不到原因。
 *
 * <p><b>★ 会话 ID</b>：前端可带可不带 → 不带则本层生成，作为参数交给 ChatAppService，
 * 再通过响应头 {@code X-Conversation-Id} 返回（前端下次带上）。
 * 用随机 UUID 而非自增序号，因为后者可遍历、会读到别人的会话（IDOR 越权）。
 * 走响应头而不是响应体，是因为响应体是业务数据契约（{@code BaseResponse}），
 * 会话 ID 属于调用元数据；代价是要在 {@code WebConfig} 的 {@code exposedHeaders} 中暴露它，
 * 否则前端读不到（且不报错）。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAppService chatAppService;
    private final StreamProperties streamProperties;

    /**
     * 同步对话（带会话记忆）。
     *
     * <p>会话 ID 通过注解 {@code @RequestHeader} 无法"可选地返回"，
     * 所以这里用 {@code HttpServletResponse} 直接写响应头 —— 比包装一层 ResponseEntity 更直白。
     */
    @PostMapping
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                           @RequestAttribute(name = UserContext.REQUEST_ATTRIBUTE) UserContext userContext,
                                           jakarta.servlet.http.HttpServletResponse servletResponse) {
        String conversationId = resolveConversationId(request.conversationId());
        servletResponse.setHeader(ConversationHeaders.ID, conversationId);
        return BaseResponse.success(chatAppService.chat(
                request.message(), conversationId, resolveMode(request.mode()), userContext));
    }

    /**
     * 解析请求体里的 {@code mode}。
     *
     * <p><b>★ 为什么要把 {@code IllegalArgumentException} 翻成 {@code AgentException}</b>：
     * {@code GlobalExceptionHandler} 里<b>没有</b> {@code IllegalArgumentException} 的处理器
     * （这是刻意的 —— 它太宽泛，接住它会把真正的编程错误也变成 400）。
     * 不翻的话，"客户端把 mode 打成 {@code wrokflow}"会落进兜底的 {@code Exception.class}
     * → <b>HTTP 500</b>，于是客户端会去重试一个永远不会成功的请求。
     *
     * <p>合法取值见 {@link MemoryMode#fromWire}：{@code chat} / {@code workflow}，大小写不敏感；
     * 不传则用 {@link MemoryMode#DEFAULT}。
     */
    private MemoryMode resolveMode(String rawMode) {
        try {
            return MemoryMode.fromWire(rawMode);
        } catch (IllegalArgumentException e) {
            throw new AgentException(ErrorCode.INVALID_PARAM, e.getMessage());
        }
    }

    /**
     * 生成或沿用会话 ID。
     *
     * <p>★ 服务端生成、随机 UUID —— 不走"前端自己造"，理由见类注释。
     */
    private String resolveConversationId(String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString();
    }

    // 流式对话 —— 只保留一条路径：SseEmitter + 完整事件字段（手动驱动）

    /**
     * 【唯一流式入口】SseEmitter + 带 {@code id} / {@code retry} 的完整事件。
     *
     * <p><b>推的事件</b>（见 {@code StreamEventType}）：{@code step}（第 N 步开始，先于任何
     * tool_call）、{@code reasoning} / {@code message}（思考与正文增量）、{@code tool_call} /
     * {@code tool_result}、{@code retrieved}、{@code done}（恰好一次）/
     * {@code error}（异常时一次，替代 done）。
     *
     * <p>⚠️ {@code SseEmitter} <b>不认识</b> {@code ServerSentEvent}（那是给 WebFlux /
     * 返回 Publisher 那条路用的），必须用 {@code SseEmitter.event()} 构建器。
     */
    @PostMapping(value = "/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSse(@Valid @RequestBody ChatRequest request,
                                @RequestAttribute(name = UserContext.REQUEST_ATTRIBUTE) UserContext userContext,
                                jakarta.servlet.http.HttpServletResponse servletResponse) {
        String conversationId = resolveConversationId(request.conversationId());
        // 会话 ID 用响应头返回，前端下次带上即可续用同一会话。
        // 注意必须已暴露在 WebConfig 的 exposedHeaders 里，否则浏览器读不到（且不报错）。
        servletResponse.setHeader(ConversationHeaders.ID, conversationId);
        SseEmitter emitter = new SseEmitter(streamProperties.getSseTimeoutMs());
        chatAppService.stream(request.message(), conversationId,
                resolveMode(request.mode()), userContext, emitter);
        return emitter;
    }

    /**
     * 流式入口的 GET 形式。
     *
     * <p><b>★ 这里有一个真实的限制</b>：浏览器 {@code EventSource} 只能发 GET、且不能带请求体，
     * 所以会话 ID 只能走 query 参数 —— 而 <b>URL 会被记进访问日志、浏览器历史、Referer</b>。
     * 会话 ID 一旦泄露，攻击者可读到该会话的全部历史（当前无鉴权时尤其危险）。
     *
     * <p>所以<b>需要会话历史的场景请用 POST 入口</b>。本 GET 入口保留是为了让 EventSource
     * 能用最原生的方式连上，定位是"演示与调试"。
     *
     * <p>生产做法通常是：POST 建立会话拿到 ID，再用 GET + ID 订阅流 ——
     * 但那时必须先有鉴权，否则会话 ID 本身就是唯一的凭证。
     */
    @GetMapping(value = "/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSseGet(@RequestParam String message,
                                   @RequestParam(required = false) String conversationId,
                                   @RequestParam(required = false) String mode,
                                   @RequestAttribute(name = UserContext.REQUEST_ATTRIBUTE) UserContext userContext,
                                   jakarta.servlet.http.HttpServletResponse servletResponse) {
        String resolved = resolveConversationId(conversationId);
        servletResponse.setHeader(ConversationHeaders.ID, resolved);
        SseEmitter emitter = new SseEmitter(streamProperties.getSseTimeoutMs());
        chatAppService.stream(message, resolved, resolveMode(mode), userContext, emitter);
        return emitter;
    }
}
