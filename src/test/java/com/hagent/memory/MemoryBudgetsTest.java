package com.hagent.memory;

import com.hagent.advisor.MemoryReadAdvisor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>预算表的两道结构性保证</b> —— 这两件事都属于"不报错但会错"的类型。
 *
 * <h2>★ 为什么必须单独测（一次真实的重复）</h2>
 *
 * <p>"模式 → 预算"这张表原先被<b>抄了两份</b>：{@code MemoryReadAdvisor}（主链路）
 * 与 {@code ChatMemoryAdapter}（框架边界），都是手写的 {@code Map.of(...)}。
 *
 * <p>后果不是"看着重复"，而是两个具体的失败：
 * <ol>
 *   <li><b>漏配一个模式</b> → {@code budgets.get(新模式)} 返回 {@code null}
 *       → 下一行 {@code .maxTokens()} <b>NPE</b>。而堆栈里看不出"漏配了一个枚举常量"；</li>
 *   <li><b>两处各自演化</b> → 同一个会话在两条路径下拿到<b>不同预算</b>。
 *       这不会报错，只会让某条路径悄悄多带或少带历史。</li>
 * </ol>
 *
 * <p>所以本类分别钉住这两点：
 * <ul>
 *   <li>{@link #coversEveryMode()} —— 建表必须覆盖<b>全部</b>模式（靠遍历 values()，不靠手写）；</li>
 *   <li>{@link #advisorAndAdapterAgreeOnTheSameBudget()} —— ★ <b>两个调用点必须拿到同一份预算</b>。
 *       这条是防止重复被重新引入的<b>回归防线</b>：谁再把表抄一份且数字不一致，这里立刻失败。</li>
 * </ul>
 */
class MemoryBudgetsTest {

    private MemoryProperties properties() {
        MemoryProperties props = new MemoryProperties();
        // 刻意用与默认值不同的数字 —— 这样"读错了配置项"也会被抓到，
        // 而不是碰巧等于默认值而通过
        props.setConversationMaxTokens(1234);
        props.setConversationMaxMessages(7);
        props.setWorkflowMaxTokens(99999);
        props.setWorkflowMaxMessages(88);
        return props;
    }

    // ========================================================================
    // 一、覆盖全部模式（防漏配）
    // ========================================================================

    @Test
    @DisplayName("★ 预算表覆盖【全部】MemoryMode —— 新增模式时不会出现漏配")
    void coversEveryMode() {
        MemoryBudgets budgets = MemoryBudgets.from(properties());

        assertThat(budgets.byMode().keySet())
                .as("★ 建表靠遍历 MemoryMode.values()，所以模式有多少个、表就有多少项。"
                        + "如果这里失败，说明建表改回了手写列举")
                .containsExactlyInAnyOrder(MemoryMode.values());

        // 逐个取一遍，确认 forMode 对每个模式都给得出值（不会 NPE）
        for (MemoryMode mode : MemoryMode.values()) {
            assertThat(budgets.forMode(mode))
                    .as("模式 %s 必须有预算", mode)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("★ 每个模式的数值来自【它自己那两项】配置（防串号）")
    void eachModeReadsItsOwnProperties() {
        MemoryBudgets budgets = MemoryBudgets.from(properties());

        assertThat(budgets.forMode(MemoryMode.CONVERSATION))
                .as("会话模式必须读 conversation-max-*")
                .isEqualTo(new TokenBudget(1234, 7));
        assertThat(budgets.forMode(MemoryMode.WORKFLOW))
                .as("工作流模式必须读 workflow-max-*，而不是复用会话的数值")
                .isEqualTo(new TokenBudget(99999, 88));

        // 两个模式不得相同 —— 相同就意味着"共用一个大上限"那个坑回来了
        assertThat(budgets.forMode(MemoryMode.CONVERSATION))
                .as("阶段 5 修正过的坑：用数量级掩盖歧义（取最大的那个当共用上限）")
                .isNotEqualTo(budgets.forMode(MemoryMode.WORKFLOW));
    }

    @Test
    @DisplayName("★ 空表被拒绝 —— 空表会让「取预算」退化成「随便给一个」")
    void emptyTableIsRejected() {
        assertThatThrownBy(() -> new MemoryBudgets(java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    // ========================================================================
    // 二、★ 两个调用点必须一致（防重复被重新引入）
    // ========================================================================

    /**
     * 记录型记忆实现：把每次 {@code load} 收到的预算记下来。
     *
     * <p>用它就能观察到"调用方到底把哪个预算传下去了" ——
     * 这是不需要读私有字段就能验证两条路径一致的办法。
     */
    private static final class RecordingMemory implements ConversationMemory {
        private final List<TokenBudget> budgetsSeen = new ArrayList<>();

        @Override
        public void append(String conversationId, List<Message> messages) {
            // 本测试不关心写入
        }

        @Override
        public List<Message> load(String conversationId, TokenBudget budget) {
            budgetsSeen.add(budget);
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }

        @Override
        public void delete(String conversationId) {
        }
    }

    /** 链尾：本测试只关心"预算被传下去了"，不关心模型调用。 */
    private static final class Terminator implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return new ChatClientResponse(null, request.context());
        }

        @Override
        public String getName() {
            return "Terminator";
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE;
        }
    }

    @Test
    @DisplayName("★★ 主链路与框架路径对同一模式拿到【同一个】预算 —— 防止重复表被重新引入")
    void advisorAndAdapterAgreeOnTheSameBudget() {
        MemoryProperties props = properties();

        // --- 路径一：框架边界（ChatMemoryAdapter）---
        RecordingMemory adapterMemory = new RecordingMemory();
        ChatMemoryAdapter adapter = new ChatMemoryAdapter(adapterMemory, props);
        for (MemoryMode mode : MemoryMode.values()) {
            adapter.loadFor("conv-" + mode, mode);
        }

        // --- 路径二：主链路（MemoryReadAdvisor，经真实切面链执行）---
        RecordingMemory advisorMemory = new RecordingMemory();
        MemoryReadAdvisor advisor = new MemoryReadAdvisor(advisorMemory, props);
        for (MemoryMode mode : MemoryMode.values()) {
            DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                    .push(advisor)
                    .push(new Terminator())
                    .build()
                    .nextCall(ChatClientRequest.builder()
                            .prompt(new Prompt(List.<Message>of(new UserMessage("在吗"))))
                            .context(ChatMemory.CONVERSATION_ID, "conv-" + mode)
                            .context(MemoryMode.CONTEXT_KEY, mode)
                            .build());
        }

        // ★ 核心断言：两条路径逐模式给出的预算完全一致
        assertThat(advisorMemory.budgetsSeen)
                .as("★ 主链路与框架路径必须用同一份预算表。"
                        + "若这里失败，说明有人把「模式→预算」又抄了一份（或改了其中一处）——"
                        + "那正是 MemoryBudgets 要消灭的重复")
                .isEqualTo(adapterMemory.budgetsSeen);

        // 逐项确认数值本身也对（防止两边同时错成一样）
        assertThat(advisorMemory.budgetsSeen).containsExactly(
                new TokenBudget(1234, 7),      // CONVERSATION
                new TokenBudget(99999, 88));   // WORKFLOW
    }

    @Test
    @DisplayName("★ from() 是纯函数：同一份配置每次得到相同的表，且顺序稳定")
    void fromIsDeterministic() {
        MemoryBudgets first = MemoryBudgets.from(properties());
        MemoryBudgets second = MemoryBudgets.from(properties());

        assertThat(first).isEqualTo(second);
        assertThat(first.byMode().keySet())
                .as("保留枚举声明顺序（LinkedHashMap），启动日志里读起来更顺")
                .containsExactly(MemoryMode.values());
    }
}
