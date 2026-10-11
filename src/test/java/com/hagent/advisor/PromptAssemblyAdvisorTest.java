package com.hagent.advisor;

import com.hagent.config.AdvisorProperties;
import com.hagent.config.PromptProperties;
import com.hagent.document.AccessLevel;
import com.hagent.retrieval.RelevanceBand;
import com.hagent.retrieval.RetrievedChunk;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>拼装切面</b>的行为测试 —— 它是全链路唯一重建消息列表的人，所以每一类消息的
 * 去留都必须有断言。
 *
 * <h2>★ 为什么每条都要单独断言</h2>
 *
 * <p>拼装是"把所有来源合成一份 prompt"的地方，它的失败形式是<b>静默丢内容</b>：
 * 少了历史、少了片段、或者把别的切面注入的消息吃掉 ——
 * <b>都不会报错</b>，只会让模型看到一个不完整的问题。
 *
 * <p>本项目已经在这上面栽过一次：旧的 {@code ConversationMemoryAdvisor}
 * 重建列表时只挑 system / history / user 三类，其余全丢；
 * 当时的注释还写着"顺序不能变"，读起来完全正常。问题在于<b>它丢的是别人的东西</b>。
 * 所以本类逐项锁定"谁进、谁出、谁被保留"。
 */
class PromptAssemblyAdvisorTest {

    private static final String CONSTRAINT_MARK = "【回答约束】";

    private final PromptAssemblyAdvisor advisor = new PromptAssemblyAdvisor(
            new AdvisorProperties(),
            new PromptProperties(),
            new ClassPathResource("prompts/rag-answer-prompt.md"));

    /** 链尾：把"真正要交给模型的消息列表"截获下来。 */
    private static final class CapturingAdvisor implements CallAdvisor {
        private final AtomicReference<List<Message>> captured;

        CapturingAdvisor(AtomicReference<List<Message>> captured) {
            this.captured = captured;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            captured.set(List.copyOf(request.prompt().getInstructions()));
            return new ChatClientResponse(null, request.context());
        }

        @Override
        public String getName() {
            return "CapturingAdvisor";
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 跑一次拼装，返回"模型最终会收到的消息列表"。
     *
     * <p>用真实的 {@link DefaultAroundAdvisorChain} 而不是直接调用
     * {@code adviseCall} 并手工传链 —— 后者测的是"我以为的转发方式"。
     */
    private List<Message> assemble(ChatClientRequest request) {
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(advisor)
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(request);
        return captured.get();
    }

    /** 构造一个带了记忆与检索产出的 request —— 这正是拼装切面在真实链上收到的样子。 */
    private ChatClientRequest requestWith(List<Message> history, List<RetrievedChunk> fragments) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算"))))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, history)
                .context(PromptFragmentKeys.RAG_FRAGMENTS, fragments)
                .build();
    }

    private RetrievedChunk chunk(String content, String section, int index, RelevanceBand band) {
        return new RetrievedChunk(content, "员工手册.md", "员工手册", section, index,
                AccessLevel.INTERNAL, 0.80, band);
    }

    // ========================================================================
    // 一、完整拼装的形状与顺序
    // ========================================================================

