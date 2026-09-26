package com.hagent.advisor;

import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.MemoryProperties;
import com.hagent.memory.TokenBudget;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆切面 —— 自动把历史注入 prompt，并在调用后写回。
 *
 * <h2>它做什么（一次调用的完整时序）</h2>
 * <pre>
 *   ① 从 request.context() 取出 conversationId
 *   ② 按预算加载历史（★ 此时历史里【不含】本次问题）
 *   ③ 组装新 prompt = system + 历史 + 本次问题
 *   ④ 继续调用链 → 模型
 *   ⑤ 拿到回复后，把「本次问 + 模型答」一起写回记忆
 * </pre>
 *
 * <h2>★ 三个关键设计决定</h2>
 *
 * <h3>1. conversationId 走 context，不走 prompt</h3>
 * <p>会话 ID 是<b>系统元数据</b>，不是给模型的内容。放进 prompt 会：
 * ① 让模型看到无意义的串、可能被复述；② 白花 token；
 * ③ 更根本 —— <b>prompt 文本会发给模型提供方，而 context 不会离开我们的进程</b>。
 *
 * <h3>2. 预算只管历史，不含 system 与本次问题</h3>
 * <p>这样"截断把本次问题丢掉"在结构上就不可能发生 ——
 * 本次问题是独立追加的，不参与 {@code load} 的预算计算。
 *
 * <h3>3. 先调模型、后写入记忆（而不是反过来）</h3>
 * <p>两种顺序对<b>发给模型的 prompt 内容没有影响</b>（都是"system + 历史 + 本次问"）。
 * 选"后写"的实际差别只有一处：<b>本次问题不进入 load 的预算范围</b>，
 * 正好与第 2 条对齐，少一条需要防守的规则。
 * <ul>
 *   <li>好处：模型调用失败时，记忆里不会留下"有问无答"的半截记录；</li>
 *   <li>代价：调用成功但写入失败时，这一轮不在历史里（见下面的容错处理）。</li>
 * </ul>
 *
 * <h2>★ 写入失败为什么不影响本次响应</h2>
 *
 * <p>"历史持久化"<b>不在关键路径上</b>：它失败的影响是"后续对话质量下降"，
 * 而不是"本次请求失败"。模型回复已经产生、token 已经花掉 ——
 * 为此丢弃它才是双重损失。所以这里捕获异常、记 ERROR 日志，但<b>照常返回回复</b>。
 *
 * <p>但<b>必须记为 ERROR</b>：否则会变成"静默丢历史"——用户完全无感，运维也查不到。
 */
