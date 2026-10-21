package com.hagent.app;

import com.hagent.agent.PipelineEvent;
import com.hagent.config.AgentLoopProperties;
import com.hagent.config.MemoryProperties;
import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
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
 * 阶段 13：工作流模式的编排行为（<b>用假模型</b>，不花钱、可重复）。
 *
 * <p>钉住三件事，它们都是"不报错但会错"的类型：
 * <ol>
 *   <li><b>先拆解、再逐步执行</b>：计划必须在任何 {@code StepStarted} 之前出现，
 *       且每一步都有 {@code StepFinished}；</li>
 *   <li><b>规划失败要降级且可见</b>：退回普通循环，但必须发 {@code PlanSkipped}
 *       —— 悄悄退回会让用户以为工作流跑过了；</li>
 *   <li><b>两次重试后放弃，且说清停在哪一步</b>：跳过会让"少做了一件事"看起来像"做完了"。</li>
 * </ol>
 */
class WorkflowPipelineTest {

    private static final String TWO_STEP_PLAN = """
            {"goal":"回答年假政策","steps":[
              {"goal":"检索年假条款","doneWhen":"拿到条款原文"},
              {"goal":"按司龄给出结论"}]}
            """;

    private static final String ONE_STEP_PLAN = """
            {"goal":"回答年假政策","steps":[{"goal":"换个方式再查一次"}]}
            """;

    private static final class RecordingMemory implements ConversationMemory {
        private final List<List<Message>> appended = new ArrayList<>();
        private final List<Message> historyToReturn;

        private RecordingMemory() {
            this(List.of());
        }

        private RecordingMemory(List<Message> historyToReturn) {
            this.historyToReturn = historyToReturn;
        }

        @Override
        public void append(String conversationId, List<Message> messages) {
            appended.add(messages);
        }

        @Override
        public List<Message> load(String conversationId, TokenBudget budget) {
            return historyToReturn;
        }

        @Override
        public void clear(String conversationId) {
        }

        @Override
        public void delete(String conversationId) {
        }
    }

    private ChatResponse chunk(String text) {
        DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content(text)
                .reasoningContent(null)
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 规划调用按顺序返回给定的文本（第 2 个即"重规划"那次）。 */
    private ChatModel model(String... planTexts) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(
                org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                        .model("deepseek-flash").build());
        ChatResponse first = chunk(planTexts[0]);
        ChatResponse[] rest = new ChatResponse[planTexts.length - 1];
        for (int i = 1; i < planTexts.length; i++) {
            rest[i - 1] = chunk(planTexts[i]);
        }
        when(chatModel.call(any(Prompt.class))).thenReturn(first, rest);
        return chatModel;
    }

    private AgentPipeline pipeline(ChatModel chatModel, RecordingMemory memory) {
        MemoryProperties props = new MemoryProperties();
        return new AgentPipeline(
                chatModel,
                memory,
                TestToolCallbackProvider.none(),
                TestAudit.disabled(),
                new TraceProperties(),
                new PromptProperties(),
                props,
                new AgentLoopProperties(),
                new ByteArrayResource("【约束】".getBytes(StandardCharsets.UTF_8)),
                TestLongTermMemory.empty());
    }

    private List<PipelineEvent> run(AgentPipeline pipeline) {
        return pipeline.run("公司年假怎么算", "conv-1", MemoryMode.WORKFLOW, UserContext.ANONYMOUS)
                .collectList().block();
    }

