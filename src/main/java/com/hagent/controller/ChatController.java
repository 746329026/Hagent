package com.hagent.controller;

import com.hagent.app.ChatAppService;
import com.hagent.common.BaseResponse;
import com.hagent.dto.ChatRequest;
import com.hagent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 对话接口。
 *
 * <h2>★ 会话 ID 的流转（阶段 6 引入）</h2>
 * <pre>
 *   前端请求（conversationId 可带可不带）
 *        ↓  Controller 生成（若是新会话）
 *   放进请求 context → Advisor 取出来加载历史
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
 * <h2>★ 哪些端点带会话记忆（主动决定，非遗漏）</h2>
 * <pre>
 *   POST /chat          ✅ 带记忆  —— 产品路径
 *   POST /stream-sse    ✅ 带记忆  —— 后续多步 Agent 的主力形态
 *   GET  /stream-sse    ✅ 带记忆  —— 同上（会话 ID 走 query，风险见该方法注释）
 *   POST /stream        ❌ 不带    —— 流式机制对照实验（手动驱动）
 *   GET  /stream-flux   ❌ 不带    —— 流式机制对照实验（框架驱动）
 * </pre>
 * 三条流式路径中<b>只有方式三带记忆</b>：方式一/二的价值在于把两种流式机制摆在一起对比，
 * 加记忆只会增加噪声。理由详见 {@code ChatAppService#streamWithServerSentEvent}。
 *
 * <h2>⚠️ 已撤回：{@code POST /chat/intent}（任务意图理解）</h2>
 *
 * <p>本端点曾用于"先让模型解析出结构化任务意图，再由代码决定调哪些工具"。
 * <b>该设计已被整体撤回并删除</b>，理由见 {@code docs/PROCEDURES.md} 的"一个被撤回的坑"一节。
 *
 * <p><b>为什么不保留它"作为调试工具"</b>：
 * 删一半留一半会产生"看起来还在用"的错觉 —— 后来人会以为它是主链路的一环，
 * 又把它接回去。要留痕，留在文档里比留在代码里安全。<b>代码不承载历史，文档承载。</b>
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 会话 ID 的响应头名。集中定义，避免裸字符串散落。 */
    public static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";

    private final ChatAppService chatAppService;

    /**
     * 同步对话（带会话记忆）。
     *
     * <p>会话 ID 通过注解 {@code @RequestHeader} 无法"可选地返回"，
     * 所以这里用 {@code HttpServletResponse} 直接写响应头 —— 比包装一层 ResponseEntity 更直白。
     */
    @PostMapping
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                           jakarta.servlet.http.HttpServletResponse servletResponse) {
        String conversationId = resolveConversationId(request.conversationId());
        servletResponse.setHeader(CONVERSATION_ID_HEADER, conversationId);
        return BaseResponse.success(chatAppService.chat(request.message(), conversationId));
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
    // 流式对话 —— 三种实现方式并列，用于对比"手动驱动 vs 框架驱动"
    //
    // 三者的输入输出完全一样（都推 message / done / error 事件），
    // 差别只在"谁在驱动数据推送"与"能否携带 SSE 的进阶字段（id / retry）"。
    // ⚠️ 会话记忆只接入了方式三（/stream-sse）——
    //    方式一/二是"流式机制对照实验"，刻意不带记忆，理由见
    //    ChatAppService#streamWithServerSentEvent 的类注释。
    // ========================================================================

    /**
     * 【方式一】SseEmitter + 手动 send —— 最基础、最直白。
     *
     * <p><b>谁在驱动</b>：我们的代码。每推一个事件都要显式调 {@code emitter.send(...)}，
     * 结束要显式 {@code emitter.complete()}。
     *
     * <p><b>优点</b>：完全可控 —— 多步 Agent 场景下要"在任意时刻插入一个 question 事件并暂停"，
     * 就靠这种可控性。
     *
     * <p><b>缺点</b>：代码量大；线程与生命周期要自己管（超时、断连、关闭）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.stream(request.message(), emitter);
        return emitter;
    }

    /**
     * 方式一的 GET 入口。
     *
     * <p><b>为什么需要它</b>：浏览器的 {@code EventSource} <b>只能发起 GET、且不能带请求体</b>。
     * 若只提供 POST 入口，浏览器端就无法用最原生的方式消费这个流。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGet(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.stream(message, emitter);
        return emitter;
    }

    /**
     * 【方式二】直接返回 Flux&lt;ServerSentEvent&lt;String&gt;&gt; —— 框架驱动。
     *
     * <p><b>谁在驱动</b>：Spring。它把返回的 {@code Flux} 订阅起来，
     * 每来一个元素就自动写成一个 SSE 事件，流结束时自动关闭连接。
     * <b>我们的代码里没有一处 send / complete</b>。
     *
     * <p><b>为什么用 {@code ServerSentEvent} 而不是裸 {@code Flux<String>}</b>：
     * 它能携带 SSE 的进阶字段（{@code id} / {@code retry}），而裸字符串只能带 data。
     *
     * <p>这个返回类型是 {@code Publisher}，会被 {@link com.hagent.stream.SseSupport}
     * 判定为"必须放行"，因此不会被统一响应包装破坏。
     */
    @GetMapping(value = "/stream-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamFlux(@RequestParam String message) {
        return chatAppService.streamAsFlux(message);
    }

    /**
     * 【方式三】SseEmitter + 带 {@code id} / {@code retry} 的完整事件 —— 字段最全的手动驱动。
     *
     * <p>这是后续多步 Agent 的<b>主力形态</b>：既要手动插入不同类型的事件
     * （message / step / question / done），又要事件带 id 便于前端去重与续传。
     *
     * <p>⚠️ 实现时踩过一个坑：{@code SseEmitter} <b>不认识</b> {@code ServerSentEvent}
     * （那是给 WebFlux/Publisher 那条路用的），必须用 {@code SseEmitter.event()} 构建器。
     * 详见 {@code ChatAppService#streamWithServerSentEvent} 的注释。
     *
     * <p><b>★ 三条流式路径里，只有本路径返回会话 ID</b>：
     * 只有它带记忆（理由见 {@code ChatAppService#streamWithServerSentEvent}）。
     * 方式一/二不返回该响应头，因为它们的每条流都是独立的、不产生会话历史 ——
     * 返回一个"下次带上就能续用"的 ID 反而是误导。
     */
    @PostMapping(value = "/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSse(@Valid @RequestBody ChatRequest request,
                                jakarta.servlet.http.HttpServletResponse servletResponse) {
        String conversationId = resolveConversationId(request.conversationId());
        // 与同步路径一致：会话 ID 用响应头返回，前端下次带上即可续用同一会话。
        // 注意必须已暴露在 WebConfig 的 exposedHeaders 里，否则浏览器读不到（且不报错）。
        servletResponse.setHeader(CONVERSATION_ID_HEADER, conversationId);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.streamWithServerSentEvent(request.message(), conversationId, emitter);
        return emitter;
    }

    /**
     * 方式三的 GET 入口。
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
                                   jakarta.servlet.http.HttpServletResponse servletResponse) {
        String resolved = resolveConversationId(conversationId);
        servletResponse.setHeader(CONVERSATION_ID_HEADER, resolved);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.streamWithServerSentEvent(message, resolved, emitter);
        return emitter;
    }
}
