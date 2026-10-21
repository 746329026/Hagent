package com.hagent.app;

import com.hagent.agent.PipelineEvent;

import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.config.AgentLoopProperties;
import com.hagent.config.MemoryProperties;
import com.hagent.memory.TokenBudget;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentPipeline} 的事件流契约 —— <b>阶段 8 新增的核心防线</b>。
 *
 * <h2>★ 它保护的是四条"实测出来、写错不报错"的行为</h2>
 *
 * <ol>
 *   <li><b>每片是增量，Final 必须自己拼接</b> —— 实测真实响应 184 片中
 *       最后一片 text 长度为 0、而全片拼接才是完整回答。
 *       若实现改成"取最后一片"，回答会变成空字符串<b>且不报错</b>；</li>
 *   <li><b>空片必须过滤</b> —— 实测首片是 {@code text=null, reasoning=空串}。
 *       不过滤会往 SSE 里发空事件；</li>
 *   <li><b>思考与回答两段互斥、都要累积</b> —— 前端要分两块渲染；</li>
 *   <li><b>写回发生在 Final 之前，且只写一次</b> —— 否则"前端收到 Final 时历史还没落库"，
 *       或一个请求写两遍。</li>
 * </ol>
 *
 * <p>这些断言用<b>假的 ChatModel</b> 构造出与实测完全相同的分片形状，
 * 因此不需要真实 API Key、不花钱，且可重复。
 */
class AgentPipelineTest {

    // ========================================================================
    // 测试替身
    // ========================================================================

    /** 记录型记忆：记下 load 的预算与 append 的内容。 */
    private static final class RecordingMemory implements ConversationMemory {
        private final List<TokenBudget> budgets = new ArrayList<>();
        private final List<List<Message>> appended = new ArrayList<>();
        private List<Message> historyToReturn = List.of();

        @Override
        public void append(String conversationId, List<Message> messages) {
            appended.add(messages);
        }

        @Override
        public List<Message> load(String conversationId, TokenBudget budget) {
            budgets.add(budget);
            return historyToReturn;
        }

        @Override
        public void clear(String conversationId) {
        }

        @Override
        public void delete(String conversationId) {
        }
    }

    private MemoryProperties memoryProperties() {
        MemoryProperties props = new MemoryProperties();
        props.setChatMaxTokens(5000);
        props.setChatMaxMessages(30);
        props.setWorkflowMaxTokens(50000);
        props.setWorkflowMaxMessages(200);
        return props;
    }

    /**
     * 造一个 pipeline，模型的流式输出由 {@code chunks} 指定（<b>不带工具</b>）。
     *
     * <p>★ 阶段 9 起本构造器需要 {@code chatModel.getOptions()} 是
     * {@code DeepSeekChatOptions}（它要"克隆配置 + 挂工具"，见 AgentPipeline 的启动自检），
     * 所以这里必须真的把它 stub 出来 —— 否则构造期就失败。
     */
    private AgentPipeline pipeline(RecordingMemory memory, List<ChatResponse> chunks) {
        return pipeline(memory, chunks, TestLongTermMemory.empty());
    }

