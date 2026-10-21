package com.hagent.app;

import com.hagent.retrieval.KnowledgeSearch;

import com.hagent.agent.PipelineEvent;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>工具循环的行为契约</b> —— 阶段 9.1 的核心防线。
 *
 * <h2>★ 它守的四件事（都是"写错了不报错"的类型）</h2>
 *
 * <ol>
 *   <li><b>循环真的会"多走一步"</b>：模型请求工具 → 执行 → 把结果回灌 → <b>再调模型</b>。
 *       若忘了回灌（或回灌后没再调模型），表现为"模型说了一句『我要查一下』就结束了"，
 *       <b>用户永远拿不到答案，而没有任何报错</b>；</li>
 *   <li><b>循环会正常结束</b>：模型不再请求工具时就收尾，
 *       而不是无限地再调一次；</li>
 *   <li>★ <b>步数上限真的会拦住绕圈的模型</b>：这一条尤其重要 ——
 *       它是唯一"由我们控制"的出口，而<b>每多走一步都是一次计费的模型调用</b>；</li>
 *   <li><b>工具结果确实被送给了模型</b>：断言第 2 次调用的提示词里有 {@code ToolResponseMessage}
 *       （这是"工具真的被执行并回流"的直接证据，而不是只看最终文本猜）。</li>
 * </ol>
 *
 * <h2>测试怎么做到"确定"</h2>
 *
 * <p>用假的 {@code ChatModel} 按<b>脚本</b>逐次返回：第 1 次要求调工具、第 2 次给最终答案。
 * 用假的 {@link ToolCallback} 记录被调用次数并返回固定结果。
 * 两者都不碰真实 API，因此可重复、不花钱。
 */
class AgentPipelineToolLoopTest {

    // ========================================================================
    // 测试替身
    // ========================================================================

    /** 记录型记忆。 */
    private static final class RecordingMemory implements ConversationMemory {
        private final List<List<Message>> appended = new ArrayList<>();

        @Override
        public void append(String conversationId, List<Message> messages) {
            appended.add(messages);
        }

        @Override
        public List<Message> load(String conversationId, TokenBudget budget) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
        }

