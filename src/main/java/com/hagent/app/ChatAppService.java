package com.hagent.app;

import com.hagent.dto.ChatResponse;
import com.hagent.stream.StreamEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
     * @param message 已经过参数校验的用户消息（非空）
     * @return 模型回复
     */
    public ChatResponse chat(String message) {
        String reply = chatClient.prompt()
                .user(message)
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
}
