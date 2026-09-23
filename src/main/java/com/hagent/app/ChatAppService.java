package com.hagent.app;

import com.hagent.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 对话应用服务 —— app 层。
 *
 * <p><b>它现在很薄，但它的存在已经有明确价值</b>：
 * Controller 依赖的是本类，而不是直接依赖 {@link ChatClient}。
 * 于是当阶段 9 把这里从"一次模型调用"升级成"多步 Agent 循环"时，
 * <b>Controller 一行都不用改</b>。这就是 app 层隔离的价值，
 * 在阶段 1 就已经体现出来 —— 而不是等到要改的时候才后悔没分层。
 *
 * <p><b>调用链</b>：
 * <pre>
 *   chatClient.prompt()   →  开始一次对话
 *       .user(message)    →  填充用户消息
 *       .call()           →  同步调用（还有 .stream() 用于流式，阶段 1 的下一步）
 *       .content()        →  取回纯文本内容
 * </pre>
 *
 * <p>这条链会经过 AiConfig 里挂载的 PromptTraceAdvisor ——
 * 所以每次调用，日志里都会出现"请求 → 响应 + 耗时"两行，
 * 这就是阶段 1 的验收方式：<b>不是"返回了答案"，而是"日志里能看到完整链路"</b>。
 */
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
}