        @Override
        public void delete(String conversationId) {
        }
    }

    /** 记录被调用次数的假工具。 */
    private static final class FakeTool {
        private final String name;
        private final String result;
        private final AtomicInteger calls = new AtomicInteger();

        FakeTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        ToolCallback asCallback() {
            return new ToolCallback() {
                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return DefaultToolDefinition.builder()
                            .name(name)
                            .description("测试用假工具：" + name)
                            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                            .build();
                }

                @Override
                public String call(String toolInput) {
                    calls.incrementAndGet();
                    return result;
                }
            };
        }
    }

    /** 预算（历史能有多长）—— 与步数上限无关，所以这里不再带 maxSteps。 */
    private MemoryProperties memoryProperties() {
        MemoryProperties props = new MemoryProperties();
        props.setChatMaxTokens(5000);
        props.setChatMaxMessages(30);
        props.setWorkflowMaxTokens(50000);
        props.setWorkflowMaxMessages(200);
        return props;
    }

    /** 步数上限（循环跑几轮）—— 阶段 12 起它是独立的一份配置，且按模式分档。 */
    private AgentLoopProperties agentLoopProperties(int chatMaxSteps) {
        AgentLoopProperties props = new AgentLoopProperties();
        props.setChatMaxSteps(chatMaxSteps);
        props.setWorkflowMaxSteps(chatMaxSteps * 2);
        return props;
    }

    private static DeepSeekChatOptions deepSeekOptions() {
        return DeepSeekChatOptions.builder().model("deepseek-flash").build();
    }

    /** 一片"要求调用工具"的响应。 */
    private ChatResponse toolCallChunk(String toolName, String args) {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("")
                .reasoningContent("我应该调用 " + toolName)
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 一片"最终回答"的响应（无工具调用）。 */
    private ChatResponse finalChunk(String text) {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content(text)
                .reasoningContent("想清楚了")
                .toolCalls(List.of())
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private PipelineEvent.ModelOutput.Final finalOf(List<PipelineEvent> events) {
        return events.stream()
                .filter(PipelineEvent.ModelOutput.Final.class::isInstance)
                .map(PipelineEvent.ModelOutput.Final.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件流里没有 Final：" + events));
    }

    // ========================================================================
    // 一、★★ 循环真的会多走一步（本测试最重要的一条）
    // ========================================================================

    @Test
    @DisplayName("★★ 模型请求工具 → 执行 → 结果回灌 → 再调模型 → 拿到最终答案")
    void loopFeedsToolResultBackAndCallsModelAgain() {
        FakeTool weather = new FakeTool("getWeather", "杭州今天晴，26 度");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());

        AtomicInteger modelCalls = new AtomicInteger();
        List<Prompt> captured = new ArrayList<>();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return modelCalls.getAndIncrement() == 0
                    ? Flux.just(toolCallChunk("getWeather", "{}"))
                    : Flux.just(finalChunk("杭州今天晴，26 度。"));
        });

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(weather.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("杭州今天天气怎么样", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(modelCalls.get())
                .as("★ 模型必须被调用【两次】：一次请求工具、一次给答案。"
                        + "若只调用一次，说明循环没有继续 —— 用户会只看到『我要查一下』")
                .isEqualTo(2);
        assertThat(weather.calls.get())
                .as("工具必须被真的执行一次")
                .isEqualTo(1);
        assertThat(finalOf(events).text())
                .as("最终答案来自【第 2 次】模型调用（工具结果之后的那个）")
                .isEqualTo("杭州今天晴，26 度。");

        // ★ 直接证据：第 2 次调用的提示词里必须有工具结果
        List<Message> secondPrompt = captured.get(1).getInstructions();
        assertThat(secondPrompt)
                .as("第 2 次提示词必须含 ToolResponseMessage —— 这是『结果真的回流了』的直接证据，"
                        + "而不是靠最终文本反推")
                .anyMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    @DisplayName("★ 模型不请求工具时只调用一次 —— 循环不会白走")
    void noToolCallMeansSingleModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger modelCalls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            return Flux.just(finalChunk("不需要工具"));
        });

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.none(),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("你好", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(modelCalls.get())
                .as("没有工具请求就【不该】再调一次 —— 那是一次白花的模型调用")
                .isEqualTo(1);
        assertThat(finalOf(events).text()).isEqualTo("不需要工具");
    }

    // ========================================================================
    // 二、★★ 步数上限（唯一由我们控制的出口）
    // ========================================================================

    @Test
    @DisplayName("★★ 模型一直请求工具 → 达到 max-steps 就收尾，不会无限循环")
    void maxStepsStopsARunawayLoop() {
        FakeTool loopTool = new FakeTool("loopingTool", "结果");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger modelCalls = new AtomicInteger();
        // ★ 每次都给"要调工具"，模拟绕圈的模型
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            return Flux.just(toolCallChunk("loopingTool", "{}"));
        });

        int maxSteps = 3;
        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(loopTool.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(maxSteps),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("一直绕圈", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(modelCalls.get())
                .as("★ 模型调用次数必须【恰好】等于 max-steps(%d)："
                        + "每多一次都是一次计费调用。若这里失败，说明防御出口没生效 ——"
                        + "而现实中那意味着账单失控", maxSteps)
                .isEqualTo(maxSteps);
        assertThat(events)
                .as("即使绕圈，也必须产出 Final 收尾 —— 不能让调用方拿不到任何结果")
                .anyMatch(PipelineEvent.ModelOutput.Final.class::isInstance);
    }

    @Test
    @DisplayName("★★ 步数上限【按模式分档】：chat 与 workflow 各走各的上限")
    void maxStepsIsPerMode() {
        // 辅助方法造的是 chat=2 / workflow=4（见 agentLoopProperties(int)）
        int chatCalls = modelCallsUntilFinish(MemoryMode.CHAT);
        int workflowCalls = modelCallsUntilFinish(MemoryMode.WORKFLOW);

        assertThat(chatCalls)
                .as("chat 模式必须用 hagent.agent.chat-max-steps(%d)", 2)
                .isEqualTo(2);
        assertThat(workflowCalls)
                .as("★ workflow 模式必须用 hagent.agent.workflow-max-steps(%d)。"
                        + "若这里也是 2，说明 maxStepsOf 的 switch 两档取到了同一个字段 —— "
                        + "而这正是『两档分开』这件事唯一会被抓到的位置", 4)
                .isEqualTo(4);
    }

    /**
     * 让模型一直请求工具（模拟绕圈），返回"到收尾为止发生了几次模型调用"。
     *
     * <p>★ 必须是**端到端**的（真的跑一遍 {@code run()}），而不是反射去调
     * {@code maxStepsOf} —— 那个方法是 private，测它等于测实现细节；
     * 而"哪一档真的被用上"只有跑完一条流才看得见。
     */
    private int modelCallsUntilFinish(MemoryMode mode) {
        FakeTool loopTool = new FakeTool("loopingTool", "又是这个结果");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger modelCalls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            return Flux.just(toolCallChunk("loopingTool", "{}"));
        });

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(loopTool.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(2),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("一直绕圈", "conv-1", mode, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(events)
                .as("即使触顶，也必须产出 Final 收尾")
                .anyMatch(PipelineEvent.ModelOutput.Final.class::isInstance);
        return modelCalls.get();
    }

    @Test
    @DisplayName("★ 达到上限时用【已累积的内容】收尾，而不是丢掉（用户已经等到了）")
    void maxStepsStillReturnsAccumulatedText() {
        FakeTool tool = new FakeTool("t", "r");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                // 既有正文又有工具请求：正文要留下来
                Flux.just(new ChatResponse(List.of(new Generation(
                        DeepSeekAssistantMessage.builder()
                                .content("我先查一下。")
                                .reasoningContent("想想")
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "c", "function", "t", "{}")))
                                .build())))));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.of(tool.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(2),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("问", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(finalOf(events).text())
                .as("超限收尾时要把已经流出来的正文带上 —— 丢掉它等于让用户白等")
                .isEqualTo("我先查一下。我先查一下。");
    }

    // ========================================================================
    // 四、★ 工具事件：ToolCall 必须先于 ToolResult（前端要有"正在做"的反馈）
    // ========================================================================

    @Test
    @DisplayName("★★ 事件顺序：ToolCall 先、ToolResult 后，且顺序恰好一一对应")
    void toolCallEventComesBeforeToolResult() {
        FakeTool weather = new FakeTool("getWeather", "杭州今天晴，26 度");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("getWeather", "{\"city\":\"杭州\"}"))
                        : Flux.just(finalChunk("今天晴。")));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.of(weather.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("杭州天气", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        List<PipelineEvent.ModelOutput> modelEvents = events.stream()
                .filter(PipelineEvent.ModelOutput.class::isInstance)
                .map(PipelineEvent.ModelOutput.class::cast)
                .toList();

        PipelineEvent.ModelOutput.ToolCall call = modelEvents.stream()
                .filter(PipelineEvent.ModelOutput.ToolCall.class::isInstance)
                .map(PipelineEvent.ModelOutput.ToolCall.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 ToolCall 事件：" + modelEvents));
        PipelineEvent.ModelOutput.ToolResult result = modelEvents.stream()
                .filter(PipelineEvent.ModelOutput.ToolResult.class::isInstance)
                .map(PipelineEvent.ModelOutput.ToolResult.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 ToolResult 事件：" + modelEvents));

        assertThat(call.name()).isEqualTo("getWeather");
        assertThat(call.arguments()).as("参数是模型生成的 JSON 原文").contains("杭州");
        assertThat(result.name()).isEqualTo("getWeather");
        assertThat(result.result()).as("结果文本就是模型看到的那个").contains("26 度");
        assertThat(result.id())
                .as("请求与结果必须能配对（前端据此把一次调用渲染成一行）")
                .isEqualTo(call.id());

        assertThat(modelEvents.indexOf(call))
                .as("★ ToolCall 必须排在 ToolResult【之前】—— 工具执行可能很慢，"
                        + "先发『开始』用户才有『正在查…』的反馈；"
                        + "若两者顺序反了，前端只能在结果出来后才显示这次调用")
                .isLessThan(modelEvents.indexOf(result));
    }

    @Test
    @DisplayName("★★ 检索工具发出 Retrieved 事件 —— 出处是结构化数据，不经过文本解析")
    void retrievalToolEmitsRetrievedEvent() {
        // 用【真实】的 KnowledgeRetrievalTool，而不是假工具：
        // 这条要验的正是"工具从 ToolContext 拿到事件出口并发出 Retrieved"。
        com.hagent.retrieval.KnowledgeSearch retrieval =
                mock(com.hagent.retrieval.KnowledgeSearch.class);
        com.hagent.retrieval.RetrievedChunk chunk = new com.hagent.retrieval.RetrievedChunk(
                "员工每年享有五天带薪年假", "员工手册", "员工手册", "假期管理", 0,
                com.hagent.document.AccessLevel.INTERNAL, 0.8,
                com.hagent.retrieval.RelevanceBand.HIGH);
        when(retrieval.retrieve(any(), any())).thenReturn(List.of(chunk));

        ToolCallback[] callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider
                .builder()
                .toolObjects(new com.hagent.tool.KnowledgeRetrievalTool(retrieval))
                .build()
                .getToolCallbacks();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("retrieveKnowledge", "{\"query\":\"年假几天\"}"))
                        : Flux.just(finalChunk("五天。")));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.of(callbacks),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("年假几天", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        PipelineEvent.Retrieved retrieved = events.stream()
                .filter(PipelineEvent.Retrieved.class::isInstance)
                .map(PipelineEvent.Retrieved.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "检索工具没有发出 Retrieved 事件 —— 前端将无法显示『参考了哪几篇』。"
                                + "事件流：" + events));

        assertThat(retrieved.chunks()).containsExactly(chunk);
        assertThat(retrieved.query())
                .as("外传查询串，让前端与排查都能确认『检索用的是什么问题』"
                        + "（它可能已被模型改写得更明确）")
                .isEqualTo("年假几天");
    }

    @Test
    @DisplayName("★ 检索被关掉（0 个工具）时不产生任何工具事件")
    void noToolsMeansNoToolEvents() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(finalChunk("纯对话")));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(), TestToolCallbackProvider.none(),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("你好", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(events)
                .noneMatch(PipelineEvent.ModelOutput.ToolCall.class::isInstance)
                .noneMatch(PipelineEvent.ModelOutput.ToolResult.class::isInstance)
                .noneMatch(PipelineEvent.Retrieved.class::isInstance);
    }

    // ========================================================================
    // 五、★ 审计（阶段 9.3）：工具调用必须留下可追溯的记录
    // ========================================================================

    @Test
    @DisplayName("★★ 每次工具调用写一条审计：会话 / 步序 / 工具名 / 参数 / 结果")
    void toolInvocationsAreAudited() {
        FakeTool weather = new FakeTool("getWeather", "杭州今天晴，26 度");
        TestAudit.RecordingAudit audit = TestAudit.recording();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("getWeather", "{\"city\":\"杭州\"}"))
                        : Flux.just(finalChunk("今天晴。")));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(weather.asCallback()),
                audit,
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        pipeline.run("杭州天气", "conv-9", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(audit.recorded())
                .as("一次工具调用 = 一条审计记录。这是『事后能查』的唯一载体 —— "
                        + "若这里是空的，说明审计没落库，而出事时才发现的代价很高")
                .hasSize(1);

        var record = audit.recorded().get(0);
        assertThat(record.conversationId()).isEqualTo("conv-9");
        assertThat(record.stepIndex()).as("第 1 步（该工具是本次对话的第一次工具调用）").isEqualTo(1);
        assertThat(record.toolName()).isEqualTo("getWeather");
        assertThat(record.arguments()).as("参数原文要留档（审计要能回答『它被喂了什么』）").contains("杭州");
        assertThat(record.result()).as("结果原文要留档（模型看到的同一份）").contains("26 度");
        // ★ 阶段 14：审计必须能回答"谁、哪一轮" —— 否则它只是操作日志
        assertThat(record.userId())
                .as("主体。缺了它，'上周三这个人做了什么'永远答不出来")
                .isEqualTo(UserContext.ANONYMOUS.userId());
        assertThat(record.turnId())
                .as("轮次标识要与 chat_turn.turn_id 同值，否则无法与回合账对账")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★ 审计被关掉时：工具照常执行、对话照常完成，只是没有审计记录")
    void auditDisabledStillRunsToolsButRecordsNothing() {
        FakeTool weather = new FakeTool("getWeather", "晴");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("getWeather", "{}"))
                        : Flux.just(finalChunk("今天晴。")));

        // TestAudit.disabled() 的 getIfAvailable() 返回 null —— 等价于
        // hagent.tool.audit.enabled=false（该 Bean 不存在）
        AgentPipeline pipeline = new AgentPipeline(
                chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(weather.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        List<PipelineEvent> events = pipeline
                .run("天气", "conv-8", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(weather.calls.get())
                .as("★ 关掉审计【不能】影响功能：工具必须照常执行")
                .isEqualTo(1);
        assertThat(finalOf(events).text())
                .as("对话必须照常完成 —— 审计是可关闭的旁路，不是依赖")
                .isEqualTo("今天晴。");
    }

    // ========================================================================
    // 三、工具活动不进会话记忆（跨轮不注入那条决定的落点）
    // ========================================================================

    @Test
    @DisplayName("★★ 写回记忆的只有【问 + 最终答】，工具消息不进记忆")
    void toolMessagesAreNotWrittenToMemory() {
        FakeTool tool = new FakeTool("getWeather", "晴");
        RecordingMemory memory = new RecordingMemory();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("getWeather", "{}"))
                        : Flux.just(finalChunk("今天晴。")));

        AgentPipeline pipeline = new AgentPipeline(
                chatModel, memory, TestToolCallbackProvider.of(tool.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());

        pipeline.run("天气", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(memory.appended).hasSize(1);
        List<Message> turn = memory.appended.get(0);
        assertThat(turn)
                .as("★ 只写两条：用户提问 + 最终回答。工具活动另有归宿（审计表），"
                        + "若这里出现 ToolResponseMessage，说明有人把工具消息写进了 "
                        + "用户可见的对话记录 —— 用户会在聊天窗口看到模型的『手机屏幕』")
                .hasSize(2)
                .noneMatch(ToolResponseMessage.class::isInstance);
        assertThat(turn.get(0).getText()).isEqualTo("天气");
        assertThat(turn.get(1).getText()).isEqualTo("今天晴。");
    }

    // ========================================================================
    // 六、阶段 10：步骤骨架（StepStarted）与思考的分步落库
    // ========================================================================

    /** 造一个"第 1 步调工具、第 2 步给答案"的模型。 */
    private ChatModel twoStepModel(AtomicInteger modelCalls) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                modelCalls.getAndIncrement() == 0
                        ? Flux.just(toolCallChunk("getWeather", "{}"))
                        : Flux.just(finalChunk("杭州今天晴，26 度。")));
        return chatModel;
    }

    private AgentPipeline twoStepPipeline(ChatModel chatModel, RecordingMemory memory) {
        return new AgentPipeline(
                chatModel, memory, TestToolCallbackProvider.of(new FakeTool("getWeather", "杭州今天晴").asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop());
    }

    @Test
    @DisplayName("★★ 两步回合发两个 StepStarted，且都在同一步的思考之前")
    void stepEventsMarkEachModelCall() {
        List<PipelineEvent> events = twoStepPipeline(twoStepModel(new AtomicInteger()), new RecordingMemory())
                .run("杭州今天天气怎么样", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        List<Integer> steps = events.stream()
                .filter(PipelineEvent.StepStarted.class::isInstance)
                .map(PipelineEvent.StepStarted.class::cast)
                .map(PipelineEvent.StepStarted::step)
                .toList();

        assertThat(steps)
                .as("★ 模型被调用两次 ⇒ 必须有两个 StepStarted(1)(2)。前端靠它把工具调用归到"
                        + "同一步下；没有它，『一步调两个工具』与『两步各调一个』完全无法区分")
                .containsExactly(1, 2);

        // ★ 顺序：StepStarted 必须在同一步的第一个模型事件之前
        int firstStep = events.indexOf(new PipelineEvent.StepStarted(1));
        int firstReasoning = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof PipelineEvent.ModelOutput.Reasoning) {
                firstReasoning = i;
                break;
            }
        }
        assertThat(firstStep)
                .as("★ StepStarted 必须【先于】任何 Reasoning —— 模型思考的十几秒里若没有它，"
                        + "前端面板是空的，用户分不清『在想』和『卡住』")
                .isNotNegative()
                .isLessThan(firstReasoning);
    }

    @Test
    @DisplayName("★★ 思考按步落库：第 1 步不带标记，第 2 步的标记带上『上一步调了什么工具』")
    void reasoningIsPersistedWithStepMarkers() {
        RecordingMemory memory = new RecordingMemory();
        List<PipelineEvent> events = twoStepPipeline(twoStepModel(new AtomicInteger()), memory)
                .run("杭州今天天气怎么样", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        List<Message> turn = memory.appended.get(0);
        String trace = com.hagent.memory.MessageCodec.reasoningOf(turn.get(1));

        assertThat(trace)
                .as("★ 落库形态：第 1 步的思考（无标记）+ 标记行 + 第 2 步的思考。"
                        + "工具名挂在『下一步的标记』上 —— 因为工具是在第 1 步【结束之后】才执行的，"
                        + "而第 1 步按约定不写标记，挂在自己那一步会让它彻底丢失")
                .isEqualTo("我应该调用 getWeather\n\n@@step:2 tool:getWeather\n想清楚了");

        assertThat(finalOf(events).reasoning())
                .as("★ 而 Final.reasoning 是【不带标记】的纯文本：它给 /chat 阻塞接口用，"
                        + "那里没有『步』这个概念，塞进标记只会让调用方去解析它")
                .isEqualTo("我应该调用 getWeather想清楚了");

        // 反向解析：历史回放拿到的结构与落库文本对得上
        assertThat(com.hagent.memory.ReasoningTrace.parse(trace))
                .hasSize(2)
                .extracting(com.hagent.memory.ReasoningTrace.Section::step)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("★ 单步回合（没调工具）落库就是纯思考，一个标记都没有")
    void singleStepReasoningHasNoMarker() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(finalChunk("不需要工具")));

        RecordingMemory memory = new RecordingMemory();
        new AgentPipeline(chatModel, memory, TestToolCallbackProvider.none(), TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop())
                .run("你好", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(com.hagent.memory.MessageCodec.reasoningOf(memory.appended.get(0).get(1)))
                .as("★ 绝大多数回合都是单步（没调工具），它们的思考落库必须是【干净的一段话】——"
                        + "否则每一行历史里都会多一行格式噪音")
                .isEqualTo("想清楚了");
    }

    @Test
    @DisplayName("★★ 工具执行【不允许】跑在 Reactor 的非阻塞线程上 —— 否则真机上检索必崩")
    void toolExecutionMustNotRunOnNonBlockingThread() {
        // ★★ 这条断言来自一次真机事故（阶段 10 联调）：接真实 WebClient 时，
        //    模型流的元素在 reactor-http-nio-*（Netty 事件循环）上发出，
        //    于是 handleToolCalls 也在那条线程上被订阅 ——
        //    而检索工具内部是【阻塞】调用（Ollama embedding 的 RestClient 底层要 Mono.block()），
        //    Reactor 会直接拒绝在非阻塞线程上 block：
        //      IllegalStateException: block()/blockFirst()/blockLast() are blocking,
        //      which is not supported in thread reactor-http-nio-4
        //    结果每次检索都抛异常 → 被降级成"没命中" → 模型回答"知识库中未找到相关内容"。
        //
        // ★ 为什么 279 个用例全绿还是漏了：所有假模型都用 Flux.just(...)，
        //    元素在调用线程（允许阻塞）上发出。这条用例刻意用 publishOn(Schedulers.parallel())
        //    模拟"元素来自非阻塞线程"，把那条边补上。
        java.util.concurrent.atomic.AtomicBoolean nonBlocking =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> threadName =
                new java.util.concurrent.atomic.AtomicReference<>("");

        ToolCallback probe = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("probeTool")
                        .description("测试用：记录自己跑在什么线程上")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                // ★ 这里【不能抛异常】：框架会把工具异常翻成文本塞进结果，
                //   测试就会"通过"——那是假绿。改为记下来，出了工具再断言。
                nonBlocking.set(reactor.core.scheduler.Schedulers.isInNonBlockingThread());
                threadName.set(Thread.currentThread().getName());
                return "ok";
            }
        };

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        AtomicInteger modelCalls = new AtomicInteger();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Flux<ChatResponse> chunks = modelCalls.getAndIncrement() == 0
                    ? Flux.just(toolCallChunk("probeTool", "{}"))
                    : Flux.just(finalChunk("好了"));
            // ★ 关键：模拟真实 WebClient —— 元素在 Reactor 的非阻塞调度器上发出
            return chunks.publishOn(reactor.core.scheduler.Schedulers.parallel());
        });

        new AgentPipeline(chatModel, new RecordingMemory(), TestToolCallbackProvider.of(probe),
                TestAudit.disabled(), new TraceProperties(), new PromptProperties(),
                memoryProperties(),
                agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), TestTurn.noop())
                .run("随便问问", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(nonBlocking.get())
                .as("工具在非阻塞线程 [%s] 上执行了。真机上任何阻塞调用（HTTP 检索 / JDBC 写回 / 审计）"
                        + "都会抛 IllegalStateException，或被静默地堵在事件循环里。"
                        + "修法是在 AgentPipeline 里把阻塞段搬到 Schedulers.boundedElastic()",
                        threadName.get())
                .isFalse();
    }
}
