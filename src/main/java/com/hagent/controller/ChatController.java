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
 * <p><b>阶段 10 起还有三个只读接口</b>（会话列表 / 历史回放 / 改名），
 * 它们<b>不碰模型</b>，所以放在 {@link ConversationController} 而不是这里。
 *
 * <p><b>★ 阶段 10：模式不再是硬编码</b>。{@code ChatRequest.mode}（{@code chat} / {@code workflow}）
 * 决定本次的历史预算（5k vs 50k token），并会随会话落库
 * （{@code chat_conversation.mode}）—— <b>首次确定后不再由请求改变</b>。
 * 在此之前两处都写死 {@code MemoryMode.CHAT}，于是 workflow 预算虽然配了、
 * 整条通道也通了，却<b>没有任何真实调用能走到它</b>（"装好了但没通电的开关"）。
 *
 * <h2>★ 阶段 8 删除了两条流式端点（旧 {@code /stream} 与 {@code /stream-flux}）</h2>
 *
 * <p>它们曾是"流式机制对照实验"（手动驱动 vs 框架驱动）。删除的理由与
 * {@code /chat/intent} 那次相同：<b>代码不承载历史，文档承载。</b>
 * 对照实验的产出是"三种机制的差异"，那个产出已经在 {@code docs/PROCEDURES.md} 里，
 * 而保留两条没人维护的路径，后来人会以为它们是主链路的一环、又把它接回去。
 *
 * <h2>★ 会话 ID 的流转</h2>
 * <pre>
 *   前端请求（conversationId 可带可不带）
 *        ↓  Controller 生成（若是新会话）
 *   作为参数交给 ChatAppService → AgentPipeline
 *        ↓
 *   响应头 X-Conversation-Id 返回给前端（前端下次带上）
 * </pre>
 *
 * <p><b>为什么会话 ID 用随机 UUID</b>：
 * 若用自增序号，攻击者可遍历（1,2,3...）读到别人的会话（IDOR 越权）。
 * 随机 UUID 不可预测，配合后续的鉴权才能构成完整防护。
 *
 * <p><b>为什么用响应头而不是响应体返回</b>：
 * 响应体是业务数据（{@code BaseResponse} 的契约），会话 ID 属于"调用元数据"。
 * 放进响应体会破坏已有契约；响应头不改契约，语义也更贴切。
 * 代价是要在 {@code WebConfig} 的 {@code exposedHeaders} 中暴露它，否则前端读不到。
 *
 * <h2>⚠️ 已撤回：{@code POST /chat/intent}（任务意图理解）</h2>
 *
 * <p>本端点曾用于"先让模型解析出结构化任务意图，再由代码决定调哪些工具"。
 * <b>该设计已被整体撤回并删除</b>，理由见 {@code docs/PROCEDURES.md} 的"一个被撤回的坑"一节。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 会话 ID 的响应头名。集中定义，避免裸字符串散落。 */


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
     * 不翻的话，"客户端把 mode 打成 {@code wrokflow}"会落进兜底的
     * {@code Exception.class} → <b>HTTP 500 "服务内部错误"</b>，
     * 于是客户端会去重试一个永远不会成功的请求，运维也会以为服务挂了。
     *
     * <p>合法取值见 {@link MemoryMode#fromWire}：{@code chat} / {@code workflow}，
     * 大小写不敏感；不传则用 {@link MemoryMode#DEFAULT}。
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

    // ========================================================================
    // 流式对话 —— 只保留一条路径：SseEmitter + 完整事件字段（手动驱动）
    // ========================================================================

    /**
     * 【唯一流式入口】SseEmitter + 带 {@code id} / {@code retry} 的完整事件。
     *
     * <p><b>它推什么事件</b>（见 {@code StreamEventType}）：
     * <pre>
     *   step       → 第 N 步开始（阶段 10；工具调用的"骨架"，先于任何 tool_call）
     *   reasoning  → 模型的思考过程（DeepSeek 独有，可能整体缺失）
     *   message    → 回答正文逐片（可能多次）
     *   tool_call  → 模型请求调用工具（阶段 9）
     *   tool_result→ 工具返回（阶段 9）
     *   retrieved  → 知识库命中的片段（阶段 9）
     *   done       → 正常结束（恰好一次）
     *   error      → 出错（异常时一次，替代 done）
     * </pre>
     *
     * <p><b>★ 只有带记忆的路径返回会话 ID 响应头</b> —— 本路径与 {@code POST /chat} 都带记忆，
     * 所以两者都返回；没有"不带记忆"的路径需要区别对待了。
     *
     * <p>⚠️ 实现时踩过一个坑：{@code SseEmitter} <b>不认识</b> {@code ServerSentEvent}
     * （那是给 WebFlux / 返回 Publisher 那条路用的），必须用 {@code SseEmitter.event()} 构建器。
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
     * <p><b>★ 这里有一个真实的限制</b>：浏览器 {@code EventSource} 只能发 GET、
     * 且不能带请求体，所以会话 ID 只能走 query 参数。
     * 而 query 参数出现在 URL 里 —— <b>URL 会被记进访问日志、浏览器历史、Referer</b>。
     * 会话 ID 一旦泄露，攻击者可读到该会话的全部历史（当前无鉴权时尤其危险）。
     *
     * <p>所以<b>需要会话历史的场景请用 POST 入口</b>（{@code conversationId} 在请求体里，
     * 不进 URL）。本 GET 入口保留是为了让 EventSource 能用最原生的方式连上，
     * 定位是"演示与调试"。
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
