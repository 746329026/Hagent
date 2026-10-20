package com.hagent.app;

import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.config.AgentLoopProperties;
import com.hagent.config.MemoryProperties;
import com.hagent.memory.TokenBudget;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>"模式 → 预算"的唯一出处与它的两条保证</b>。
 *
 * <h2>★ 本测试是从 {@code MemoryBudgetsTest} 重建来的（阶段 8）</h2>
 *
 * <p>原测试的核心断言是"<b>主链路与框架边界两条路径拿到同一份预算</b>"，
 * 用于防止"模式→预算"这张表被抄成两份而各自演化。
 * 阶段 8 删掉了框架边界（{@code ChatMemoryAdapter} 与 {@code MemoryReadAdvisor}），
 * 那条断言的两个比较对象<b>同时消失</b> —— 继续比较就是拿一个没坏的东西跟坏掉的东西比，
 * 两边都坏时永远相等（这正是"测试跑过 ≠ 是有效防线"）。
 *
 * <p>所以重建后的做法是：<b>比较"实际传下去的预算"与"期望值"</b>。
 * 它比原断言更强 —— 原断言只能发现"两处不一致"，
 * 而本测试能发现"两处一致地错"（例如两个模式都读了同一项配置）。
 *
 * <h2>★ 它保护的两件事（都是"不报错但会错"的类型）</h2>
 *
 * <ol>
 *   <li><b>漏配一个模式</b>：{@code AgentPipeline#budgetOf} 用穷尽 {@code switch}
 *       且不写 {@code default}，所以新增 {@link MemoryMode} 会<b>编译失败</b>。
 *       本测试的第 1 条断言把这个性质钉住 —— 它遍历 {@code values()}，
 *       若有人改回 {@code Map.of(...)} 手写列举，这里立刻失败；</li>
 *   <li><b>读错配置项</b>（串号）：两个模式若都读 {@code chat-max-*}，
 *       表现是"工作流预算静默等于 chat 预算"，模型历史短了 10 倍而<b>没有任何报错</b>。
 *       第 2 条断言用<b>刻意不同</b>的数字抓住它。</li>
 * </ol>
 */
class ChatBudgetTest {

    /** 刻意用与默认值不同的数字 —— 这样"读错了配置项"也会被抓到，而不是碰巧等于默认值而通过。 */
    private MemoryProperties properties() {
        MemoryProperties props = new MemoryProperties();
        props.setChatMaxTokens(1234);
        props.setChatMaxMessages(7);
        props.setWorkflowMaxTokens(99999);
        props.setWorkflowMaxMessages(88);
        return props;
    }

    /**
     * 造一个只用来观察"预算被传下去了"的 pipeline。
     *
     * <p>{@code chatModel.stream} 被 stub 成<b>一片带回答的响应</b>（而不是空流）——
     * 空流会让管道产出空 Final 且不报错，那样测试就分不清
     * "没走到模型调用"与"模型返回空"。给一片真实形状的响应最省事。
     */
    private AgentPipeline pipeline(MemoryProperties props, ConversationMemory memory) {
        ChatModel chatModel = mock(ChatModel.class);
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("好")
                .reasoningContent(null)
                .build();
        ChatResponse chunk = new ChatResponse(List.of(new Generation(message)));
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk));

        return new AgentPipeline(
                chatModel,
                memory,
                TestToolCallbackProvider.none(),
                TestAudit.disabled(),
                new TraceProperties(),
                new PromptProperties(),
                props,
                new AgentLoopProperties(),
                new ByteArrayResource("测试用约束".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TestLongTermMemory.empty());
    }

    /**
     * 模型"配置好的" options。
     *
     * <p>★ 阶段 9 起管线构造器要求 {@code chatModel.getOptions()} 是
     * {@code DeepSeekChatOptions}（它要"克隆配置 + 挂工具"，见 AgentPipeline 的启动自检），
     * 所以测试必须真的 stub 出来，否则构造期就失败。
     */
    private static org.springframework.ai.deepseek.DeepSeekChatOptions deepSeekOptions() {
        return org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                .model("deepseek-flash")
                .build();
    }

    /** 记录型记忆：把每次 {@code load} 收到的预算记下来。 */
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

    private void runOnce(AgentPipeline pipeline, MemoryMode mode) {
        // run 返回冷流：必须订阅才会执行到 memory.load。
        // 用 blockLast() 而不是 subscribe()，保证断言时执行已经完成。
        pipeline.run("在吗", "conv-" + mode, mode, UserContext.ANONYMOUS).blockLast();
    }

    // ========================================================================
    // 一、每个模式都要有预算，且必须真的被传下去
    // ========================================================================

    @Test
    @DisplayName("★ 【每个】MemoryMode 都会把预算传给记忆层 —— 新增模式时不会漏")
    void everyModePassesABudget() {
        RecordingMemory memory = new RecordingMemory();
        AgentPipeline pipeline = pipeline(properties(), memory);

        for (MemoryMode mode : MemoryMode.values()) {
            runOnce(pipeline, mode);
        }

        assertThat(memory.budgetsSeen)
                .as("★ 遍历 MemoryMode.values()：模式有多少个，就应该观察到多少个预算。"
                        + "若这里失败，说明 budgetOf 的 switch 漏了分支或被改成了手写列举")
                .hasSize(MemoryMode.values().length)
                .doesNotContainNull();
    }

    // ========================================================================
    // 二、★ 每个模式读【它自己那两项】配置（防串号）
    // ========================================================================

    @Test
    @DisplayName("★★ 每个模式的预算来自它自己那两项配置 —— 防「两个模式读同一项」")
    void eachModeReadsItsOwnProperties() {
        MemoryProperties props = properties();

        // 会话模式
        RecordingMemory chatMemory = new RecordingMemory();
        runOnce(pipeline(props, chatMemory), MemoryMode.CHAT);

        assertThat(chatMemory.budgetsSeen)
                .as("chat 模式必须读 chat-max-*")
                .containsExactly(new TokenBudget(1234, 7));

        // 工作流模式
        RecordingMemory workflowMemory = new RecordingMemory();
        runOnce(pipeline(props, workflowMemory), MemoryMode.WORKFLOW);

        assertThat(workflowMemory.budgetsSeen)
                .as("工作流模式必须读 workflow-max-*，而不是复用会话的数值")
                .containsExactly(new TokenBudget(99999, 88));
    }

    @Test
    @DisplayName("★ 两个模式的预算不得相同 —— 相同就意味着「共用一个大上限」那个坑回来了")
    void modesDoNotShareOneBudget() {
        MemoryProperties props = properties();

        RecordingMemory chatMemory = new RecordingMemory();
        RecordingMemory workflowMemory = new RecordingMemory();
        runOnce(pipeline(props, chatMemory), MemoryMode.CHAT);
        runOnce(pipeline(props, workflowMemory), MemoryMode.WORKFLOW);

        assertThat(chatMemory.budgetsSeen.get(0))
                .as("阶段 5 修正过的坑：用数量级掩盖歧义（取最大的那个当共用上限）")
                .isNotEqualTo(workflowMemory.budgetsSeen.get(0));
    }

    // ========================================================================
    // 三、参数校验（保留自 TokenBudget 自身的契约）
    // ========================================================================

    @Test
    @DisplayName("★ 非正数的预算被拒绝 —— 0 或负数会让「截断」退化成「什么都留」或「什么都丢」")
    void nonPositiveBudgetIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TokenBudget(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTokens");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TokenBudget(100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessages");
    }
}