    @Test
    @DisplayName("★ 完整拼装：system(约束+片段) → 历史 → 用户，且用户消息永远最后")
    void assemblesSystemHistoryAndUserInOrder() {
        List<Message> history = List.of(
                new UserMessage("我叫张三"),
                new AssistantMessage("你好，张三"));

        List<Message> assembled = assemble(requestWith(history,
                List.of(chunk("员工每年享有五天带薪年假。", "第二章 假期管理", 1, RelevanceBand.HIGH))));

        assertThat(assembled).hasSize(4);
        assertThat(assembled.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(assembled.get(1).getText()).isEqualTo("我叫张三");
        assertThat(assembled.get(2).getText()).isEqualTo("你好，张三");
        assertThat(assembled.get(3)).isInstanceOf(UserMessage.class);
        assertThat(assembled.get(3).getText())
                .as("★ 本次用户消息必须最后 —— 模型据此判断『现在要回答什么』")
                .isEqualTo("年假怎么算");
    }

    @Test
    @DisplayName("★ 约束与片段合并进【同一条】system 消息（多提供方对多条 system 处理不一致）")
    void constraintAndFragmentsShareOneSystemMessage() {
        List<Message> assembled = assemble(requestWith(List.of(),
                List.of(chunk("员工每年享有五天带薪年假。", "第二章 假期管理", 1, RelevanceBand.HIGH))));

        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(0)).isInstanceOf(SystemMessage.class);
        String system = assembled.get(0).getText();
        assertThat(system).contains(CONSTRAINT_MARK);
        assertThat(system).contains("【知识库片段】");
    }

    // ========================================================================
    // 二、片段块的格式（模型唯一的出处依据）
    // ========================================================================

    @Test
    @DisplayName("★ 片段块格式：序号从 1 起、含来源与相关度档位")
    void rendersFragmentWithOriginAndBand() {
        List<Message> assembled = assemble(requestWith(List.of(),
                List.of(chunk("员工每年享有五天带薪年假。", "第二章 假期管理", 1, RelevanceBand.HIGH))));

        String system = assembled.get(0).getText();
        assertThat(system).contains("【知识库片段 1／来源：员工手册 第二章 假期管理／相关度：高】");
        assertThat(system).contains("员工每年享有五天带薪年假。");
    }

    @Test
    @DisplayName("★ section 为空时降级为「第 N 块」—— 防止模型自行补全一个章节名（编造来源）")
    void emptySectionFallsBackToChunkOrdinal() {
        List<Message> assembled = assemble(requestWith(List.of(),
                List.of(chunk("公司成立于 2015 年。", "", 2, RelevanceBand.MEDIUM))));

        String system = assembled.get(0).getText();
        assertThat(system)
                .as("chunkIndex=2 → 对人显示为『第 3 块』（1-based）")
                .contains("第 3 块")
                .contains("相关度：中");
        assertThat(system).doesNotContain("来源：员工手册 ／");
    }

    @Test
    @DisplayName("★ 多条片段按检索顺序编号，且档位各自独立")
    void numbersMultipleFragments() {
        List<Message> assembled = assemble(requestWith(List.of(), List.of(
                chunk("甲内容", "第一章", 0, RelevanceBand.HIGH),
                chunk("乙内容", "第二章", 1, RelevanceBand.LOW))));

        String system = assembled.get(0).getText();
        assertThat(system).contains("【知识库片段 1／来源：员工手册 第一章／相关度：高】");
        assertThat(system).contains("【知识库片段 2／来源：员工手册 第二章／相关度：低】");
    }

    // ========================================================================
    // 三、可选的入参：没有片段 / 关闭约束
    // ========================================================================

    @Test
    @DisplayName("★ 没有片段（检索空手而归）→ 只有约束，不出现空的片段块")
    void emptyFragmentsOmitFragmentBlock() {
        List<Message> assembled = assemble(requestWith(List.of(), List.of()));

        assertThat(assembled).hasSize(2);
        String system = assembled.get(0).getText();
        assertThat(system).contains(CONSTRAINT_MARK);

        // ⚠️ 不能用 "【知识库片段】" 来判断"有没有片段块" —— 约束正文里本来就写着
        //    "优先依据【知识库片段】回答"，那个标记恒在。
        //    必须用【片段块的完整形态】来判断（带序号与来源），否则断言恒真、测了个寂寞。
        assertThat(system)
                .as("没有片段时不该出现片段块标题 —— 否则模型会以为『片段是空的』"
                        + "而不是『这次没有检索到片段』")
                .doesNotContain("【知识库片段 1／来源：");
    }

    @Test
    @DisplayName("★ context 里根本没有片段键（检索没跑）→ 同样只有约束（这是正常状态，不告警）")
    void absentFragmentKeyIsTreatedAsNoRetrieval() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算"))))
                .build();

