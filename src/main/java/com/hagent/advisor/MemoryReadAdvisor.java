package com.hagent.advisor;

import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryBudgets;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.MemoryProperties;
import com.hagent.memory.TokenBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 记忆<b>读取</b>切面 —— 加载会话历史，放进 {@code context}，<b>不改 prompt</b>。
 *
 * <h2>★ 它解决什么问题、产出谁消费</h2>
 *
 * <p>产出 {@code List<Message>} 写入 {@link PromptFragmentKeys#MEMORY_MESSAGES}；
 * 消费者是 {@code PromptAssemblyAdvisor}，由它把历史拼进 prompt。
 *
 * <p>不做这一步会怎样：模型看不到上一轮说过什么，"我叫张三"之后问"我叫什么"
 * 只能得到"我不知道"。
 *
 * <h2>★ 从"改 prompt"改成"写 context" —— 拆分带来的关键变化</h2>
 *
 * <p>原 {@code ConversationMemoryAdvisor} 在 {@code augment()} 里<b>重建了整个消息列表</b>：
 *
 * <pre>
 *   List&lt;Message&gt; messages = new ArrayList&lt;&gt;();
 *   messages.add(original.getSystemMessage());
 *   messages.addAll(history);
 *   messages.add(original.getUserMessage());
 *   return new Prompt(messages, original.getOptions());   // ← 其余消息全被丢弃
 * </pre>
 *
 * <p>这段代码本身没错，但它的前提是"我是链上第一个改 prompt 的人"。
 * 一旦 RAG 切面先往 prompt 里放了片段，历史注入就会把它<b>静默抹掉</b>
 * （{@code AdvisorChainMessageSemanticsTest} 实验 2 就是复刻这个行为做的断言）。
 *
 * <p>所以本轮把职责拆开：<b>读取的人只产数据，拼装的人只有一个。</b>
 * 这样"谁重建消息列表"从"靠约定"变成"结构上当且仅当一处"。
 *
 * <h2>★ 预算只管历史，不含 system 与本次问题</h2>
 *
 * <p>与拆分前一致：本次用户消息<b>不参与</b> {@code load} 的预算计算，
 * 所以"截断把当前问题丢掉"在结构上不可能发生。
 * 这也是这个切面能安心只做"读"的原因 —— 读的东西和当前问题无关。
 *
 * <h2>★ 为什么"先调模型、后写记忆"在拆分后依然成立</h2>
 *
 * <p>拆分前这个决定的理由是"本次问题不进入 load 的预算范围"。
 * 拆分后更直接：<b>读和写是两个切面</b>，写回（{@code MemoryWritebackAdvisor}）
 * 在整条链的最外层收尾，读（本类）在拼装之前 —— 顺序天然就是
 * "先读、再问、最后写"。模型调用失败时自然不会有写回，
 * 记忆里不会留下"有问无答"的半截记录。
 */
@Slf4j
public class MemoryReadAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "MemoryReadAdvisor";

    /**
     * 调用方没传会话 ID 时的兜底值。
     *
     * <p>框架的 {@code ChatMemory} 只提供 key 常量（{@code CONVERSATION_ID}），
     * 没有"默认会话 ID"常量，故在此自定义并集中管理（避免裸字符串散落）。
     * 兜底的意义是"忘了传也不会崩，只是所有这类调用共享同一个默认会话"。
     */
    private static final String FALLBACK_CONVERSATION_ID = "default";

    private final ConversationMemory memory;

    /**
     * 按模式索引的预算表。
     *
     * <p><b>★ 与 {@code ChatMemoryAdapter} 共用同一份</b>（{@link MemoryBudgets}）——
     * 两个调用点读的是同一份配置、表达的是同一条规则。
     *
     * <p>早先这里与适配器<b>各抄了一份</b> {@code Map.of(...)}。问题不在于"看着重复"，
     * 而在于 {@code Map.of} 靠手写列举、<b>没有任何机制</b>保证"每个模式都被覆盖"：
     * 将来加第三个模式时漏改一处，就会在 {@code budgets.get(mode).maxTokens()} 上
     * 以 <b>NPE</b> 的形式炸掉，而堆栈里看不出"漏配了一个枚举常量"。
     * 现在 {@link MemoryBudgets} 用穷尽 switch + 遍历 {@code values()} 建表，
     * 把这类错误从运行期挪到<b>编译期</b>。
     */
    private final MemoryBudgets budgets;

    public MemoryReadAdvisor(ConversationMemory memory, MemoryProperties properties) {
        this.memory = memory;
        this.budgets = MemoryBudgets.from(properties);
    }

    // ========================================================================
    // 非流式与流式：行为完全相同（before 侧，与"何时拿到完整回复"无关）
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(withHistory(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(withHistory(request));
    }

    // ========================================================================
    // 核心：读历史 → 放进 context
    // ========================================================================

    /**
     * 把历史写进 {@code context} 并原样继续调用链。
     *
     * <p><b>★ 为什么用 {@code request.mutate().context(k, v).build()} 而不是
     * {@code request.context().put(k, v)}</b>：后者的行为取决于
     * {@code context()} 返回的那个 Map 是否可变。框架构造请求时可能用
     * {@code Map.of(...)}（不可变），此时 {@code put} 会抛
     * {@code UnsupportedOperationException}；也可能用 {@code HashMap}，
     * 此时 {@code put} 会<b>绕过 record 的不可变约定、改动别人看到的那个实例</b>。
     * 两种结局都不好。<b>走 builder 是唯一稳定的写法</b> ——
     * 字节码显示 {@code mutate()} 会先复制一份 HashMap（{@code new HashMap<>(context)}），
     * 在副本上改，不影响上游。
     *
     * <p>注意这里<b>不动 prompt</b>：{@code mutate()} 虽然也复制了 prompt，
     * 但我们一个字段都不改，所以下游看到的 prompt 与本切面收到的一致。
     */
    private ChatClientRequest withHistory(ChatClientRequest request) {
        String conversationId = conversationIdOf(request);
        MemoryMode mode = modeOf(request);
        TokenBudget budget = budgets.forMode(mode);
        List<Message> history = memory.load(conversationId, budget);

        log.debug("[记忆-读] 会话 {} 模式={} 注入历史 {} 条（预算 {} token / {} 条）",
                conversationId, mode, history.size(), budget.maxTokens(), budget.maxMessages());

        return request.mutate()
                .context(PromptFragmentKeys.MEMORY_MESSAGES, history)
                .build();
    }

    /**
     * 取出本次调用的记忆模式。
     *
     * <p>模式由<b>调用方显式声明</b>并经 context 传来 —— 切面自己<b>不去猜</b>
     * "这看起来像不像工作流"：它没有足够信息，猜错会静默地少给或多给 10 倍预算。
     *
     * <p>取不到时落到 {@link MemoryMode#DEFAULT}（会话模式）：
     * 让"忘记传"的后果偏向更省的那一侧。
     *
     * <p>传了但类型不对时<b>逐字暴露</b>而不静默忽略 —— 否则调用方会以为
     * 工作流预算生效了，实际走的是会话预算。
     */
    private MemoryMode modeOf(ChatClientRequest request) {
        Object value = request.context().get(MemoryMode.CONTEXT_KEY);
        if (value instanceof MemoryMode mode) {
            return mode;
        }
        if (value != null) {
            log.warn("[记忆-读] context 里的 {} 类型不是 MemoryMode 而是 {}，已回退为 {}。"
                            + "正确用法：a.param(MemoryMode.CONTEXT_KEY, MemoryMode.WORKFLOW)",
                    MemoryMode.CONTEXT_KEY, value.getClass().getName(), MemoryMode.DEFAULT);
        }
        return MemoryMode.DEFAULT;
    }

    /** 取出会话 ID；key 用框架常量 {@link ChatMemory#CONVERSATION_ID} 而不是裸字符串。 */
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
        return AdvisorOrders.MEMORY_READ;
    }
}
