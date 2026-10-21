package com.hagent.app;

import com.hagent.agent.PipelineEvent;
import com.hagent.config.AgentLoopProperties;
import com.hagent.config.MemoryProperties;
import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.TokenBudget;
import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStatus;
import com.hagent.observability.TurnStore;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
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
 * 阶段 14：<b>回合账</b>的口径与三出口（{@code TurnStats} + {@code AgentPipeline} 的接线）。
 *
 * <h2>★ 它钉住的是"不报错但会错"的四个地方</h2>
 *
 * <ol>
 *   <li>★ <b>token 是整轮累加，不是最后一次</b>。阶段 14 之前 {@code Final.usage} 取
 *       最后一片，实测一轮 workflow 调 7 次模型、只记了最后一次的 3419 ——
 *       成本被低估 3~7 倍且不报错。这条用例用一个"两次调用各有用量"的假模型把它钉死；</li>
 *   <li><b>失败与取消也必须留一条账</b>。它们的记录在 MySQL 里本来<b>零痕迹</b>
 *       （失败不写回记忆），若不记，成功率永远是 100%；</li>
 *   <li><b>取消 ≠ 失败</b>。用户关页面是正常操作，混进 FAILED 会让成功率无缘无故变差；</li>
 *   <li><b>工具次数必须与审计行数一致</b>，且两边共用同一个 {@code turnId}
 *       —— 否则"这一轮到底调了几次工具"有两个可能的答案。</li>
 * </ol>
 *
 * <p>全部用假模型 → 不花钱、可重复（与阶段 9/13 的管线测试同一套做法）。
 */
class TurnMetricsTest {

    // ========================================================================
    // 一、口径：token 是整轮累加
    // ========================================================================

    @Test
    @DisplayName("★★ 多步回合的 token 是【各次调用之和】，不是最后一次")
    void tokensAreSummedAcrossModelCalls() {
        TestTurn.Recording turn = TestTurn.recording();
        AtomicInteger calls = new AtomicInteger();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        // 第 1 次：请求工具，用量 100 + 20 = 120
        // 第 2 次：给答案，  用量 200 + 40 = 240
        // 整轮应当是 360；若实现取"最后一片"，这里会是 240（这正是阶段 14 之前的 bug）
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(chunk(toolCallMessage("getWeather", "{}"), usage(100, 20)))
                        : Flux.just(chunk(textMessage("今天晴。"), usage(200, 40))));

        pipeline(chatModel, turn, new FakeTool("getWeather", "杭州今天晴"))
                .run("杭州天气", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        TurnRecord record = turn.single();
        assertThat(record.status()).isEqualTo(TurnStatus.SUCCESS);
        assertThat(record.modelCalls()).as("两次模型调用").isEqualTo(2);
        assertThat(record.totalTokens())
                .as("★ 120 + 240 = 360。取最后一片会得到 240 —— 那正是本阶段要修的缺陷")
                .isEqualTo(360);
        assertThat(record.promptTokens()).isEqualTo(300);
        assertThat(record.completionTokens()).isEqualTo(60);
        assertThat(record.usageMissingCalls())
                .as("两次调用都拿到了 usage")
                .isZero();
        assertThat(record.firstTokenMs())
                .as("至少第一片 token 到达过 ⇒ 首字耗时必须有值（它是『慢在等首字』的判据）")
                .isNotNull();
    }

