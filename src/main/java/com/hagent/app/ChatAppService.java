package com.hagent.app;

import com.hagent.dto.ChatResponse;
import com.hagent.stream.StreamEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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
                .doOnComplete(() -> {
                    sendEvent(emitter, StreamEventType.DONE, "[DONE]");
                    emitter.complete();
                })
                .doOnError(error -> {
                    log.warn("[流式对话] 出错：{}", error.getMessage());
                    sendEvent(emitter, StreamEventType.ERROR, error.getMessage());
                    emitter.completeWithError(error);
                })
                .subscribe();
        // 注意：subscribe() 是非阻塞的，它会立刻返回。
        // Flux 的请求在 Reactor 的线程上异步推进，逐片触发 doOnNext。
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
