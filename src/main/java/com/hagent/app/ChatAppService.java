package com.hagent.app;

import com.hagent.agent.PipelineEvent;

import com.hagent.config.StreamProperties;
import com.hagent.dto.ChatResponse;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.catalog.SessionStore;
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
 * 对话应用服务 —— app 层，<b>对外只有两条入口</b>：{@link #chat}（阻塞 /chat，拿完整结果）与
 * {@link #stream}（流式 /stream-sse，逐事件推送）。两条入口消费<b>同一份事件流</b>
 * （{@code AgentPipeline.run(...)}），读历史 / 拼装 / 累积只有一份实现。
 *
 * <h2>★ 错误为什么不能在流内"静默收尾"</h2>
 *
 * <p>{@code SseEmitter} 有一条真实的坑：不能用 {@code emitter.completeWithError(error)} —— 它会把
 * 异常抛给 Spring 的异步处理链路，最终落到全局异常处理器，而处理器想返回 JSON，可响应的 Content-Type
 * 早已锁成 {@code text/event-stream}，于是报
 * {@code HttpMessageNotWritableException: No converter for [BaseResponse]}。所以本类用三路并行把它
 * 补回来（见 {@link #handleStreamError}）：推 error 事件给前端 + 记完整堆栈给运维 + {@code complete()}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    /** 模型调用的编排者 —— 不注入 {@code ChatClient}：管道直连 {@code ChatModel}。 */
    private final AgentPipeline agentPipeline;

    /** 流式相关参数（超时 / 重连间隔 / 心跳）。 */
    private final StreamProperties streamProperties;

    /**
     * 会话目录 —— <b>不是"聊天链路"的一部分，是"这次请求到达"的记录</b>。
     *
     * <p>放在这一层而不是 {@code AgentPipeline}：它记的是 {@code (会话ID, 模式, 用户消息)}，而这三样
     * 在<b>入口处</b>就齐了；管道要到"写回记忆"那一刻才知道它们，那时模型已经答完 —— 会话要等答完
     * 才出现在侧栏，用户发出第一句话后会看到侧栏空着十几秒。★ 它失败不能影响对话：见 {@link #touchSession}。
     */
    private final SessionStore sessionStore;

    // ---- 入口一：阻塞式 ----

    /**
     * 同步对话：把用户消息发给模型，返回<b>完整回复 + 思考过程</b>。
     *
     * <p><b>★ 阻塞路径也要带思考</b>：流式路径可以把思考逐片推给前端，但<b>刷新页面后思考就永久丢了</b>
     * （会话记忆只存"问 + 最终答"）。带上它，前端回看历史时才能显示思考区。
     *
     * <p><b>★ 三个"请求级参数"怎么走下去</b>：会话 ID、记忆模式、身份<b>都是
     * {@code AgentPipeline.run(...)} 的方法参数</b>，不再是塞进 {@code ChatClientRequest.context} 的
     * 字符串 key（那套 key 拼错不报错，运行时只表现为"注入为空"）。<b>模式</b>由调用方声明：只有
     * HTTP 层知道这次属于哪种场景，管线猜错的后果是<b>静默地少给或多给 10 倍预算</b>；它来自请求体
     * 并落进会话目录，同一会话的模式<b>首次确定后不再改变</b>。<b>身份</b>经 Controller 作为参数传进来
     * （编译期检查），最终消费者是检索 —— 把许可等级翻成 SQL 条件，让无权内容留在数据库里。
     * <b>会话 ID 不进 prompt</b>：它是系统元数据，而 prompt 会发给模型提供方，没有必要跟着走出去。
     *
     * <p><b>为什么复用 {@code sse-timeout-ms} 作为阻塞上限</b>：两者表达的是同一件事 ——"愿意为一次
     * 模型回答等待多久"；新增一个没被验证过的配置键属于画蛇添足。
     *
     * @param message        已经过参数校验的用户消息（非空）
     * @param conversationId 会话标识（由 Controller 生成或透传；空则落到管线的兜底值）
     * @param mode           记忆模式（来自请求体）
     * @param userContext    调用方身份（未认证时为 {@link UserContext#ANONYMOUS}）
     * @return 模型的完整回复：正文 + 思考过程
     */
    public ChatResponse chat(String message, String conversationId,
                             MemoryMode mode, UserContext userContext) {
        touchSession(conversationId, mode, message);
        // 流是冷的：collectList 订阅时才真正发出请求。逻辑上最后一个事件必然是 Final。
        List<PipelineEvent> events = agentPipeline
                .run(message, conversationId, mode, userContext)
                .collectList()
                .block(Duration.ofMillis(streamProperties.getSseTimeoutMs()));

        PipelineEvent.ModelOutput.Final last = finalOf(events);
        if (last == null) {
            // 走到这里说明 pipeline 的行为与契约不符（它一定产出 Final）。明确抛异常而不是返回
            // 空串 —— 否则前端看到的是"模型没回答"，而真正的原因（契约被破坏）被静默吞掉。
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
     * <p>取"最后一个"而不是"第一个"：工作流的多步循环可能产出多个 Final，最后一个才是最终答复。
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

    // ---- 入口二：流式（SSE） ----

    /**
     * 流式对话：把事件流翻译成 SSE 帧推给前端。
     *
     * <p><b>模式由调用方传入</b>：它与 {@code conversationId} / {@code userContext} 一样是
     * {@code AgentPipeline.run(...)} 的<b>方法参数</b> —— 枚举实参，模式名写错编译不过。
     *
     * <p><b>事件映射</b>（{@link PipelineEvent} → {@link StreamEventType}）：
     * <pre>
     *   StepStarted → step       帧        Token / Reasoning → message / reasoning 帧
     *   ToolCall / ToolResult              → tool_call / tool_result 帧（明细，共用同一个 id）
     *   Retrieved                          → retrieved 帧（参考了哪几篇）
     *   PlanCreated / PlanSkipped / StepFinished → workflow 帧（编排骨架）
     *   TurnFinished → stats      帧        Final → done 帧
     * </pre>
     *
     * <p>明细事件的数据都经过<b>显式的 {@code Map} 收窄</b>，而不是直接序列化领域对象：直接暴露
     * record 会让"内部字段改名"意外变成"前端契约破坏"（见 {@code toolCallData} 等的注释）。
     * {@code id} / {@code retry} 则是为断线重连带的：浏览器会带上 {@code Last-Event-ID} 请求头，
     * 服务端据此判断续传位置，{@code retry} 告诉浏览器断开后隔多久重连（下发一次即可）。
     *
     * <p><b>★ 必须用 {@code SseEmitter.event()} 构建器</b>：{@code SseEmitter} <b>不认识</b>
     * {@code ServerSentEvent}（那是给 WebFlux / 返回 Publisher 那条路用的），交给它会被当成"要 JSON
     * 序列化的普通对象"，结果是<b>一个事件都发不出去</b>，而服务端日志干干净净。
     */
    public void stream(String message,
                       String conversationId,
                       MemoryMode mode,
                       UserContext userContext,
                       SseEmitter emitter) {
        AtomicLong seq = new AtomicLong();
        AtomicBoolean retrySent = new AtomicBoolean(false);

        // 会话先落库（侧栏立刻能看到），再开始跑模型。理由见 touchSession。
        touchSession(conversationId, mode, message);

        // ★ 客户端断开必须【停止流水线】：否则 emitter 已完成之后流水线仍在跑、仍在发事件 ——
        //   实测表现为成百上千条 "ResponseBodyEmitter has already completed" 的 WARN，
        //   且继续白烧模型调用。（Reactor 只在订阅方取消时才传播取消，而这里没人取消。）
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> subscription =
                new java.util.concurrent.atomic.AtomicReference<>();
        emitter.onCompletion(() -> disposeQuietly(subscription.get()));
        emitter.onTimeout(() -> {
            disposeQuietly(subscription.get());
            completeQuietly(emitter);
        });
        emitter.onError(error -> disposeQuietly(subscription.get()));

        subscription.set(agentPipeline.run(message, conversationId, mode, userContext)
                .subscribe(event -> sendEvent(emitter, event, seq, retrySent),
                        error -> handleStreamError(emitter, error),
                        () -> {
                            sendNamedEvent(emitter,
                                    SseEmitter.event()
                                            .id(String.valueOf(seq.incrementAndGet()))
                                            .name(StreamEventType.DONE)
                                            .data("[DONE]"));
                            completeQuietly(emitter);
                        }));
        // subscribe() 非阻塞：它会立刻返回，Flux 在 Reactor 线程上异步推进。
    }

    /** 取消订阅（幂等：已结束时是空操作）。 */
    private void disposeQuietly(reactor.core.Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /** 把一个管道事件翻译成 SSE 帧并发送。 */
    private void sendEvent(SseEmitter emitter,
                           PipelineEvent event,
                           AtomicLong seq,
                           AtomicBoolean retrySent) {
        // ★ 第 N 步开始 —— 工具调用的"骨架"：模型决定调工具之前，一条 tool_call 都不会有，
        //   所以这是"思考中"这个状态唯一能出现的时机。
        if (event instanceof PipelineEvent.StepStarted step) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.STEP, stepData(step)));
            return;
        }
        if (event instanceof PipelineEvent.ModelOutput.Reasoning reasoning) {
            // ★ 文本载荷是 {"text":"…"} 而不是裸字符串，理由见 textData
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.REASONING, textData(reasoning.text())));
            return;
        }
        if (event instanceof PipelineEvent.ModelOutput.Token token) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.MESSAGE, textData(token.text())));
            return;
        }
        // ★ 工具调用与结果外发 —— 这是"用户能看到模型在做什么"的关键。
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
        // ★ 检索命中片段外发 —— 前端据此显示"参考了哪几篇"（结构化数据，不必解析工具文本）。
        // ★ 工作流的编排骨架（计划 / 规划降级 / 步结束）：用独立事件名，这样前端"不渲染计划面板"时
        //   不需要写任何分支（忽略未知事件名即可）。
        if (event instanceof PipelineEvent.PlanCreated created) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.WORKFLOW, planData(created)));
            return;
        }
        if (event instanceof PipelineEvent.PlanSkipped skipped) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent, StreamEventType.WORKFLOW,
                    java.util.Map.of("type", "plan_skipped", "reason", skipped.reason())));
            return;
        }
        if (event instanceof PipelineEvent.StepFinished finished) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent, StreamEventType.WORKFLOW,
                    java.util.Map.of(
                            "type", "step_finished",
                            "step", finished.step(),
                            "ok", finished.ok(),
                            "summary", finished.summary())));
            return;
        }
        if (event instanceof PipelineEvent.Retrieved retrieved) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.RETRIEVED, retrievedData(retrieved)));
            return;
        }
        // ★ 本轮统计（耗时 / 次数 / token / 结局）。它在 Final 之后、done 之前到达。
        //   失败与取消的轮次【不会】有这个事件（流已经断了），但那两种轮次照样落库 ——
        //   所以"库里比前端看到的多"是预期的。
        if (event instanceof PipelineEvent.TurnFinished finished) {
            sendNamedEvent(emitter, frame(emitter, seq, retrySent,
                    StreamEventType.STATS, statsData(finished.record())));
            return;
        }
        // Final 不在这里发：它由流的 onComplete 统一发 done（见 stream 方法）。
    }

    /**
     * 本轮统计 → SSE 数据。
     *
     * <p><b>用 {@link com.hagent.dto.SessionDtos.TurnStatsView} 而不是临时 Map</b>：这一行字有
     * <b>两个来源</b>（实时本方法、历史回放 {@code ConversationService#detail}），两边用同一个
     * record，前端就只有一份渲染逻辑。<b>身份绝不进帧</b>：{@code user_id} / {@code role} 只用于落库。
     *
     * <p>⚠️ {@code firstTokenMs} 与 {@code totalTokens} 可能是 <b>null</b>（没等到首字 / 没拿到
     * usage）。<b>不放 0</b>：{@code null} 表示"没有数据"，而 0 会被显示成"0.0s / 0 tokens"。
     */
    private com.hagent.dto.SessionDtos.TurnStatsView statsData(
            com.hagent.observability.TurnRecord record) {
        return new com.hagent.dto.SessionDtos.TurnStatsView(
                record.status().name(),
                record.elapsedMs(),
                record.firstTokenMs(),
                record.modelCalls(),
                record.toolCalls(),
                record.totalTokens(),
                record.usageMissingCalls());
    }

    /**
     * 「第 N 步开始」→ SSE 数据：{@code {"step":2}}。
     *
     * <p>只有一个字段，仍然走 {@code Map}：与其它明细事件同一做法（见 {@link #toolCallData}）。
     */
    private java.util.Map<String, Object> stepData(PipelineEvent.StepStarted step) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("step", step.step());
        return data;
    }

    /**
     * 「计划」→ SSE 数据：{@code {"type":"plan","goal":"…","steps":[…]}}。
     *
     * <p>字段名与 {@code WorkflowPlan} 的 record 组件同名 —— 不用缩写，因为它是<b>要给人看的计划</b>。
     */
    private java.util.Map<String, Object> planData(PipelineEvent.PlanCreated created) {
        java.util.List<java.util.Map<String, Object>> steps = new java.util.ArrayList<>();
        for (var step : created.plan().steps()) {
            java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("index", step.index());
            one.put("goal", step.goal());
            one.put("doneWhen", step.doneWhen());
            steps.add(one);
        }
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "plan");
        data.put("goal", created.plan().goal());
        data.put("steps", steps);
        return data;
    }

    /**
     * 文本载荷 → {@code {"text":"…"}}。
     *
     * <p><b>为什么不能把裸字符串塞进 SSE</b>：{@code SseEmitter.event().data(String)} <b>不转义
     * 换行</b>，而 markdown 的段落、列表、代码块到处都有 {@code \n} —— 帧会在那里被提前结束，后半段
     * 变成一条没有名字、没有 {@code data:} 前缀的垃圾行，<b>段落的空行还被当成帧分隔符吃掉</b>。
     * 症状是"回答的排版偶尔错乱"，极难归因。
     *
     * <p>⇒ 把文本包进 JSON：Jackson 会把 {@code \n} 转义成 {@code \\n}，序列化结果<b>永远只有
     * 一行</b>。代价是前端要读 {@code data.text} 而不是把 data 当字符串。
     */
    private java.util.Map<String, Object> textData(String text) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("text", text == null ? "" : text);
        return data;
    }

    /**
     * 工具调用 → SSE 数据。
     *
     * <p>用一个显式的小 {@code Map} 而不是直接序列化 record：这里只想暴露前端真正需要的三个
     * （{@code id} / {@code name} / {@code arguments}），而 record 的字段名会成为前端契约 ——
     * 直接暴露领域对象会让"内部字段改名"意外变成"前端契约破坏"。
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

    // ---- 会话目录 ----

    /**
     * 把"这次请求到达"记进会话目录：<b>不存在则创建</b>（标题取第一句用户消息），<b>存在则只刷新
     * 活跃时间</b>。
     *
     * <p><b>为什么在跑模型之前调用</b>：会话在请求到达的瞬间就该出现在侧栏里。等答完才记，用户发出
     * 第一句话后会看到侧栏<b>空着十几秒</b> —— 而那正是他最容易怀疑"没发出去"的时候。
     *
     * <p><b>为什么失败只记 WARN</b>：为"目录写不进去"丢掉已经产生的模型回复是双重损失；但也不能
     * 静默 —— 目录失败的表现是"侧栏看不到这个会话"而历史其实存下来了，这种"数据在、入口没了"
     * 必须留日志，否则会被当成前端 bug 排查很久。
     *
     * <p><b>为什么用管道的兜底会话 ID</b>：空 ID 时管道会落到
     * {@link AgentPipeline#FALLBACK_CONVERSATION_ID} 去写消息；这里若直接跳过，就会出现"<b>消息写进
     * default，目录里却没有 default</b>"—— 一份谁也打不开的历史。所以两侧必须用同一个兜底值
     * （正常路径上不会发生：Controller 总会生成 UUID）。
     */
    private void touchSession(String conversationId, MemoryMode mode, String message) {
        String cid = (conversationId == null || conversationId.isBlank())
                ? AgentPipeline.FALLBACK_CONVERSATION_ID : conversationId;
        try {
            sessionStore.touch(cid, mode, message);
        } catch (Exception e) {
            log.warn("[会话目录] 记录活跃会话失败（对话继续；只是侧栏可能看不到这个会话，"
                    + "而它的历史消息仍会被正常写入）：会话={} 原因={}", cid, e.toString());
        }
    }

    // ---- 发送与错误处理 ----

    /** 发送一个由 {@code SseEmitter.event()} 构建器组装好的事件（手动推送路径上唯一正确的发送方式）。 */
    private void sendNamedEvent(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (IOException e) {
            // 客户端断开（关页面/关标签）：用户正常操作，不是故障
            log.debug("[流式对话] 发送 SSE 事件时客户端已断开：{}", e.getMessage());
        } catch (IllegalStateException e) {
            // ★ 这条几乎总是"emitter 已完成"（客户端断开或已收尾）。它曾被归到下面的"序列化问题"
            //   分支且每个事件 WARN 一次 —— 实测一次断线刷出上百条 WARN，真正的错误反而被淹没。
            log.debug("[流式对话] 流已结束，丢弃后续事件：{}", e.getMessage());
        } catch (Exception e) {
            // 其余非 IO 异常：多半是事件构造/序列化的问题，必须可见
            log.warn("[流式对话] 发送 SSE 事件失败（事件构造或序列化问题）：{}", e.toString());
        }
    }

    /**
     * 流式出错的统一处理 —— <b>三路并行，缺一不可</b>：<b>前端</b>推 {@code error} 事件（用户能看到
     * 失败）；<b>服务端</b> {@code log.error} 记完整堆栈（若只推事件而不记日志，而推送恰好也失败 ——
     * 比如客户端断开 —— 错误就彻底消失，谁都不知道）；<b>框架</b> {@code complete()} 正常收尾，避免
     * 异常逃逸触发 "No converter for [BaseResponse]"（见类注释）。
     */
    private void handleStreamError(SseEmitter emitter, Throwable error) {
        // 第 1 路：前端 —— 推 error 事件（放在最前，保证用户先收到反馈）
        //   ★ 载荷同样包成 JSON：异常消息里带换行并不罕见（上游返回的多行报错），
        //     而裸字符串会像 message 一样把帧切断（见 textData 的注释）
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        sendNamedEvent(emitter, SseEmitter.event()
                .name(StreamEventType.ERROR)
                .data(java.util.Map.of("message", message)));

        // 第 2 路：服务端 —— 记录完整根因（含堆栈），这是 complete() 方案必须补上的一环
        log.error("[流式对话] 失败：{}", error.getMessage(), error);

        // 第 3 路：框架 —— 正常关闭，不抛异常
        completeQuietly(emitter);
    }

    /**
     * 安静地关闭流。
     *
     * <p><b>为什么必须包 try-catch</b>：{@code complete()} 在"响应已提交"或"连接已断开"时会抛
     * {@link IllegalStateException}。若让它逃逸，异常又会被 Spring 交给全局异常处理器，于是<b>再次
     * 触发 "No converter" 问题</b> —— 我们刚绕开的坑会绕回来。
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[流式对话] 关闭流时异常（通常是客户端已断开）：{}", e.getMessage());
        }
    }
}