    @Test
    @DisplayName("★★ 规划看全量历史、每步只看最近 2 轮 —— 因为一次工作流里模型能看到多次提示词")
    void planningSeesFullHistoryButStepsOnlyRecentTurns() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(
                org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                        .model("deepseek-flash").build());
        List<Prompt> planPrompts = new ArrayList<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            planPrompts.add(invocation.getArgument(0));
            return chunk(TWO_STEP_PLAN);
        });
        List<Prompt> stepPrompts = new ArrayList<>();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            stepPrompts.add(invocation.getArgument(0));
            return Flux.just(chunk("这一步的结论"));
        });
        List<Message> history = List.of(
                new org.springframework.ai.chat.messages.UserMessage("第一轮问"),
                new org.springframework.ai.chat.messages.AssistantMessage("第一轮答"),
                new org.springframework.ai.chat.messages.UserMessage("第二轮问"),
                new org.springframework.ai.chat.messages.AssistantMessage("第二轮答"),
                new org.springframework.ai.chat.messages.UserMessage("第三轮问"),
                new org.springframework.ai.chat.messages.AssistantMessage("第三轮答"));

        run(pipeline(chatModel, new RecordingMemory(history)));

        String planText = promptText(planPrompts.get(0));
        assertThat(planText)
                .as("规划只发生一次，要让它看到完整上下文才可能拆对")
                .contains("第一轮问", "第三轮答", "公司年假怎么算");
        String stepText = promptText(stepPrompts.get(0));
        assertThat(stepText)
                .as("每步都带全量历史会让 8 步的成本线性膨胀；最近 2 轮足够支撑指代")
                .contains("第二轮问", "第三轮答")
                .doesNotContain("第一轮问");
        assertThat(stepText)
                .as("每步的指令里必须带【原始用户问题】—— 只带规划器改写后的 goal 会丢掉细节")
                .contains("公司年假怎么算");
        assertThat(stepText)
                .as("P1：每步要求只给要点（≤300 字），否则 6 步会拼出上万字的小作文")
                .contains("300");
        assertThat(stepText)
                .as("非最后一步不该被告知『这是最后一步』")
                .doesNotContain("这是最后一步");
        assertThat(promptText(stepPrompts.get(1)))
                .as("最后一步必须要求汇总（去重 + 保留出处）—— 否则各步小作文被原样拼接")
                .contains("这是最后一步")
                .contains("合并");
    }

    private static String promptText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            sb.append(message.getText()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("★★ 先拆解再逐步执行：PlanCreated → (StepStarted → StepFinished)×2 → Final")
    void plansThenExecutesEachStep() {
        ChatModel chatModel = model(TWO_STEP_PLAN);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("条款：满一年 5 天")))
                .thenReturn(Flux.just(chunk("结论：按司龄 5/10/15 天")));
        RecordingMemory memory = new RecordingMemory();

        List<PipelineEvent> events = run(pipeline(chatModel, memory));

        assertThat(events.getFirst())
                .as("计划必须在任何步骤之前——否则『计划可见』退化成『事后才知道』")
                .isInstanceOf(PipelineEvent.PlanCreated.class);
        PipelineEvent.PlanCreated created = (PipelineEvent.PlanCreated) events.getFirst();
        assertThat(created.plan().size()).isEqualTo(2);
        assertThat(created.plan().step(1).goal()).isEqualTo("检索年假条款");

        List<PipelineEvent.StepStarted> started = events.stream()
                .filter(PipelineEvent.StepStarted.class::isInstance)
                .map(PipelineEvent.StepStarted.class::cast).toList();
        assertThat(started).extracting(PipelineEvent.StepStarted::step)
                .as("每一步各发一次 StepStarted，且编号是【计划步号】（内层模型调用不再发）")
                .containsExactly(1, 2);

        List<PipelineEvent.StepFinished> finished = events.stream()
                .filter(PipelineEvent.StepFinished.class::isInstance)
                .map(PipelineEvent.StepFinished.class::cast).toList();
        assertThat(finished).allMatch(PipelineEvent.StepFinished::ok)
                .extracting(PipelineEvent.StepFinished::step).containsExactly(1, 2);

        PipelineEvent.ModelOutput.Final last = (PipelineEvent.ModelOutput.Final) events.getLast();
        assertThat(last.text())
                .as("最终回答要把各步结论汇总——否则用户只看到最后一步")
                .contains("条款：满一年 5 天")
                .contains("结论：按司龄 5/10/15 天");
        assertThat(memory.appended)
                .as("整轮只写回一次（每步各写一次会污染历史）")
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 规划失败 → PlanSkipped + 退回普通循环（回答照常，但不假装跑过工作流）")
    void planningFailureFallsBackToNormalLoop() {
        ChatModel chatModel = model("这个问题很复杂，我先不规划了。");
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("普通回答")));

        List<PipelineEvent> events = run(pipeline(chatModel, new RecordingMemory()));

        assertThat(events).noneMatch(PipelineEvent.PlanCreated.class::isInstance);
        assertThat(events).anyMatch(PipelineEvent.PlanSkipped.class::isInstance);
        PipelineEvent.ModelOutput.Final last = (PipelineEvent.ModelOutput.Final) events.getLast();
        assertThat(last.text()).isEqualTo("普通回答");
    }

    @Test
    @DisplayName("★★ 两次重试后放弃：同步重试 → 重规划一次 → 仍失败则写明停在哪一步")
    void givesUpAfterTwoRetriesAndSaysWhere() {
        ChatModel chatModel = model(TWO_STEP_PLAN, ONE_STEP_PLAN);
        // 每一步都"正常收尾但什么都没说" —— 这是工具失败后最常见的形态
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk((String) null)));
        RecordingMemory memory = new RecordingMemory();

        List<PipelineEvent> events = run(pipeline(chatModel, memory));

        assertThat(events.stream().filter(PipelineEvent.PlanCreated.class::isInstance).count())
                .as("首次计划 + 重规划各发一次 PlanCreated")
                .isEqualTo(2);
        PipelineEvent.ModelOutput.Final last = (PipelineEvent.ModelOutput.Final) events.getLast();
        assertThat(last.text())
                .as("失败必须写清停在哪一步 —— 静默跳过会把『没做成』伪装成『做完了』")
                .contains("未完成")
                .contains("第 1 步");
        assertThat(memory.appended)
                .as("即使失败也要写回（用户能看到这次发生了什么）")
                .hasSize(1);
    }
}
