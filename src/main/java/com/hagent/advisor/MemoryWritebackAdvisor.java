package com.hagent.advisor;

import com.hagent.memory.ConversationMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆<b>写回</b>切面 —— 把本轮的"问 + 答"落库，<b>一次请求只写一次</b>。
 *
 * <h2>★ 它解决什么问题、产出谁消费</h2>
 *
 * <p>把本轮对话追加进 {@code ConversationMemory}；消费者是下一次请求的
 * {@code MemoryReadAdvisor}。不做它会怎样：历史永远为空，
 * 每一轮都像第一次对话。
 *
 * <h2>★ 为什么必须从"记忆读取"里拆出来（本轮最重要的重构）</h2>
 *
 * <p>拆分前两者同属一个切面、共用一个 order。但它们的<b>位置要求相反</b>：
 *
 * <table border="1">
 *   <caption>一个切面装不下两条相反的位置要求</caption>
 *   <tr><th></th><th>位置要求</th><th>原因</th></tr>
 *   <tr><td>读取</td><td>必须在拼装<b>之前</b></td>
 *       <td>它的产出要被拼装消费</td></tr>
 *   <tr><td>写回</td><td>必须在<b>整条链收尾</b></td>
 *       <td>它需要最终完整回复；且要避开工具循环</td></tr>
 * </table>
 *
 * <p>挤在一起时只能二选一牺牲。拆开之后各自干净：读取在 {@code MIN+200}，
 * 写回在 {@code MIN+150}（更外层 → after 更晚执行 → 更靠近链尾）。
 *
 * <h2>★ 为什么"只 after"是这类切面的正确形态</h2>
 *
 * <p>本切面的 before <b>什么都不做</b>（原样放行）。这不是偷懒，是语义决定的：
 * 写回需要"最终回复"，而最终回复只有模型返回之后才存在。
 *
 * <p>注意 around 链的特性：{@code before} 空不代表这个切面"没有位置" ——
 * 它的 <b>after 落在它自己 order 决定的镜像位置上</b>。
 * 想调整"写回"与"日志"的先后，调的是本类的 order，
 * 而<b>它的 before 会跟着一起挪</b>（虽然它没有 before）。
 * 这就是"一个切面只有一个位置"的含义。
 *
 * <h2>★ 写入失败不影响本次响应</h2>
 *
 * <p>"历史持久化"<b>不在关键路径上</b>：它失败的影响是"后续对话质量下降"，
 * 而不是"本次请求失败"。模型回复已经产生、token 已经花掉 ——
 * 为此丢弃它才是双重损失。所以这里捕获异常、记 ERROR，但<b>照常返回回复</b>。
 *
 * <p>但<b>必须记为 ERROR</b>：否则会变成"静默丢历史"，用户完全无感，运维也查不到。
 * 日志里明确写出后果（"后续对话将缺失这一轮"），而不是只打一句"写入失败"。
 *
 * <h2>★ 为什么写回拿得到"原始用户消息"</h2>
 *
 * <p>本切面在 {@code MIN+150}，比拼装（{@code MIN+250}）<b>更外层</b>，
 * 所以它收到的 request 就是 {@code ChatAppService} 交给链的那一个 ——
 * 只有 system（若有）与本次用户消息，没有被注入任何东西。
 * {@code getUserMessage()} 取到的正是用户的原始提问。
 *
 * <p>这一点很重要：若从"注入后的 prompt"里取用户消息，取到的可能是
 * 工具循环某轮的中间消息，写进历史就成了"用户说过一句他没说过的话"。
 */
@Slf4j
public class MemoryWritebackAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "MemoryWritebackAdvisor";

    /** 与 {@code MemoryReadAdvisor} 保持同一个兜底值 —— 否则读和写会落到不同的会话上。 */
    private static final String FALLBACK_CONVERSATION_ID = "default";

    private final ConversationMemory memory;

    public MemoryWritebackAdvisor(ConversationMemory memory) {
        this.memory = memory;
    }

    // ========================================================================
    // 非流式：拿到响应后立即写
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        recordTurn(conversationIdOf(request), request, response);
        return response;
    }

    // ========================================================================
    // 流式：等流读完再写（与非流式的差别只在"何时拿到完整回复"）
    // ========================================================================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> stream = chain.nextStream(request);

        // 聚合器会订阅流、把逐片内容攒起来，流结束时回调我们。
        // 写记忆必须用【完整文本】—— 写某一片进去，历史里就只有半句话，
        // 而下一轮模型会把这半句话当成本轮的回答。
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                stream,
                aggregated -> recordTurn(conversationIdOf(request), request, aggregated));
    }

    // ========================================================================
    // 核心逻辑
    // ========================================================================

    /** 把「本次问 + 模型答」写回记忆。 */
    private void recordTurn(String conversationId, ChatClientRequest originalRequest, ChatClientResponse response) {
        UserMessage user = originalRequest.prompt().getUserMessage();
        if (user == null) {
            // 没有用户消息就没有"一轮对话"可言（例如系统内部发起的调用）。
            // 明确跳过而不是写一条只有回答的记录 —— 那会让历史里出现"无因之果"。
            log.debug("[记忆-写] 会话 {} 本次请求没有用户消息，跳过写回", conversationId);
            return;
        }

        List<Message> turn = new ArrayList<>(2);
        turn.add(user);

        String replyText = extractText(response);
        if (replyText != null && !replyText.isBlank()) {
            turn.add(new AssistantMessage(replyText));
        }

        try {
            memory.append(conversationId, turn);
            log.debug("[记忆-写] 会话 {} 已写回 {} 条（问 + {}）",
                    conversationId, turn.size(), turn.size() > 1 ? "答" : "无答");
        } catch (Exception e) {
            // 见类注释：历史持久化不在关键路径上，失败不应影响本次响应。
            // 但必须记 ERROR —— 否则就是"静默丢历史"。
            log.error("[记忆-写] 会话 {} 历史写入失败（本轮回复仍会返回给用户；后续对话将缺失这一轮）：{}",
                    conversationId, e.getMessage(), e);
        }
    }

    /** 从响应里取模型回复文本；取不到返回 null。 */
    private String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return null;
        }
        var result = response.chatResponse().getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }
        return result.getOutput().getText();
    }

    /** 取出会话 ID。key 用框架常量，与 {@code MemoryReadAdvisor} 完全一致。 */
    private String conversationIdOf(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return FALLBACK_CONVERSATION_ID;
    }

    // ========================================================================
    // Advisor 元信息
    // ========================================================================

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.MEMORY_WRITEBACK;
    }
}
