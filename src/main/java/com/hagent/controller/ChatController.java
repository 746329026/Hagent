package com.hagent.controller;

import com.hagent.app.ChatAppService;
import com.hagent.app.TaskIntentAppService;
import com.hagent.common.BaseResponse;
import com.hagent.dto.ChatRequest;
import com.hagent.dto.ChatResponse;
import com.hagent.dto.TaskIntent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    private final TaskIntentAppService taskIntentAppService;

    /**
     * 同步对话。
     */
    @PostMapping
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return BaseResponse.success(chatAppService.chat(request.message()));
    }

    /**
     * 任务意图理解 —— 结构化输出的落点。
     *
     * <p><b>返回的是强类型对象</b>（{@link TaskIntent}），不是一段文本。
     * 这样下游就能做<b>确定性判断</b>：该产出什么文件、要不要先问用户确认 ——
     * 而不必去解析模型返回的自然语言。
     *
     * <p>它也被统一响应包装成 {@code {"code":0,"data":{...intent...}}}。
     */
    @PostMapping("/intent")
    public BaseResponse<TaskIntent> intent(@Valid @RequestBody ChatRequest request) {
        return BaseResponse.success(taskIntentAppService.analyze(request.message()));
    }

    // ========================================================================
    // 流式对话 —— 三种实现方式并列，用于对比"手动驱动 vs 框架驱动"
    //
    // 三者的输入输出**完全一样**（都推 message / done / error 事件），
    // 差别只在"谁在驱动数据推送"与"能否携带 SSE 的进阶字段（id / retry）"。
    // 放在一起是为了让差异可被直接对比，而不是靠记忆。
    // ========================================================================

    /**
     * 【方式一】SseEmitter + 手动 send —— 最基础、最直白。
     *
     * <p><b>谁在驱动</b>：我们的代码。每推一个事件都要显式调 {@code emitter.send(...)}，
     * 结束要显式 {@code emitter.complete()}。
     *
     * <p><b>优点</b>：完全可控 —— 想什么时候推、推什么、什么时候结束，全在手里。
     * 多步 Agent 场景下要"在任意时刻插入一个 question 事件并暂停"，就靠这种可控性。
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
     * <p><b>为什么需要它</b>：浏览器的 {@code EventSource} <b>只能发起 GET 请求、且不能带请求体</b>。
     * 若只提供 POST 入口，浏览器端就无法用最原生的方式消费这个流。
     * 所以三个流式端点都同时提供 POST（供 curl / 前端 fetch 用）与 GET（供 EventSource 用）。
     *
     * <p>消息通过 query 参数传递 —— 这带来一个真实的限制：
     * <b>URL 有长度上限</b>，长消息（或将来要传的历史上下文）不适合走 GET。
     * 生产做法通常是：POST 建立会话拿到 sessionId，再用 GET + sessionId 订阅流。
     * 当前阶段消息短，先用 query 参数最直接。
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
     * <b>我们的代码里没有一处 send / complete</b> —— 这就是与方式一最直观的差别。
     *
     * <p><b>优点</b>：代码极简（一个表达式）；断连处理由框架兜底
     * （订阅被取消 → 上游 Flux 被取消 → 不会继续调模型烧 token）。
     *
     * <p><b>为什么用 {@code ServerSentEvent} 而不是裸 {@code Flux<String>}</b>：
     * {@code ServerSentEvent} 能携带 SSE 的进阶字段（{@code id} / {@code retry} / {@code comment}），
     * 而裸字符串只能带 data。这也是本方式相对方式一"在字段表达力上不吃亏"的原因。
     *
     * <p>另外：这个返回类型是 {@code Publisher}，会被 {@link com.hagent.stream.SseSupport}
     * 判定为"必须放行"，因此不会被统一响应包装破坏。
     */
    @GetMapping(value = "/stream-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamFlux(@RequestParam String message) {
        return chatAppService.streamAsFlux(message);
    }

    /**
     * 【方式三】SseEmitter + ServerSentEvent —— 手动驱动，但事件带进阶字段。
     *
     * <p><b>定位</b>：方式一和方式二的"并集"——
     * 保留手动驱动的可控性（适合将来做中断/续跑、多事件类型交织），
     * 同时把事件从"裸字符串"升级成 {@code ServerSentEvent}，从而能带
     * {@code id}（断线重连时前端可凭它续传）与 {@code retry}（告诉浏览器重连间隔）。
     *
     * <p>这个组合是后续阶段的**主力形态**：多步 Agent 需要"手动插入不同类型的事件"，
     * 又需要"事件带 id 以便前端去重/续传"。
     */
    @PostMapping(value = "/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSse(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.streamWithServerSentEvent(request.message(), emitter);
        return emitter;
    }

    /** 方式三的 GET 入口：供浏览器 EventSource 直接连接（EventSource 只能发 GET，无法带请求体）。 */
    @GetMapping(value = "/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSseGet(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(600_000L);
        chatAppService.streamWithServerSentEvent(message, emitter);
        return emitter;
    }
}