        List<Message> assembled = assemble(request);

        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(0).getText()).contains(CONSTRAINT_MARK);
    }

    @Test
    @DisplayName("★ include-constraint=false → 不注入约束（仅用于对照实验）")
    void constraintCanBeTurnedOff() {
        PromptProperties off = new PromptProperties();
        off.setIncludeConstraint(false);
        PromptAssemblyAdvisor noConstraint = new PromptAssemblyAdvisor(
                new AdvisorProperties(), off, new ClassPathResource("prompts/rag-answer-prompt.md"));

        AtomicReference<List<Message>> captured = new AtomicReference<>();
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(noConstraint)
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(requestWith(List.of(),
                        List.of(chunk("员工每年享有五天带薪年假。", "第二章", 1, RelevanceBand.HIGH))));

        assertThat(captured.get().get(0).getText())
                .as("关掉约束后，片段块仍在（它是数据），但回答约束不出现")
                .doesNotContain(CONSTRAINT_MARK)
                .contains("【知识库片段 1");
    }

    // ========================================================================
    // 四、不丢别人的东西（本轮重构的核心教训）
    // ========================================================================

    @Test
    @DisplayName("★★ 未知消息必须被保留 —— 旧写法把它丢掉，而且丢的是别人的东西")
    void preservesUnknownMessages() {
        // 模拟"将来某个新切面往 prompt 里放了一条非 system、非用户的消息"。
        //
        // ⚠️ 这里刻意用 AssistantMessage 而不是 SystemMessage：
        //    Prompt.getSystemMessage() 返回的是列表里【第一条 system 消息】，
        //    与它位置无关。所以若注入的是 SystemMessage，它会走"与原 system 合并"
        //    那条路径（同样被保留，但测的不是 collectUnknown 这条分支）。
        //    要真正覆盖"未知消息"分支，必须用非 system 的消息。
        AssistantMessage otherInjection = new AssistantMessage("【某个新切面注入的内容】");
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(
                        new UserMessage("年假怎么算"),
                        otherInjection)))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, List.of())
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        List<Message> assembled = assemble(request);

        String joined = String.join("|", assembled.stream().map(Message::getText).toList());
        assertThat(joined)
                .as("重建列表时必须把不认识的消息原样带下去，而不是静默吃掉")
                .contains("【某个新切面注入的内容】");

        assertThat(assembled).hasSize(3);
        assertThat(assembled.get(1))
                .as("未知消息应排在历史之后、本次用户消息之前")
                .isSameAs(otherInjection);
    }

    @Test
    @DisplayName("★ 注入的 SystemMessage 会与原 system 合并（同样不丢，走的是另一条分支）")
    void injectedSystemMessageIsMergedNotDropped() {
        SystemMessage otherInjection = new SystemMessage("【某个新切面注入的 system】");
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(
                        new UserMessage("年假怎么算"),
                        otherInjection)))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, List.of())
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        List<Message> assembled = assemble(request);

        assertThat(assembled.get(0).getText())
                .as("它成了『原 system』，被合并进同一条 system 消息 —— 内容仍在")
                .contains("【某个新切面注入的 system】");
        assertThat(assembled).hasSize(2);
    }

    @Test
    @DisplayName("★ 原有 system 被保留，并与约束合并进同一条 system（不是被覆盖）")
    void preservesOriginalSystemAndMergesWithConstraint() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(
                        new SystemMessage("你是企业办公助手。"),
                        new UserMessage("年假怎么算"))))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, List.of())
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        List<Message> assembled = assemble(request);

        String system = assembled.get(0).getText();
        assertThat(system).startsWith("你是企业办公助手。");
        assertThat(system).contains(CONSTRAINT_MARK);
        assertThat(assembled).hasSize(2);
    }

    @Test
    @DisplayName("★ 内容相同的两条消息不会被误删（判重按引用，不按内容）")
    void identicalContentMessagesAreNotDeduplicated() {
        // 两条文本完全一样的消息：一条来自历史、一条是本次用户消息。
        // 若用 equals 判重，其中一条会被误删 —— 那是"静默丢内容"的另一种形态。
        List<Message> history = List.of(new UserMessage("年假怎么算"));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算"))))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, history)
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        List<Message> assembled = assemble(request);

        assertThat(assembled.stream().filter(m -> "年假怎么算".equals(m.getText())))
                .as("历史里那句与本次提问内容相同，但它们是两条不同的消息，都要在")
                .hasSize(2);
    }

    // ========================================================================
    // 五、类型不符的容错
    // ========================================================================

    @Test
    @DisplayName("★ context 里放了错的类型 → 按「无历史/无片段」处理，不抛异常（并会记 WARN）")
    void wrongTypeInContextDegradesGracefully() {
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算"))))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, "这不是一个消息列表")
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        List<Message> assembled = assemble(request);

        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(1).getText()).isEqualTo("年假怎么算");
    }

    @Test
    @DisplayName("★ 请求级选项（如 reasoning-effort）在重建 prompt 后不丢")
    void preservesPromptOptions() {
        var options = org.springframework.ai.chat.prompt.ChatOptions.builder().model("deepseek-flash").build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算")), options))
                .context(PromptFragmentKeys.MEMORY_MESSAGES, List.of())
                .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                .build();

        AtomicReference<Prompt> rebuilt = new AtomicReference<>();
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(advisor)
                .push(new CallAdvisor() {
                    @Override
                    public ChatClientResponse adviseCall(ChatClientRequest r, CallAdvisorChain chain) {
                        rebuilt.set(r.prompt());
                        return new ChatClientResponse(null, r.context());
                    }

                    @Override
                    public String getName() {
                        return "PromptCaptor";
                    }

                    @Override
                    public int getOrder() {
                        return Integer.MAX_VALUE;
                    }
                })
                .build()
                .nextCall(request);

        assertThat(rebuilt.get().getOptions().getModel())
                .as("★ 重建消息列表时必须显式带上原 options —— 丢了它表现为"
                        + "『配置写了却没生效』，是本项目一直在防的静默失效")
                .isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("★ 拼装后的 prompt 不含 null 元素（空 system 不会被塞进去）")
    void doesNotEmitEmptySystemMessage() {
        // 关掉约束且无片段 → system 正文为空 → 不应产生一条空 SystemMessage
        PromptProperties off = new PromptProperties();
        off.setIncludeConstraint(false);
        PromptAssemblyAdvisor noConstraint = new PromptAssemblyAdvisor(
                new AdvisorProperties(), off, new ClassPathResource("prompts/rag-answer-prompt.md"));

        AtomicReference<List<Message>> captured = new AtomicReference<>();
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(noConstraint)
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(ChatClientRequest.builder()
                        .prompt(new Prompt(List.<Message>of(new UserMessage("年假怎么算"))))
                        .context(PromptFragmentKeys.MEMORY_MESSAGES, List.of())
                        .context(PromptFragmentKeys.RAG_FRAGMENTS, List.of())
                        .build());

        List<Message> messages = new ArrayList<>(captured.get());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("★ 拼装切面的 order 必须落在工具循环之外")
    void orderIsOutsideToolLoop() {
        assertThat(advisor.getOrder()).isEqualTo(AdvisorOrders.PROMPT_ASSEMBLY);
        assertThat(advisor.getOrder()).isLessThan(AdvisorOrders.TOOL_CALLING);
    }

    @Test
    @DisplayName("★ 资源不存在时构造器直接失败（不静默用空约束）")
    void missingConstraintResourceFailsFast() {
        Resource missing = new ClassPathResource("prompts/does-not-exist.md");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new PromptAssemblyAdvisor(
                        new AdvisorProperties(), new PromptProperties(), missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回答约束提示词读取失败");
    }
}
