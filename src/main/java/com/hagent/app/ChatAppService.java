package com.hagent.app;

import com.hagent.config.StreamProperties;
import com.hagent.dto.ChatResponse;
import com.hagent.memory.MemoryMode;
import com.hagent.security.UserContext;
import com.hagent.stream.StreamEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话应用服务 —— app 层，<b>对外只有两条入口</b>。
 *
 * <pre>
 *   {@link #chat}    阻塞：/chat          —— 拿完整结果（带思考）
 *   {@link #stream}  流式：/stream-sse     —— 逐事件推送（SSE）
 * </pre>
 *
 * <p><b>两条入口消费的是同一份事件流</b>（{@code AgentPipeline.run(...)}）——
 * 这是把管道做成"一条事件流"而不是两个方法的直接收益：
 * 读历史 / 检索 / 拼装 / 累积<b>只有一份实现</b>，不会各自演化出差异。
 *
 * <h2>★ 阶段 8 移除了什么（以及为什么不再需要）</h2>
 *
 * <p>原先有三条流式路径，用于对比三种流式机制：
 * <pre>
 *   方式一 /stream        手动驱动（我们 send / 我们 complete）
 *   方式二 /stream-flux   框架驱动（返回 Flux，Spring 自己推）
 *   方式三 /stream-sse    手动驱动 + 完整事件字段（id / retry）  ← 保留
 * </pre>
 * 方式一/二的定位是"<b>机制对照实验</b>"，而阶段 8 起只有一条产品路径需要维护。
 * 保留对照实验的代价是：三条路径各自一套错误处理与生命周期管理，
 * 而它们的差异早已被记录进文档（那才是对照实验的真正产出）。
 * <b>代码不承载历史，文档承载。</b>
 *
 * <p>随之删除的还有 {@code streamFailureCount}：它的注释写着"因为流式出错时我们用
 * {@code complete()} 正常收尾，框架会把失败当成正常结束，所以失败次数要自己记"——
 * 那是方式二独有的问题。现在错误通过事件流的 {@code onError} 统一处理，不需要这个计数器。
 *
 * <h2>★ 错误为什么不能在流内"静默收尾"</h2>
 *
 * <p>{@code SseEmitter} 的错误处理有一条真实的坑：不能用
 * {@code emitter.completeWithError(error)} —— 它会把异常抛给 Spring 的异步处理链路，
 * 最终落到全局异常处理器，而处理器想返回 JSON，可响应的 Content-Type 早已被锁成
 * {@code text/event-stream}，于是报
 * {@code HttpMessageNotWritableException: No converter for [BaseResponse]}。
 *
 * <p>所以本类用三路并行把它补回来（见 {@link #handleStreamError}）：
 * 推 error 事件给前端 + 记完整堆栈给运维 + {@code complete()} 给框架。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    /** 模型调用的编排者 —— 阶段 8 起不再注入 {@code ChatClient}（管道直连 {@code ChatModel}）。 */
    private final AgentPipeline agentPipeline;

    /** 流式相关参数（超时 / 重连间隔 / 心跳）。 */
    private final StreamProperties streamProperties;

    // ========================================================================
    // 入口一：阻塞式
    // ========================================================================

    /**
     * 同步对话：把用户消息发给模型，返回<b>完整回复 + 思考过程</b>。
     *
     * <p><b>★ 阶段 8 起带思考</b>：流式路径可以把思考逐片推给前端，
     * 但<b>刷新页面后思考就永久丢了</b> —— 会话记忆只存"问 + 最终答"（决策 32）。
     * 阻塞路径带上它，前端回看历史时才能显示思考区。
     *
     * <h3>★ 三个"请求级参数"怎么走下去</h3>
     *
     * <p>会话 ID、记忆模式、身份<b>都是 {@code AgentPipeline.run(...)} 的方法参数</b>，
     * 不再是塞进 {@code ChatClientRequest.context} 的字符串 key ——
     * 那套 key 约定随切面链一起删除（阶段 8）：
     *
     * <ul>
     *   <li><b>模式</b>由本层显式声明：这里固定传 {@link MemoryMode#CONVERSATION}。
     *       只有 app 层知道"本次调用属于哪种场景"，管线拿不到这个信息；
     *       若让它去猜，猜错的后果是<b>静默地少给或多给 10 倍预算</b>。
     *       阶段 9 的工作流引擎在这里改传 {@link MemoryMode#WORKFLOW} 即可，管线无需改动；</li>
     *   <li><b>身份</b>（{@code userContext}）的来源是 HTTP 层的
     *       {@code UserContextInterceptor}，由 Controller 用 {@code @RequestAttribute}
     *       取出后<b>作为参数传到这里</b>。
     *       用方法参数的好处是<b>编译期检查</b>：谁改了签名，所有调用点立刻编译失败
     *       —— 而旧实现在切面里按字符串 key 读 context，拼错不报错。
     *       它最终的消费者是检索：由检索把许可等级翻成 SQL 条件，
     *       让无权内容留在数据库里；</li>
     *   <li><b>会话 ID</b> 仍然<b>不进 prompt</b>：它是<b>系统元数据</b>，模型不需要它；
     *       而 prompt 文本会发给模型提供方，会话 ID 没有必要跟着走出去。
     *       它只作为参数交给管线，用来读写会话记忆。</li>
     * </ul>
     *
     * <p><b>为什么复用 {@code sse-timeout-ms} 作为阻塞上限</b>：两者表达的是同一件事
     * ——"愿意为一次模型回答等待多久"。新增一个没被验证过的配置键属于画蛇添足。
     *
     * @param message        已经过参数校验的用户消息（非空）
     * @param conversationId 会话标识（由 Controller 生成或透传；空则落到管线的兜底值）
     * @param userContext    调用方身份（未认证时为 {@link UserContext#ANONYMOUS}）
     * @return 模型的完整回复：正文 + 思考过程
     */
    public ChatResponse chat(String message, String conversationId, UserContext userContext) {
        // 流是冷的：collectList 订阅时才真正发出请求。逻辑上最后一个事件必然是 Final。
        List<PipelineEvent> events = agentPipeline
                .run(message, conversationId, MemoryMode.CONVERSATION, userContext)
                .collectList()
                .block(Duration.ofMillis(streamProperties.getSseTimeoutMs()));

        PipelineEvent.ModelOutput.Final last = finalOf(events);
        if (last == null) {
            // 走到这里说明 pipeline 的行为与契约不符（它一定产出 Final）。
            // 明确抛异常而不是返回空串 —— 否则前端看到的是"模型没回答"，
            // 而真正的原因（契约被破坏）被静默吞掉。
            throw new IllegalStateException(
                    "AgentPipeline 未产出 Final 事件（事件数="
                            + (events == null ? "null" : String.valueOf(events.size()))
                            + "）。这是管道契约被破坏，【不是】模型的问题。");
        }
        return new ChatResponse(last.text(), last.reasoning());
    }

    /**
     * 从事件流里取出最后一个 {@link PipelineEvent.ModelOutput.Final}。
     *
     * <p>用"取最后一个"而不是"取第一个"：目前一条流只可能有一个，但阶段 9 的多步循环
     * 可能产出多个 Final（每步一个）—— 那时"最后一个"才是本次的最终答复。
     */
    private PipelineEvent.ModelOutput.Final finalOf(List<PipelineEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        PipelineEvent.ModelOutput.Final found = null;
        for (PipelineEvent event : events) {
            if (event instanceof PipelineEvent.ModelOutput.Final f) {
                found = f;
            }
        }
        return found;
    }

    // ========================================================================
    // 入口二：流式（SSE）
    // ========================================================================

    /**
     * 流式对话：把事件流翻译成 SSE 帧推给前端。
     *
     * <p><b>模式由调用方传入</b>：{@code mode} 与 {@code conversationId} / {@code userContext}
     * 一样，是 {@code AgentPipeline.run(...)} 的<b>方法参数</b> —— 谁调用谁声明本次属于哪种场景
     * （Controller 目前两处都传 {@link MemoryMode#CONVERSATION}）。
     * 旧实现把它塞进 {@code ChatClientRequest.context} 的字符串 key 里由切面去取 ——
     * key 与值都是字符串，写错不报错；现在它是枚举实参，模式名写错编译不过。
     *
     * <p><b>事件映射</b>（{@link PipelineEvent} → {@link StreamEventType}）：
     * <pre>
     *   ModelOutput.Reasoning(text)      → reasoning 帧     ← 思考区
     *   ModelOutput.Token(text)          → message   帧     ← 回答区
     *   ModelOutput.ToolCall(id,name,args)→ tool_call 帧     ← ★ 阶段 9：正在调用工具
     *   ModelOutput.ToolResult(id,name,r)→ tool_result 帧   ← ★ 阶段 9：工具结果
     *   Retrieved(chunks, query)         → retrieved 帧     ← ★ 阶段 9：参考了哪几篇
     *   ModelOutput.Final                → done      帧     ← 结束
     * </pre>
     *
     * <p><b>★ 阶段 9 起 {@code Retrieved} 也外发了</b>（此前刻意不外发，
     * 理由是"前端还没有消费它的页面"）。现在它由检索工具发出，
     * 而"展示参考了哪几篇"正是本阶段要做出来的能力 —— 消费者出现了，接线就该接上。
     *
     * <p>三条新事件的数据都经过<b>显式的 {@code Map} 收窄</b>，而不是直接序列化领域对象：
     * 直接暴露 record / 领域类会让"内部字段改名"意外变成"前端契约破坏"，
     * 而这里只想给前端它真正需要的那几项（见 {@code toolCallData} 等方法的注释）。
     *
     * <p><b>为什么 {@code id} / {@code retry} 要带</b>：浏览器断线重连时会自动带上
     * {@code Last-Event-ID} 请求头，服务端可据此判断续传位置；{@code retry} 则告诉浏览器
     * 断开后隔多久重连（只在下发一次即可，浏览器会记住）。
     *
     * <p><b>★ 必须用 {@code SseEmitter.event()} 构建器</b>：{@code SseEmitter}
     * <b>不认识</b> {@code ServerSentEvent}（那是给 WebFlux / 返回 Publisher 那条路用的），
     * 交给它会被当成"要 JSON 序列化的普通对象"，结果是<b>一个事件都发不出去</b>，
     * 而服务端日志干干净净。
     */
    public void stream(String message,
                       String conversationId,
                       MemoryMode mode,
                       UserContext userContext,
                       SseEmitter emitter) {
        AtomicLong seq = new AtomicLong();
        AtomicBoolean retrySent = new AtomicBoolean(false);

        agentPipeline.run(message, conversationId, mode, userContext)
                .subscribe(event -> sendEvent(emitter, event, seq, retrySent),
                        error -> handleStreamError(emitter, error),
                        () -> {
                            sendNamedEvent(emitter,
                                    SseEmitter.event()
                                            .id(String.valueOf(seq.incrementAndGet()))
                                            .name(StreamEventType.DONE)
                                            .data("[DONE]"));
                            completeQuietly(emitter);
                        });
        // subscribe() 非阻塞：它会立刻返回，Flux 在 Reactor 线程上异步推进。
    }

    /** 把一个管道事件翻译成 SSE 帧并发送。 */
    private void sendEvent(SseEmitter emitter,
                           PipelineEvent event,
                           AtomicLong seq,
                           AtomicBoolean retrySent) {
        if (event instanceof PipelineEvent.ModelOutput.Reasoning reasoning) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.REASONING, reasoning.text()));
            return;
        }
        if (event instanceof PipelineEvent.ModelOutput.Token token) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.MESSAGE, token.text()));
            return;
        }
        // ★ 阶段 9：工具调用与结果外发 —— 这是"用户能看到模型在做什么"的关键。
        //   两者共用同一个 id，前端据此把一次调用渲染成一行（开始 → 结果）。
        if (event instanceof PipelineEvent.ModelOutput.ToolCall call) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.TOOL_CALL, toolCallData(call)));
            return;
        }
        if (event instanceof PipelineEvent.ModelOutput.ToolResult result) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.TOOL_RESULT, toolResultData(result)));
            return;
        }
        // ★ 检索命中片段外发 —— 前端据此显示"参考了哪几篇"。
        //   它是结构化数据（来源 / 相关度都在里面），不需要前端从工具文本里解析。
        if (event instanceof PipelineEvent.Retrieved retrieved) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.RETRIEVED, retrievedData(retrieved)));
            return;
        }
        // Final 不在这里发：它由流的 onComplete 统一发 done（见 stream 方法）。
    }

    /**
     * 工具调用 → SSE 数据。
     *
     * <p>用一个显式的小 {@code Map} 而不是直接序列化 record：
     * record 的字段名会成为前端的契约，而这里只想暴露<b>前端真正需要的三个</b>
     * （{@code id} / {@code name} / {@code arguments}）——
     * 直接暴露模型/领域对象会让"内部字段改名"意外变成"前端契约破坏"。
     */
    private java.util.Map<String, Object> toolCallData(PipelineEvent.ModelOutput.ToolCall call) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", call.id());
        data.put("name", call.name());
        data.put("arguments", call.arguments());
        return data;
    }

    private java.util.Map<String, Object> toolResultData(PipelineEvent.ModelOutput.ToolResult result) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", result.id());
        data.put("name", result.name());
        data.put("result", result.result());
        return data;
    }

    private java.util.Map<String, Object> retrievedData(PipelineEvent.Retrieved retrieved) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("query", retrieved.query());
        data.put("sources", retrieved.chunks().stream()
                // ★ 只外发"用户能看懂"的两项：出处与相关度档位。
                //   不吐 content（可能很长，且前端这一屏只需要"参考了哪些"）；
                //   不吐 level / similarity（密级与原始分数属于内部实现细节）。
                //   ⚠️ displayName() 是方法不是属性，Jackson 不会序列化它 ——
                //      所以这里显式调用，避免前端拿到 "HIGH" 而无法显示中文档位。
                .map(c -> java.util.Map.of(
                        "source", c.displaySource(),
                        "band", c.band().displayName()))
                .toList());
        return data;
    }

    /**
     * 组装一个 SSE 事件；{@code retry} 只在第一个事件上下发一次
     * （它只下达一次即可，浏览器会记住该值）。
     */
    private SseEmitter.SseEventBuilder frame(SseEmitter emitter,
                                             AtomicLong seq,
                                             AtomicBoolean retrySent,
                                             String name,
                                             Object data) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(String.valueOf(seq.incrementAndGet()))
                .name(name)
                .data(data);
        if (retrySent.compareAndSet(false, true)) {
            builder.reconnectTime(streamProperties.getReconnectDelayMs());
        }
        return builder;
    }

    // ========================================================================
    // 发送与错误处理
    // ========================================================================

    /** 发送一个由 {@code SseEmitter.event()} 构建器组装好的事件（手动推送路径上唯一正确的发送方式）。 */
    private void sendNamedEvent(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (IOException e) {
            // 客户端断开（关页面/关标签）：用户正常操作，不是故障
            log.debug("[流式对话] 发送 SSE 事件时客户端已断开：{}", e.getMessage());
        } catch (Exception e) {
            // 非 IO 异常：多半是事件构造/序列化的问题，必须可见
            log.warn("[流式对话] 发送 SSE 事件失败（事件构造或序列化问题）：{}", e.toString());
        }
    }

    /**
     * 流式出错的统一处理 —— <b>三路并行，缺一不可</b>。
     *
     * <ol>
     *   <li><b>前端</b>：推 {@code error} 事件 —— 用户能看到失败；</li>
     *   <li><b>服务端</b>：{@code log.error} 记录完整堆栈 —— 弥补被丢弃的根因
     *       （若只推事件而不记日志，而事件推送恰好也失败——比如客户端断开——
     *        错误就彻底消失，谁都不知道）；</li>
     *   <li><b>框架</b>：{@code complete()} 正常收尾 —— 避免异常逃逸触发
     *       "No converter for [BaseResponse]" 那个坑（见类注释）。</li>
     * </ol>
     */
    private void handleStreamError(SseEmitter emitter, Throwable error) {
        // 第 1 路：前端 —— 推 error 事件（放在最前，保证用户先收到反馈）
        sendNamedEvent(emitter, SseEmitter.event()
                .name(StreamEventType.ERROR)
                .data(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));

        // 第 2 路：服务端 —— 记录完整根因（含堆栈），这是 complete() 方案必须补上的一环
        log.error("[流式对话] 失败：{}", error.getMessage(), error);

        // 第 3 路：框架 —— 正常关闭，不抛异常
        completeQuietly(emitter);
    }

    /**
     * 安静地关闭流。
     *
     * <p><b>为什么必须包 try-catch</b>：{@code complete()} 在"响应已提交"或
     * "连接已断开"时会抛 {@link IllegalStateException}。若让它逃逸，异常又会被
     * Spring 交给全局异常处理器，于是<b>再次触发 "No converter" 问题</b>——
     * 我们刚绕开的坑会绕回来。
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[流式对话] 关闭流时异常（通常是客户端已断开）：{}", e.getMessage());
        }
    }
}