@Slf4j
public class ConversationMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "ConversationMemoryAdvisor";

    /**
     * 调用方没传会话 ID 时的兜底值。
     *
     * <p>Spring AI 2.0.1 的 {@code ChatMemory} 只提供 key 常量（CONVERSATION_ID），
     * 没有提供"默认会话 ID"常量，故在此自定义并集中管理（避免裸字符串散落）。
     */
    private static final String FALLBACK_CONVERSATION_ID = "default";

    private final ConversationMemory memory;

    /**
     * 按模式索引的预算表。
     *
     * <p><b>为什么用 Map 而不是两个字段 + if-else</b>：
     * 加第三种模式（阶段 9 之后可能出现"批量任务"之类）时只需在这里多一行，
     * 而不是在所有分支里找遗漏。同时天然保证"每个模式都有对应预算"——
     * 这正是修正前那个 bug 的根源（workflowBudget 字段存在但无人读）。
     */
    private final Map<MemoryMode, TokenBudget> budgets;

    public ConversationMemoryAdvisor(ConversationMemory memory, MemoryProperties properties) {
        this.memory = memory;
        this.budgets = Map.of(
                MemoryMode.CONVERSATION, new TokenBudget(
                        properties.getConversationMaxTokens(), properties.getConversationMaxMessages()),
                MemoryMode.WORKFLOW, new TokenBudget(
                        properties.getWorkflowMaxTokens(), properties.getWorkflowMaxMessages()));
    }

    // ========================================================================
    // 非流式
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = conversationIdOf(request);
        Prompt augmented = augment(request.prompt(), modeOf(request), conversationId);

        ChatClientResponse response = chain.nextCall(request.mutate().prompt(augmented).build());

        recordTurn(conversationId, request.prompt(), response);
        return response;
    }

    // ========================================================================
    // 流式：等流读完再写入（与非流式的差别只在"何时拿到完整回复"）
    // ========================================================================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String conversationId = conversationIdOf(request);
        Prompt augmented = augment(request.prompt(), modeOf(request), conversationId);

        Flux<ChatClientResponse> stream =
                chain.nextStream(request.mutate().prompt(augmented).build());

        // 聚合器等流结束后回调，此时才拿到完整回复 —— 写记忆要用完整文本，不能用某一片
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                stream,
                aggregated -> recordTurn(conversationId, request.prompt(), aggregated));
    }

    // ========================================================================
    // 核心逻辑
    // ========================================================================

    /**
     * 组装新 prompt = system + 历史 + 本次用户消息。
     *
     * <p>顺序不能变：system 必须在最前；历史在中间按时间正序；
     * 本次用户消息在最后（模型据此判断"现在要回答什么"）。
     *
     * <p><b>★ 为什么用 {@code new Prompt(messages, original.getOptions())}
     * 而不是 {@code original.mutate().messages(...)}</b>：
     * 后者走的是 Builder，而 Builder 的 {@code messages(...)} 是<b>替换</b>消息列表 ——
     * 若它不带上原有的 {@code ChatOptions}，那么模型名、temperature、
     * reasoning-effort 这些<b>请求级选项就会丢失</b>，
     * 表现为"配置明明写了却没生效"（正是本项目一直在防的静默失效）。
     * 用构造器显式把 options 带上，是把这个风险从"依赖 Builder 内部行为"
     * 变成"我们主动传递"。
     */
    private Prompt augment(Prompt original, MemoryMode mode, String conversationId) {
        TokenBudget budget = budgets.get(mode);
        List<Message> history = memory.load(conversationId, budget);

        List<Message> messages = new ArrayList<>();
        // ① system（如果原 prompt 有的话）
        SystemMessage system = original.getSystemMessage();
        if (system != null) {
            messages.add(system);
        }
        // ② 历史（时间正序，可直接拼）
        messages.addAll(history);
        // ③ 本次用户消息（独立追加，不参与预算计算）
        UserMessage user = original.getUserMessage();
        if (user != null) {
            messages.add(user);
        }

        log.debug("[记忆] 会话 {} 模式={} 注入历史 {} 条（预算 {} token / {} 条）",
                conversationId, mode, history.size(), budget.maxTokens(), budget.maxMessages());

        return new Prompt(messages, original.getOptions());
    }

    /**
     * 取出本次调用的记忆模式。
     *
     * <p><b>★ 这是修正后的写法（改前是一个永远返回会话预算的死方法）</b>。
     * 模式由<b>调用方显式声明</b>并经 context 传来 ——
     * 切面自己<b>不去猜</b>"这看起来像不像工作流"：
     * 它没有足够信息，猜错会静默地少给或多给 10 倍预算。
     *
     * <p>取不到时落到 {@link MemoryMode#DEFAULT}（会话模式）：
     * 让"忘记传"的后果偏向更省的那一侧，而不是白烧 token。
     */
    private MemoryMode modeOf(ChatClientRequest request) {
        Object value = request.context().get(MemoryMode.CONTEXT_KEY);
        if (value instanceof MemoryMode mode) {
            return mode;
        }
        // 传了但类型不对（例如被序列化成字符串 "WORKFLOW"）：明确暴露，不静默忽略 ——
        // 否则调用方会以为工作流预算生效了，实际走的是会话预算。
        if (value != null) {
            log.warn("[记忆] context 里的 {} 类型不是 MemoryMode 而是 {}，已回退为 {}。"
                            + "正确用法：a.param(MemoryMode.CONTEXT_KEY, MemoryMode.WORKFLOW)",
                    MemoryMode.CONTEXT_KEY, value.getClass().getName(), MemoryMode.DEFAULT);
        }
        return MemoryMode.DEFAULT;
    }

    /** 把「本次问 + 模型答」写回记忆。 */
    private void recordTurn(String conversationId, Prompt originalPrompt, ChatClientResponse response) {
        UserMessage user = originalPrompt.getUserMessage();
        if (user == null) {
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
        } catch (Exception e) {
            // 见类注释：历史持久化不在关键路径上，失败不应影响本次响应。
            // 但必须记 ERROR —— 否则就是"静默丢历史"。
            log.error("[记忆] 会话 {} 历史写入失败（本轮回复仍会返回给用户；后续对话将缺失这一轮）",
                    conversationId, e);
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

    /**
     * 取出会话 ID。
     *
     * <p>key 用框架常量 {@link ChatMemory#CONVERSATION_ID} 而不是裸字符串 ——
     * 保持单一出处（这正是我们批评参考实现"context 里用裸字符串 key"的那一点）。
     *
     * <p>没有 ID 时返回 {@link #FALLBACK_CONVERSATION_ID}：这样即使调用方忘了传，
     * 功能也不会崩，只是所有这种调用共享同一个"默认会话"。
     * 注意 Spring AI 2.0.1 的 {@code ChatMemory} <b>只提供 key 常量，
     * 没有提供默认会话 ID 常量</b>，所以这个兜底值由我们自己定义并集中在这里。
     */
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

    /**
     * order = 1（在 {@code PromptTraceAdvisor} 的内层）。
     *
     * <p>为什么必须在日志切面的<b>内层</b>：
     * order 越小越靠外层，"先执行、后收尾"。
     * 日志切面（order=0）在外层，所以它在<b>本切面注入历史之后</b>才读到 prompt ——
     * 于是日志里能看到<b>注入后</b>的完整内容，也就是"模型真正收到了什么"。
     *
     * <p>这正是我们之前讲过的认知："<b>order 决定日志记到的是注入前还是注入后的 prompt</b>"。
     * 如果反过来（日志在内层），日志将看不到历史注入的效果，排查记忆问题时会误判为"没生效"。
     */
    @Override
    public int getOrder() {
        return 1;
    }
}
