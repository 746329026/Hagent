package com.hagent.app;

import com.hagent.config.StreamProperties;
import com.hagent.dto.ChatResponse;
import com.hagent.memory.MemoryMode;
import com.hagent.stream.StreamEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话应用服务 —— app 层。
 *
 * <p><b>它现在很薄，但它的存在已经有明确价值</b>：
 * Controller 依赖的是本类，而不是直接依赖 {@link ChatClient}。
 * 于是当阶段 9 把这里从"一次模型调用"升级成"多步 Agent 循环"时，
 * <b>Controller 一行都不用改</b>。
 *
 * <p><b>同步与流式的关系</b>：本类同时提供 {@link #chat} 与 {@link #stream}，
 * 两者共享同一个 ChatClient，只是调用的方法不同（{@code .call()} vs {@code .stream()}）。
 * 这正好验证了之前的结论：同步与流式是"同一能力的不同返回方式"，
 * 差异只在返回路径，不在业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    private final ChatClient chatClient;

    /**
     * 流式相关参数（超时 / 重连间隔 / 心跳）。
     * 通过构造器注入（`@RequiredArgsConstructor` 只收 final 字段）。
     */
    private final StreamProperties streamProperties;

    /**
     * 流式失败计数器。
     *
     * <p>为什么需要它：因为流式出错时我们用 {@code complete()} 正常收尾（而不是
     * {@code completeWithError}），<b>框架会把这次失败当成"正常结束"</b>，
     * 于是它自带的成功率统计不可信。所以失败次数必须自己显式记录。
     *
     * <p>后续接入可观测性（Micrometer）时，把这个 AtomicLong 换成 Counter 即可。
     * 这是"用显式替代隐式"：既然框架不认，就自己记。
     */
    private final AtomicLong streamFailureCount = new AtomicLong();

    /**
     * 同步对话：把用户消息发给模型，返回回复文本。
     *
     * <p><b>★ 阶段 6 起带会话记忆</b>：{@code conversationId} 通过
     * {@code advisors(...)} 放进<b>请求 context</b>（不是 prompt）——
     * 会话记忆切面会从 context 取出它，加载历史并注入 prompt。
     *
     * <p>为什么会话 ID 不进 prompt：它是<b>系统元数据</b>，模型不需要它；
     * 而且 prompt 文本会发给模型提供方，context 不会离开我们的进程。
     *
     * <p><b>★ 为什么模式标记也在这里声明</b>：
     * 只有 app 层知道"本次调用属于哪种场景"。切面（advisor）拿不到这个信息，
     * 若让它去猜，猜错的后果是<b>静默地少给或多给 10 倍预算</b>。
     * 所以模式与 {@code conversationId} 一样，走 context 显式传递，
     * 由 {@link MemoryMode} 承载类型。
     * 阶段 9 的工作流引擎在这里改传 {@link MemoryMode#WORKFLOW} 即可，切面无需改动。
     *
     * @param message        已经过参数校验的用户消息（非空）
     * @param conversationId 会话标识（由 Controller 生成或透传）
     * @return 模型回复
     */
    public ChatResponse chat(String message, String conversationId) {
        String reply = chatClient.prompt()
                .user(message)
                // key 用框架常量 ChatMemory.CONVERSATION_ID，保持单一出处
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                // ★ 记忆模式也由 app 层显式声明，切面不去猜（见 MemoryMode 的类注释）
                .advisors(a -> a.param(MemoryMode.CONTEXT_KEY, MemoryMode.CONVERSATION))
                .call()
                .content();
        return new ChatResponse(reply);
    }

    /**
     * 流式对话：把模型逐片输出以 SSE 事件的形式推给前端。
     *
     * <p><b>用 SseEmitter 而不是直接返回 Flux</b>（阶段 1 的刻意选择）：
     * SseEmitter 是"手动"的 —— 每个事件都清楚自己在推什么、何时结束，
     * 最适合先理解流式机制。返回 Flux 的"框架自动推进"留给阶段 3 对比。
     *
     * <p><b>事件的完整生命周期</b>（前端据此渲染）：
     * <pre>
     *   message  →  每一片文字（可能多次）
     *   done     →  正常结束（恰好一次）
     *   error    →  出错（异常时一次，替代 done）
     * </pre>
     *
     * <p><b>这里返回的是 {@code Flux<String>}</b>：流式文字场景下够用。
     * 但注意 —— 之前讨论过，{@code Flux<String>} 会丢掉工具调用信息。
     * 阶段 1 还没有工具，所以无影响；<b>阶段 9 做多步 Agent 时，
     * 这里要换成 {@code Flux<ChatResponse>} 以保留工具调用片</b>。
     * 这是"什么时候该升级类型"的活例子：能力还没到，就不提前升级。
     *
     * <p><b>★ 本方法刻意不带会话记忆</b>（方式三是唯一带记忆的流式路径，理由见
     * {@link #streamWithServerSentEvent}）。它不对应任何产品路径，
     * 只用于展示"SseEmitter 手动驱动"这一种机制。
     */
    public void stream(String message, SseEmitter emitter) {
        // ChatClient.stream() 返回一个冷的 Flux：只有被订阅时才开始请求模型。
        chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .doOnNext(chunk -> sendEvent(emitter, StreamEventType.MESSAGE, chunk))
                // 正常结束：先推 done 事件，再正常关闭流
                .doOnComplete(() -> {
                    sendEvent(emitter, StreamEventType.DONE, "[DONE]");
                    completeQuietly(emitter);
                })
                // 出错：三路并行处理（详见 handleStreamError 的注释）
                .doOnError(error -> handleStreamError(emitter, error))
                .subscribe();
        // 注意：subscribe() 是非阻塞的，它会立刻返回。
        // Flux 的请求在 Reactor 的线程上异步推进，逐片触发 doOnNext。
    }

    /**
     * 【方式二】把模型流直接映射成 {@code Flux<ServerSentEvent<String>>} 返回 —— 框架驱动。
     *
     * <p><b>与 {@link #stream} 的关键差别（这是对比的核心）</b>：
     * <pre>
     *   方式一（stream）        : 我们订阅，我们 send，我们 complete   —— 全程手动
     *   方式二（本方法）        : 我们只负责"把流映射成事件"，其余交给 Spring
     * </pre>
     * 本方法里<b>没有一处 subscribe / send / complete</b>，Spring MVC 拿到返回值后
     * 会自己订阅、自己写响应、结束时自己关闭连接。
     *
     * <p><b>断连处理也不一样</b>：客户端断开时，Spring 会取消这个订阅，
     * 上游 Flux 随之被取消 —— 模型请求自然停止，不会白烧 token。
     * 方式一则需要我们自己在 send 失败时留意（见 {@link #sendEvent}）。
     *
     * <p>事件里带上 {@code id}：前端断线重连时，可以凭最后收到的 id 判断续传位置
     * （浏览器会自动在重连请求头 {@code Last-Event-ID} 里带上它）。
     *
     * <p><b>★ 本方法刻意不带会话记忆</b>（方式三是唯一带记忆的流式路径，理由见
     * {@link #streamWithServerSentEvent}）。它只用于展示"框架驱动"这一种机制。
     */
    public Flux<ServerSentEvent<String>> streamAsFlux(String message) {
        // 用一个从 1 开始的自增序号作为事件 id
        AtomicLong seq = new AtomicLong();
        return chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq.incrementAndGet()))
                        .event(StreamEventType.MESSAGE)
                        .data(chunk)
                        .build())
                // concatWith：在正文流之后接上 done 事件。
                // 注意这里用 concatWith 而不是 concat，因为前者是"顺序拼接"的语义。
                .concatWith(Mono.fromSupplier(() -> ServerSentEvent.<String>builder()
                        .event(StreamEventType.DONE)
                        .data("[DONE]")
                        .build()))
                // 出错时同样把错误变成流内的一个事件（而不是抛给框架）——
                // 理由与方式一相同：流式接口的错误应该是"流的一部分"。
                .onErrorResume(error -> {
                    long count = streamFailureCount.incrementAndGet();
                    log.error("[流式对话-flux] 失败（累计 {} 次）：{}", count, error.getMessage(), error);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event(StreamEventType.ERROR)
                            .data(error.getMessage())
                            .build());
                });
    }

    /**
     * 【方式三】SseEmitter + 带 {@code id} / {@code retry} 的完整事件 —— 手动驱动，字段最全。
     *
     * <p><b>与 {@link #stream} 的差别</b>：事件除了 {@code event} 与 {@code data}，
     * 还带上 {@code id} 与 {@code retry}：
     * <ul>
     *   <li>{@code id}：给每个事件编号。浏览器断线重连时会自动带上
     *       {@code Last-Event-ID} 请求头，服务端可据此判断从哪继续；</li>
     *   <li>{@code retry}：告诉浏览器"断了之后隔多久重连"（只在下发一次即可，浏览器会记住）。</li>
     * </ul>
     *
     * <p>这是后续多步 Agent 的<b>主力形态</b>：既要手动插入不同类型的事件
     * （message / step / question / done），又要事件带 id 便于前端去重与续传。
     *
     * <h3>★ 这里踩过一个真实的坑（值得记牢）</h3>
     *
     * <p>我最初把事件构造成 {@code ServerSentEvent<String>} 对象再交给
     * {@code emitter.send(...)}，结果<b>一个事件都发不出去</b>，客户端只看到"连上就断"，
     * 而服务端日志干干净净。
     *
     * <p><b>根因</b>：{@code SseEmitter} <b>不认识</b> {@code ServerSentEvent} 这个类型
     * （用 javap 读它的字节码常量池可以证实：里面根本没有 ServerSentEvent 的引用）。
     * {@code ServerSentEvent} 是给 <b>WebFlux / 返回 Publisher</b> 那条路用的，
     * 由 {@code ServerSentEventHttpMessageWriter} 负责写帧。
     * 而 {@code SseEmitter.send(Object)} 走的是 {@code ResponseBodyEmitter} 的普通序列化路径 ——
     * 它会把这个对象当"要转成 JSON 的普通数据"，于是构造/序列化直接失败。
     *
     * <p><b>为什么这次失败特别隐蔽</b>：异常被 {@code sendEvent} 的 catch 吞成了 debug 日志，
     * 客户端只看到连接断开。这也是为什么现在 {@code sendEvent} 会区分
     * "客户端断开（debug）"与"其它异常（warn）"。
     *
     * <p><b>正解</b>：手写 {@code SseEmitter.event()} 构建器 —— 它才是 SseEmitter 认的"事件表达"。
     * 两种写法的分野正对应三条流式路径：
     * <pre>
     *   SseEmitter 手动推送   -> 用 SseEmitter.event() 构建器
     *   返回 Publisher/Flux   -> 用 ServerSentEvent（由框架的 writer 写帧）
     * </pre>
     *
     * <h3>★ 阶段 6 起：本方法带会话记忆（只此一条流式路径）</h3>
     *
     * <p><b>只有方式三接入记忆，方式一（{@link #stream}）与方式二
     * （{@link #streamAsFlux}）刻意不接</b> —— 这是主动决定，不是遗漏：
     * <ul>
     *   <li>方式一/二的定位是"<b>流式机制的对照实验</b>"：
     *       它们的价值在于把"手动驱动 vs 框架驱动"的差异摆在一起对比。
     *       给它们加记忆只会增加噪声，让对照变得不干净；</li>
     *   <li>方式三是后续多步 Agent 的<b>主力形态</b>，真正的产品路径。
     *       记忆属于产品路径，所以只加在这里。</li>
     * </ul>
     * 这也是"同一能力不要在多处重复实现"的应用：有记忆的流式只有一份实现。
     *
     * <p>记忆的接法与同步路径<b>完全一致</b>（同一套 context 机制）：
     * 会话 ID 与模式都通过 {@code advisors(...)} 放进请求 context。
     *
     * @param conversationId 会话标识（由 Controller 生成或透传）
     */
    public void streamWithServerSentEvent(String message, String conversationId, SseEmitter emitter) {
        AtomicLong seq = new AtomicLong();
        long retryMs = streamProperties.getReconnectDelayMs();
        boolean[] retrySent = {false};

        chatClient.prompt()
                .user(message)
                // 与同步路径同一套机制：会话 ID + 模式走 context，不进 prompt
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(a -> a.param(MemoryMode.CONTEXT_KEY, MemoryMode.CONVERSATION))
                .stream()
                .content()
                .doOnNext(chunk -> {
                    long id = seq.incrementAndGet();
                    SseEmitter.SseEventBuilder builder = SseEmitter.event()
                            .id(String.valueOf(id))
                            .name(StreamEventType.MESSAGE)
                            .data(chunk);
                    // retry 只在第一个事件上下发一次（浏览器会记住该值）
                    if (!retrySent[0]) {
                        builder.reconnectTime(retryMs);
                        retrySent[0] = true;
                    }
                    sendEvent(emitter, builder);
                })
                .doOnComplete(() -> {
                    sendEvent(emitter, SseEmitter.event()
                            .id(String.valueOf(seq.incrementAndGet()))
                            .name(StreamEventType.DONE)
                            .data("[DONE]"));
                    completeQuietly(emitter);
                })
                .doOnError(error -> handleStreamError(emitter, error))
                .subscribe();
    }

    /**
     * 发送一个由 {@code SseEmitter.event()} 构建器组装好的事件。
     *
     * <p>这是 SseEmitter 手动推送路径上唯一正确的发送方式（见
     * {@link #streamWithServerSentEvent} 里记录的踩坑）。
     */
    private void sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
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
     * <p><b>为什么不用 {@code emitter.completeWithError(error)}</b>（实测踩过的坑）：
     * 它会把异常<b>抛给 Spring 的异步处理链路</b>，最终落到全局异常处理器，
     * 而全局异常处理器想返回 JSON —— 可这个响应的 Content-Type 早就被锁成
     * {@code text/event-stream} 了。HTTP 不允许中途换格式，于是报：
     * <pre>
     *   HttpMessageNotWritableException:
     *   No converter for [class com.hagent.common.BaseResponse]
     *   with preset Content-Type 'text/event-stream'
     * </pre>
     * 即：<b>流式接口的错误，不能走"返回 JSON"那条路</b>。
     *
     * <p><b>但简单地换成 {@code complete()} 也有代价</b>，所以这里用三路把它补回来：
     * <ol>
     *   <li><b>前端</b>：推 {@code error} 事件 —— 用户能看到失败；</li>
     *   <li><b>服务端</b>：{@code log.error} 记录完整堆栈 —— 弥补被丢弃的根因
     *       （若只推事件而不记日志，而事件推送恰好也失败——比如客户端断开——
     *        错误就彻底消失，谁都不知道）；</li>
     *   <li><b>框架</b>：{@code complete()} 正常收尾 —— 避免异常逃逸触发上面那个错误。</li>
     * </ol>
     *
     * <p><b>诚实的遗留</b>：第 3 步让框架无法自动感知失败，因此<b>失败统计不能依赖框架</b>。
     * 这里先用 {@link #streamFailureCount} 显式计数兜住，等做可观测性时换成正式指标。
     */
    private void handleStreamError(SseEmitter emitter, Throwable error) {
        // 第 1 路：前端 —— 推 error 事件（放在最前，保证用户先收到反馈）
        sendEvent(emitter, StreamEventType.ERROR, error.getMessage());

        // 第 2 路：服务端 —— 记录完整根因（含堆栈），这是 complete() 方案必须补上的一环
        long count = streamFailureCount.incrementAndGet();
        log.error("[流式对话] 失败（累计 {} 次）：{}", count, error.getMessage(), error);

        // 第 3 路：框架 —— 正常关闭，不抛异常
        completeQuietly(emitter);
    }

    /**
     * 安静地关闭流。
     *
     * <p><b>为什么必须包 try-catch</b>：{@code complete()} 在"响应已提交"或
     * "连接已断开"时会抛 {@link IllegalStateException}。若让它逃逸，异常又会被
     * Spring 交给全局异常处理器，于是<b>再次触发上面那个 "No converter" 问题</b>——
     * 我们刚绕开的坑会绕回来。
     *
     * <p>关闭失败本身通常不是故障（连接可能已由客户端断开），所以只记 debug。
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[流式对话] 关闭流时异常（通常是客户端已断开）：{}", e.getMessage());
        }
    }

    /**
     * 发送一个 SSE 命名事件。所有 SSE 发送都收口到这里，
     * 保证事件名只来自 StreamEventType（单一出处），且发送异常被统一处理。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            // 客户端断开（关页面/关标签）时 send 会抛 IOException。
            // 这是用户正常操作导致的，不是故障；记录后不再尝试发送。
            log.debug("[流式对话] 发送事件 {} 时客户端已断开：{}", eventName, e.getMessage());
        }
    }

    /**
     * 发送一个已经组装好的 {@link ServerSentEvent}。
     *
     * <p><b>★ 这个方法只用于 {@link #streamAsFlux} 那条路（返回 Publisher）。</b>
     * 在 {@code SseEmitter} 手动推送的场景里<b>不能用它</b> ——
     * {@code SseEmitter} 不认识 {@code ServerSentEvent}，会把它当成"要 JSON 序列化的普通对象"，
     * 导致事件发不出去（详见 {@link #streamWithServerSentEvent} 里记录的踩坑）。
     * 手动推送请用 {@code SseEmitter.event()} 构建器那个重载。
     */
    private void sendEvent(SseEmitter emitter, ServerSentEvent<String> event) {
        try {
            emitter.send(event);
        } catch (IOException e) {
            // 客户端断开（关页面/关标签）：用户正常操作，不是故障
            log.debug("[流式对话] 发送 SSE 事件 {} 时客户端已断开：{}", event.event(), e.getMessage());
        } catch (Exception e) {
            // 非 IO 异常：多半是事件构造/序列化的问题，必须可见
            log.warn("[流式对话] 发送 SSE 事件 {} 失败（事件构造或序列化问题）：{}",
                    event.event(), e.toString());
        }
    }
}