    @Test
    @DisplayName("★ 没有 usage 的调用要记成『缺失』，不能记成 0 token")
    void missingUsageIsCountedNotTreatedAsZero() {
        TestTurn.Recording turn = TestTurn.recording();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        // 不传 metadata ⇒ 框架语义是"没有数据"（EmptyUsage / null 的同一种意思）
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk(textMessage("答"), null)));

        pipeline(chatModel, turn, new FakeTool("unused", "x"))
                .run("问", "conv-2", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        TurnRecord record = turn.single();
        assertThat(record.totalTokens())
                .as("★ EmptyUsage 的 getPromptTokens() 返回 0（javap 实证）—— 若按数值累加，"
                        + "『没数据』会被记成『这轮花了 0 token』，成本表从此有假数据")
                .isNull();
        assertThat(record.usageMissingCalls()).isEqualTo(1);
    }

    // ========================================================================
    // 二、三出口：失败 / 取消 / 步数上限
    // ========================================================================

    @Test
    @DisplayName("★★ 模型抛错 → 落一条 FAILED（此前失败轮在库里【零痕迹】）")
    void failedTurnIsRecordedAsFailed() {
        TestTurn.Recording turn = TestTurn.recording();
        AtomicInteger calls = new AtomicInteger();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(chunk(toolCallMessage("getWeather", "{}"), usage(100, 20)))
                        : Flux.error(new IllegalStateException("上游 502")));

        assertThat(catchError(() -> pipeline(chatModel, turn, new FakeTool("getWeather", "晴"))
                .run("天气", "conv-3", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block()))
                .as("错误必须照常抛给调用方（账本不能吞掉故障）")
                .contains("上游 502");

        TurnRecord record = turn.single();
        assertThat(record.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(record.errorMessage()).contains("上游 502");
        assertThat(record.modelCalls())
                .as("★ 失败的那次调用也算一次：它同样花了钱、同样让用户等了")
                .isEqualTo(2);
        assertThat(record.usageMissingCalls())
                .as("第二次调用没走到收尾片 ⇒ 它的用量必然缺失（差值即缺口）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 客户端取消 → 落一条 CANCELLED，而不是 FAILED")
    void cancelledTurnIsNotCountedAsFailure() {
        TestTurn.Recording turn = TestTurn.recording();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk(textMessage("半句话"), usage(10, 5))));

        // take(1) 拿到第一个事件就取消上游 —— 与"用户关掉页面"同一条通道（dispose）
        pipeline(chatModel, turn, new FakeTool("unused", "x"))
                .run("问", "conv-4", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .take(1)
                .blockLast();

        TurnRecord record = turn.single();
        assertThat(record.status())
                .as("★ 用户关页面不是系统故障。混进 FAILED 会让成功率无缘无故变差，"
                        + "而且掩盖真正的失败")
                .isEqualTo(TurnStatus.CANCELLED);
    }

    @Test
    @DisplayName("★ 撞上步数上限：状态仍是 SUCCESS，但 finishReason 说清是『被截断』")
    void maxStepsIsSuccessButSaysTruncated() {
        TestTurn.Recording turn = TestTurn.recording();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        // ★ 每一轮都"说半句 + 又想调工具"：这样撞上上限时手里是有内容的
        //   （完全没有内容的 maxSteps 是 FAILED —— 用户什么都没拿到，见下一条注释）
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk(textAndToolCallMessage("先查一下", "loopTool"), usage(1, 1))));

        AgentLoopProperties props = new AgentLoopProperties();
        props.setChatMaxSteps(2);
        props.setWorkflowMaxSteps(4);

        newPipeline(chatModel, turn, new FakeTool("loopTool", "还是没完"), props)
                .run("一直绕圈", "conv-5", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        TurnRecord record = turn.single();
        assertThat(record.status())
                .as("防御出口产出了内容 ⇒ 算成功；把它算失败会让『截断率』与『故障率』混成一个数")
                .isEqualTo(TurnStatus.SUCCESS);
        assertThat(record.finishReason())
                .as("★ 但必须留下『为什么停』—— 否则一条被截断的回答看起来与正常回答一模一样")
                .isEqualTo("maxSteps");
        assertThat(record.modelCalls()).isEqualTo(2);
    }

    // ========================================================================
    // 三、对账：工具次数 与 审计行数
    // ========================================================================

    @Test
    @DisplayName("★★ 本轮的 tool_calls 与审计行数一致，且两边共用同一个 turnId")
    void toolCallsMatchAuditRowsAndShareTheTurnId() {
        TestTurn.Recording turn = TestTurn.recording();
        TestAudit.RecordingAudit audit = TestAudit.recording();
        AtomicInteger calls = new AtomicInteger();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                calls.getAndIncrement() == 0
                        ? Flux.just(chunk(toolCallMessage("getWeather", "{\"city\":\"杭州\"}"),
                        usage(50, 10)))
                        : Flux.just(chunk(textMessage("今天晴。"), usage(60, 12))));

        new AgentPipeline(chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(new FakeTool("getWeather", "晴").asCallback()),
                audit,
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), turn)
                .run("天气", "conv-6", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        TurnRecord record = turn.single();
        assertThat(record.toolCalls())
                .as("★ 这个数必须能和审计表对账（SELECT COUNT(*) WHERE turn_id=?）。"
                        + "对不上就说明审计写入失败过 —— 而那种失败平时没人看得见")
                .isEqualTo(audit.recorded().size())
                .isEqualTo(1);
        assertThat(audit.recorded().get(0).turnId())
                .as("★ 两边必须是同一个 turnId，否则『对账』这件事根本做不到")
                .isEqualTo(record.turnId());
        assertThat(audit.recorded().get(0).userId()).isEqualTo(UserContext.ANONYMOUS.userId());
    }

    // ========================================================================
    // 四、账本自己坏了不能拖垮对话
    // ========================================================================

    @Test
    @DisplayName("★★ 账本写入失败不能影响对话（与工具审计同一立场）")
    void storeFailureDoesNotBreakTheTurn() {
        // 匿名类而不是 lambda：TurnStore 现在有第二个方法（find，给历史回放用），
        // 不再是函数式接口 —— 而"实现一半"在这里正是我们要显式写出来的东西
        TurnStore broken = new TurnStore() {
            @Override
            public void record(TurnRecord record) {
                throw new IllegalStateException("磁盘满了");
            }

            @Override
            public java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds) {
                return java.util.Map.of();
            }
        };

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk(textMessage("照常回答"), usage(1, 1))));

        List<PipelineEvent> events = new AgentPipeline(chatModel, new RecordingMemory(),
                TestToolCallbackProvider.none(), TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), broken)
                .run("问", "conv-7", MemoryMode.CHAT, UserContext.ANONYMOUS)
                .collectList().block();

        assertThat(events)
                .as("★ 模型回复已经产生、token 已经花掉 —— 为『账写不进去』丢掉它是双重损失")
                .hasAtLeastOneElementOfType(PipelineEvent.ModelOutput.Final.class);
        assertThat(events.stream()
                .filter(PipelineEvent.ModelOutput.Final.class::isInstance)
                .map(e -> (PipelineEvent.ModelOutput.Final) e)
                .findFirst().orElseThrow().text())
                .isEqualTo("照常回答");
    }

    // ========================================================================
    // 五、工作流：规划调用与计划步数也要进账
    // ========================================================================

    @Test
    @DisplayName("★★ 工作流：model_calls 含【规划那次】，plan_steps 是计划步数")
    void workflowCountsPlanningCallAndPlanSteps() {
        TestTurn.Recording turn = TestTurn.recording();
        String plan = """
                {"goal":"回答年假政策","steps":[
                  {"goal":"检索年假条款","doneWhen":"拿到条款原文"},
                  {"goal":"按司龄给出结论"}]}
                """;

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(deepSeekOptions());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chunk(textMessage(plan), usage(300, 60)));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk(textMessage("条款：满一年 5 天"), usage(200, 40))))
                .thenReturn(Flux.just(chunk(textMessage("结论：5/10/15 天"), usage(250, 50))));

        new AgentPipeline(chatModel, new RecordingMemory(),
                TestToolCallbackProvider.none(), TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), agentLoopProperties(5),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), turn)
                .run("公司年假怎么算", "conv-8", MemoryMode.WORKFLOW, UserContext.ANONYMOUS)
                .collectList().block();

        TurnRecord record = turn.single();
        assertThat(record.mode()).isEqualTo("workflow");
        assertThat(record.planSteps()).as("计划 2 步").isEqualTo(2);
        assertThat(record.modelCalls())
                .as("★ 规划 1 次 + 每步 1 次 = 3。漏掉规划会让一次 3 步工作流记成 2 次 —— "
                        + "而那正是真实事故里『7 次只记 6 次』的同一类偏差")
                .isEqualTo(3);
        assertThat(record.totalTokens())
                .as("整轮 = 360 + 240 + 300 = 900")
                .isEqualTo(900);
        assertThat(record.status()).isEqualTo(TurnStatus.SUCCESS);
    }

    // ========================================================================
    // 测试替身与脚手架
    // ========================================================================

    private AgentPipeline pipeline(ChatModel chatModel, TurnStore turnStore, FakeTool tool) {
        return newPipeline(chatModel, turnStore, tool, agentLoopProperties(5));
    }

    private AgentPipeline newPipeline(ChatModel chatModel, TurnStore turnStore,
                                      FakeTool tool, AgentLoopProperties loopProperties) {
        return new AgentPipeline(chatModel, new RecordingMemory(),
                TestToolCallbackProvider.of(tool.asCallback()),
                TestAudit.disabled(),
                new TraceProperties(), new PromptProperties(), memoryProperties(), loopProperties,
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty(), turnStore);
    }

    private static String catchError(Runnable action) {
        try {
            action.run();
            return "";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
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

    private AgentLoopProperties agentLoopProperties(int chatMaxSteps) {
        AgentLoopProperties props = new AgentLoopProperties();
        props.setChatMaxSteps(chatMaxSteps);
        props.setWorkflowMaxSteps(chatMaxSteps * 2);
        return props;
    }

    private static DeepSeekChatOptions deepSeekOptions() {
        return DeepSeekChatOptions.builder().model("deepseek-flash").build();
    }

    private static Usage usage(int prompt, int completion) {
        return new DefaultUsage(prompt, completion);
    }

    /** 造一片响应；{@code usage} 为 null 表示这一片没有用量（等价于 EmptyUsage）。 */
    private static ChatResponse chunk(AssistantMessage message, Usage usage) {
        Generation generation = new Generation(message);
        return usage == null
                ? new ChatResponse(List.of(generation))
                : new ChatResponse(List.of(generation),
                        ChatResponseMetadata.builder().usage(usage).build());
    }

    private static AssistantMessage textMessage(String text) {
        return DeepSeekAssistantMessage.builder()
                .content(text)
                .reasoningContent("想清楚了")
                .toolCalls(List.of())
                .build();
    }

    private static AssistantMessage toolCallMessage(String toolName, String args) {
        return DeepSeekAssistantMessage.builder()
                .content("")
                .reasoningContent("我应该调用 " + toolName)
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, args)))
                .build();
    }

    /** 一边说半句、一边还想继续调工具 —— 用来造"撞上步数上限时手里有内容"的场景。 */
    private static AssistantMessage textAndToolCallMessage(String text, String toolName) {
        return DeepSeekAssistantMessage.builder()
                .content(text)
                .reasoningContent("再查一次")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                .build();
    }

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

    /** 固定返回值的假工具。 */
    private static final class FakeTool {
        private final String name;
        private final String result;

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
                    return result;
                }
            };
        }
    }
}