    /** 带长期记忆桩的版本 —— 用来验证"记忆索引确实进了 system"。 */
    private AgentPipeline pipeline(RecordingMemory memory,
                                   List<ChatResponse> chunks,
                                   com.hagent.memory.longterm.LongTermMemoryStore longTermMemory) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.fromIterable(chunks));

        return new AgentPipeline(
                chatModel,
                memory,
                TestToolCallbackProvider.none(),
                TestAudit.disabled(),
                new TraceProperties(),
                new PromptProperties(),
                memoryProperties(),
                new AgentLoopProperties(),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                longTermMemory,
                TestTurn.noop());
    }

    // ========================================================================
    // 六、阶段 11：长期记忆索引进 system
    // ========================================================================

    @Test
    @DisplayName("★★ 长期记忆索引必须进 system 消息，且排在回答约束【之后】")
    void longTermMemoryIndexIsInjectedIntoSystem() {
        String digest = "【长期记忆】\n- 称呼偏好 — 用户希望我称呼他张工";

        // 用"抓模型真正收到的 prompt"比读日志更硬（日志可能被截断）
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        List<Prompt> seen = new java.util.ArrayList<>();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            seen.add(invocation.getArgument(0));
            return Flux.just(textChunk("好"));
        });

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.none(),
                TestAudit.disabled(), new TraceProperties(), new PromptProperties(),
                memoryProperties(),
                new AgentLoopProperties(),
                new ByteArrayResource("【约束内容】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.withIndex(digest),
                TestTurn.noop());

        pipeline.run("我叫什么", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        String system = seen.get(0).getSystemMessage().getText();
        assertThat(system)
                .as("记忆索引没进 system ⇒ 模型根本不知道有这些记忆，等于没做（这是 Q2=A 的全部意义）")
                .contains("称呼偏好")
                .contains("张工");
        assertThat(system.indexOf("【约束内容】"))
                .as("顺序：角色与规则在前，关于用户的背景在后 —— 反过来会让模型先看到'人设'再用规则去纠正")
                .isLessThan(system.indexOf("【长期记忆】"));
    }

    @Test
    @DisplayName("★ 读记忆失败不能拖垮对话 —— 降级成'本轮不注入'")
    void memoryReadFailureDoesNotBreakTheTurn() {
        com.hagent.memory.longterm.LongTermMemoryStore broken =
                org.mockito.Mockito.mock(com.hagent.memory.longterm.LongTermMemoryStore.class);
        when(broken.renderIndexFor(any())).thenThrow(new RuntimeException("磁盘炸了"));

        List<PipelineEvent> events = pipeline(new RecordingMemory(), List.of(textChunk("照常回答")), broken)
                .run("你好", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(events)
                .as("记忆是'让回答更好'的东西，不是'让回答成立'的东西 —— 它坏了不该让这轮 500")
                .anyMatch(PipelineEvent.ModelOutput.Final.class::isInstance);
    }

    /** 模型"配置好的" options —— 必须是真的 DeepSeekChatOptions，否则管线启动自检会拒绝。 */
    private static org.springframework.ai.deepseek.DeepSeekChatOptions deepSeekOptions() {
        return org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                .model("deepseek-flash")
                .build();
    }

    /** 造一片只有思考的响应（模拟实测的"思考段"）。 */
    private ChatResponse reasoningChunk(String reasoning) {
        return chunk(null, reasoning);
    }

    /** 造一片只有正文的响应（模拟实测的"回答段"）。 */
    private ChatResponse textChunk(String text) {
        return chunk(text, null);
    }

    /** 造一片什么都没有的响应（模拟实测的"首片"与"收尾片"）。 */
    private ChatResponse emptyChunk() {
        return chunk(null, null);
    }

    private ChatResponse chunk(String text, String reasoning) {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content(text)
                .reasoningContent(reasoning)
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private List<PipelineEvent> collect(AgentPipeline pipeline) {
        return pipeline.run("提问", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList()
                .block();
    }

    private PipelineEvent.ModelOutput.Final finalOf(List<PipelineEvent> events) {
        return events.stream()
                .filter(PipelineEvent.ModelOutput.Final.class::isInstance)
                .map(PipelineEvent.ModelOutput.Final.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件流里没有 Final：" + events));
    }

    // ========================================================================
    // 一、★★ Final.text 必须自己拼接（本测试最重要的一条）
    // ========================================================================

    @Test
    @DisplayName("★★ Final.text 是【拼接后】的完整回答 —— 不是最后一片")
    void finalTextIsAccumulatedNotLastChunk() {
        // 刻意让最后一片为【空】—— 真实响应就是这样（实测 184 片的最后一片 text 长度 = 0）。
        // 若实现改成"取最后一片"，本断言立刻失败。
        List<ChatResponse> chunks = List.of(
                emptyChunk(),
                textChunk("班级共有"),
                textChunk(" 30 名学生"),
                textChunk("，其中 18 名女生。"),
                emptyChunk());

        PipelineEvent.ModelOutput.Final result = finalOf(collect(pipeline(new RecordingMemory(), chunks)));

        assertThat(result.text())
                .as("实测：最后一片 text 长度为 0，完整回答分散在各片里 —— 不拼接就会得到空串")
                .isEqualTo("班级共有 30 名学生，其中 18 名女生。");
    }

    @Test
    @DisplayName("★★ Final.reasoning 同样是拼接后的完整思考")
    void finalReasoningIsAccumulated() {
        List<ChatResponse> chunks = List.of(
                emptyChunk(),
                reasoningChunk("我们"),
                reasoningChunk("需要回答"),
                reasoningChunk("中文。"),
                textChunk("答案"),
                emptyChunk());

        PipelineEvent.ModelOutput.Final result = finalOf(collect(pipeline(new RecordingMemory(), chunks)));

        assertThat(result.reasoning()).isEqualTo("我们需要回答中文。");
        assertThat(result.text()).isEqualTo("答案");
    }

    // ========================================================================
    // 二、★ 空片被过滤，事件序列严格等于"有内容的分片"
    // ========================================================================

    @Test
    @DisplayName("★ 空片不产出任何事件 —— 首片与收尾片会被丢掉")
    void emptyChunksProduceNoEvents() {
        List<ChatResponse> chunks = List.of(
                emptyChunk(),               // 实测首片就是这样
                reasoningChunk("思考一"),
                textChunk("回答一"),
                emptyChunk());              // 实测收尾片（finishReason=STOP、内容为空）

        List<PipelineEvent> events = collect(pipeline(new RecordingMemory(), chunks));

        assertThat(events)
                .as("★ 阶段 9 起：管线不再发 Retrieved（检索已工具化）；"
                        + "阶段 10 起多 1 个 StepStarted（第 N 步开始，在所有模型事件之前）；"
                        + "阶段 14 起多 1 个 TurnFinished（本轮统计，在 Final 之后）。"
                        + "所以事件 = 1 个 StepStarted + 1 个 Reasoning + 1 个 Token + 1 个 Final"
                        + " + 1 个 TurnFinished。"
                        + "空片若未被过滤，这里会多出两个空事件（前端表现为收到空消息）")
                .hasSize(5)
                .noneMatch(PipelineEvent.Retrieved.class::isInstance)
                .hasAtLeastOneElementOfType(PipelineEvent.ModelOutput.Final.class)
                .hasAtLeastOneElementOfType(PipelineEvent.TurnFinished.class);

        List<PipelineEvent.ModelOutput> modelEvents = events.stream()
                .filter(PipelineEvent.ModelOutput.class::isInstance)
                .map(PipelineEvent.ModelOutput.class::cast)
                .toList();
        assertThat(modelEvents).hasSize(3);
        assertThat(modelEvents.get(0)).isInstanceOf(PipelineEvent.ModelOutput.Reasoning.class);
        assertThat(modelEvents.get(1)).isInstanceOf(PipelineEvent.ModelOutput.Token.class);
        assertThat(modelEvents.get(2)).isInstanceOf(PipelineEvent.ModelOutput.Final.class);
    }

    @Test
    @DisplayName("★ 思考先于回答 —— 实测两段互斥且思考段在前")
    void reasoningComesBeforeTokens() {
        List<ChatResponse> chunks = List.of(
                reasoningChunk("先想"),
                reasoningChunk("再想"),
                textChunk("再说"));

        List<PipelineEvent.ModelOutput> modelEvents = collect(pipeline(new RecordingMemory(), chunks)).stream()
                .filter(PipelineEvent.ModelOutput.class::isInstance)
                .map(PipelineEvent.ModelOutput.class::cast)
                .toList();

        // ★ 注意阶段 9 的语义：这三个 chunk 属于【同一次】模型调用（= 同一步循环），
        //   所以事件是 思考、思考、回答、Final（Final 由这一步收尾时发出）。
        assertThat(modelEvents).hasSize(4);
        assertThat(modelEvents.subList(0, 2))
                .allMatch(e -> e instanceof PipelineEvent.ModelOutput.Reasoning);
        assertThat(modelEvents.get(2)).isInstanceOf(PipelineEvent.ModelOutput.Token.class);
        assertThat(modelEvents.get(3)).isInstanceOf(PipelineEvent.ModelOutput.Final.class);
    }

    // ========================================================================
    // 三、★ 写回：发生在 Final 之前，且恰好一次
    // ========================================================================

    @Test
    @DisplayName("★★ 写回恰好一次，内容是【问 + 答】，且发生在 Final 之前")
    void writeBackHappensOnceBeforeFinal() {
        RecordingMemory memory = new RecordingMemory();
        List<ChatResponse> chunks = List.of(textChunk("回答正文"));

        List<PipelineEvent> events = collect(pipeline(memory, chunks));

        assertThat(memory.appended)
                .as("一个请求只写一次 —— 管道是直线，不存在【被循环跑 N 次】的可能")
                .hasSize(1);

        List<Message> turn = memory.appended.get(0);
        assertThat(turn).hasSize(2);
        assertThat(turn.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(turn.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(turn.get(1).getText()).isEqualTo("回答正文");

        // Final 必须仍然产出（写回不能把 Final 吞掉）
        assertThat(finalOf(events).text()).isEqualTo("回答正文");
    }

    @Test
    @DisplayName("★ 模型只思考没作答时，仍然写回【问】并产出 Final")
    void thoughtOnlyStillWritesBackAndFinishes() {
        RecordingMemory memory = new RecordingMemory();
        List<ChatResponse> chunks = List.of(reasoningChunk("只想不说"));

        List<PipelineEvent> events = collect(pipeline(memory, chunks));

        assertThat(memory.appended).hasSize(1);
        assertThat(memory.appended.get(0))
                .as("用户确实问了，记录层不该丢这一问")
                .hasSize(1);

        PipelineEvent.ModelOutput.Final result = finalOf(events);
        assertThat(result.text()).isEmpty();
        assertThat(result.reasoning()).isEqualTo("只想不说");
    }

    // ========================================================================
    // 四、检索事件
    // ========================================================================

    // ========================================================================
    // ★ 阶段 9 删除了两个用例：retrievedEventIsEmittedFirst / retrievedEventIsEmittedEvenWhenEmpty
    //
    //   它们断言"管线把检索结果作为第一个事件发出"。阶段 9 把检索改成
    //   retrieveKnowledge 工具之后，管线【不再自己做检索】，因此也不再发
    //   PipelineEvent.Retrieved —— 这个事件改由【工具】发出（阶段 9.2 接线）。
    //
    //   ⇒ 对应断言会在工具侧重建（工具既能返回文本给模型，也能发出带结构化片段的
    //     Retrieved 事件给前端）。这与 PromptBuilderTest 里那三条同一个道理：
    //     测试跟着职责走。
    // ========================================================================

    @Test
    @DisplayName("★ 管线不再自己发 Retrieved（检索已工具化）")
    void pipelineNoLongerEmitsRetrievedItself() {
        List<PipelineEvent> events = collect(pipeline(new RecordingMemory(), List.of(textChunk("答"))));

        assertThat(events)
                .as("管线现在只产模型相关事件；检索事件由工具发出。"
                        + "若这里出现 Retrieved，说明有人把无条件检索加回来了")
                .noneMatch(PipelineEvent.Retrieved.class::isInstance)
                .isNotEmpty();
    }

    // ========================================================================
    // 五、参数兜底
    // ========================================================================

    @Test
    @DisplayName("★ 空会话 ID 落到兜底会话；null 身份落到匿名（最小权限）")
    void blankConversationIdAndNullIdentityFallBack() {
        RecordingMemory memory = new RecordingMemory();
        AgentPipeline pipeline = pipeline(memory, List.of(textChunk("答")));

        List<PipelineEvent> events = pipeline
                .run("提问", "   ", MemoryMode.CHAT, null)
                .collectList()
                .block();

        assertThat(events).isNotEmpty();
        assertThat(memory.budgets)
                .as("兜底不能让流程崩掉，也不能让预算变成 null")
                .hasSize(1);
    }
}
