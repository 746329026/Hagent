package com.hagent.app;

import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PromptBuilder} 的拼装语义 —— <b>全链路唯一重建消息列表者的行为契约</b>。
 *
 * <p>拼装是<b>纯函数</b>：给 3 个参数、拿回 1 个 {@code Prompt}，断言直接对着返回值做。
 *
 * <p>一条有独立价值的断言必须留着："约束资源读不到 → 启动失败"。它防的是"静默用一个空约束" ——
 * 那种情况下模型回答依然通顺，只是偶尔编造来源，<b>没人会往提示词没加载上想</b>。
 *
 * <p>检索片段不在本测试范围：片段已改为作为工具结果回到模型，渲染断言在
 * {@code KnowledgeRetrievalToolTest}（测试跟着职责走，不留在原地测已不存在的路径）。
 */
class PromptBuilderTest {

    private static final String CONSTRAINT_TEXT = "【约束】只依据知识库片段回答，不得编造来源。";

    private static final Resource CONSTRAINT =
            new ByteArrayResource(CONSTRAINT_TEXT.getBytes(StandardCharsets.UTF_8));

    private PromptBuilder builder() {
        return new PromptBuilder(new TraceProperties(), new PromptProperties(), CONSTRAINT);
    }

    private PromptBuilder builder(PromptProperties promptProperties) {
        return new PromptBuilder(new TraceProperties(), promptProperties, CONSTRAINT);
    }

    /** 只放本次用户消息的原始 prompt（与 AgentPipeline 交给 PromptBuilder 的形状一致）。 */
    private Prompt original(String userText) {
        return new Prompt(List.of(new UserMessage(userText)));
    }

    // ========================================================================
    // 一、结构：顺序不能变
    // ========================================================================

    @Test
    @DisplayName("★ 拼装顺序：system → 历史 → 未知 → 本次用户消息（用户消息永远最后）")
    void assemblyOrderIsFixed() {
        List<Message> history = List.of(
                new UserMessage("我叫张三"),
                new AssistantMessage("你好张三"));

        Prompt assembled = builder().assemble(original("我叫什么？"), history, null);

        List<Message> messages = assembled.getInstructions();
        assertThat(messages).hasSize(1 + history.size() + 1);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.subList(1, 3)).isEqualTo(history);
        assertThat(messages.get(messages.size() - 1))
                .as("用户消息必须最后 —— 模型据此判断『现在要回答什么』")
                .isInstanceOf(UserMessage.class)
                .extracting(Message::getText).isEqualTo("我叫什么？");
    }

    @Test
    @DisplayName("★ 约束与原有 system 合并进【同一条】 system 消息，而不是多条")
    void constraintAndSystemShareOneSystemMessage() {
        Prompt assembled = builder().assemble(original("年假几天"), List.of(), null);

        long systemCount = assembled.getInstructions().stream()
                .filter(SystemMessage.class::isInstance).count();

        assertThat(systemCount)
                .as("多家提供方对『多条 system』处理不一致（有的合并、有的只认第一条）——"
                        + "合成一条就没有这个不确定性")
                .isEqualTo(1);

        String systemText = ((SystemMessage) assembled.getInstructions().get(0)).getText();
        assertThat(systemText).contains(CONSTRAINT_TEXT.trim());
    }

    // ========================================================================
    // 二、未知消息保留
    // ========================================================================

    @Test
    @DisplayName("★ 既不是 system 也不是本次用户的消息，必须【原样保留】而不是丢掉")
    void unknownMessagesArePreserved() {
        AssistantMessage unknown = new AssistantMessage("【别人放进来的东西】");
        Prompt original = new Prompt(List.of(
                new SystemMessage("原有 system"),
                unknown,
                new UserMessage("本次提问")));

        Prompt assembled = builder().assemble(original, List.of(), null);

        assertThat(assembled.getInstructions())
                .as("旧实现只挑 system / history / user 三类、其余全丢 —— 它丢的是别人的东西。"
                        + "『唯一重建者』有义务把不认识的也带下去")
                .contains(unknown);
    }

    @Test
    @DisplayName("★ 未知消息的判断用【引用】而不是 equals —— 内容相同的消息不能被误删")
    void unknownDetectionUsesIdentityNotEquality() {
        // 这条消息与"本次用户消息"内容完全相同，但它是【另一个对象】。
        // 用 equals 判断会把其中一条误删 —— 那正是"静默丢内容"的另一种形态。
        UserMessage sameContentAsUser = new UserMessage("一样的话");
        Prompt original = new Prompt(List.of(
                sameContentAsUser,
                new UserMessage("一样的话")));

        Prompt assembled = builder().assemble(original, List.of(), null);

        assertThat(assembled.getInstructions())
                .as("内容相同但引用不同的消息必须被保留")
                .contains(sameContentAsUser);
    }

    // ========================================================================
    // 三、合并【全部】 system，而不是只取第一条
    // ========================================================================

    @Test
    @DisplayName("★★ 多条 system 全部被合并 —— 第二条不会被挤成『未知消息』")
    void allSystemMessagesAreMerged() {
        SystemMessage first = new SystemMessage("第一段 system");
        SystemMessage second = new SystemMessage("第二段 system（阶段 9 的工具说明之类）");
        Prompt original = new Prompt(List.of(first, second, new UserMessage("提问")));

        Prompt assembled = builder().assemble(original, List.of(), null);

        String systemText = ((SystemMessage) assembled.getInstructions().get(0)).getText();
        assertThat(systemText)
                .as("框架的 Prompt.getSystemMessage() 只返回【第一条】（字节码实测）——"
                        + "照抄它会让后加的 system 掉进『未知消息』、被静默挤到 prompt 中段，"
                        + "失去 system 的地位")
                .contains("第一段 system")
                .contains("第二段 system");

        // 两条原文都不应再作为独立消息出现（它们已被合并）
        assertThat(assembled.getInstructions())
                .doesNotContain(first)
                .doesNotContain(second);
    }

    @Test
    @DisplayName("★ 没有 system 时不产出空的 SystemMessage")
    void noSystemMessageWhenNoneGiven() {
        PromptProperties off = new PromptProperties();
        off.setIncludeConstraint(false);

        Prompt assembled = builder(off).assemble(original("你好"), List.of(), null);

        assertThat(assembled.getInstructions())
                .allMatch(m -> m instanceof UserMessage,
                        "既没有原有 system、又关掉了约束时，不该凭空造一条空 system")
                .isNotEmpty();
    }

    @Test
    @DisplayName("★ include-constraint=false 时不注入约束（对照实验用的开关）")
    void constraintCanBeTurnedOff() {
        PromptProperties off = new PromptProperties();
        off.setIncludeConstraint(false);

        Prompt assembled = builder(off).assemble(original("你好"), List.of(), null);

        assertThat(assembled.getInstructions())
                .allMatch(m -> m instanceof UserMessage)
                .as("关掉约束的用途是【对照实验】，不是『让模型自由发挥』")
                .isNotEmpty();
    }

    // ========================================================================
    // 三·二、长期记忆索引
    // ========================================================================

    @Test
    @DisplayName("★ 长期记忆索引拼进 system 末尾，且与约束之间有空行分隔")
    void memoryDigestIsAppendedToSystem() {
        Prompt assembled = builder().assemble(original("我叫什么"), List.of(),
                "【长期记忆】\n- 称呼偏好 — 叫他张工");

        String system = assembled.getSystemMessage().getText();
        assertThat(system).contains("【长期记忆】").contains("称呼偏好");
        assertThat(system.indexOf("【长期记忆】"))
                .as("记忆在约束之后：先立规则，再给'关于这个人的背景'")
                .isGreaterThan(system.indexOf("【约束"));
        assertThat(system).contains("\n\n【长期记忆】");
    }

    @Test
    @DisplayName("★ 没有记忆（null / 空串）时不产生空白段落 —— 空段落也会花 token")
    void blankMemoryDigestProducesNoExtraBlock() {
        String withoutNull = builder().assemble(original("你好"), List.of(), null)
                .getSystemMessage().getText();
        String withoutBlank = builder().assemble(original("你好"), List.of(), "   ")
                .getSystemMessage().getText();

        assertThat(withoutNull)
                .as("null 与空串必须给出完全相同的 system —— 否则'有没有记忆'会改变 prompt 的字节数，"
                        + "让缓存/对比测试出现莫名其妙的差异")
                .isEqualTo(withoutBlank)
                .doesNotContain("长期记忆");
    }

    // ========================================================================
    // 四、资源缺失 → 启动失败
    // ========================================================================

    @Test
    @DisplayName("★ 约束资源不存在 → 构造即失败，绝不静默用空约束")
    void missingConstraintResourceFailsFast() {
        Resource missing = new ClassPathResource("prompts/不存在的提示词.md");

        assertThatThrownBy(() -> new PromptBuilder(
                new TraceProperties(), new PromptProperties(), missing))
                .as("缺失时模型会照常给出通顺的回答，只是偶尔编造来源 ——"
                        + "这种表现不会被联想到『提示词没加载』，所以宁可起不来")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回答约束提示词读取失败")
                .hasMessageContaining("hagent.rag.prompt.constraint-resource");
    }

    @Test
    @DisplayName("★ 真实资源能被读入 —— 与 classpath 上的那份保持一致")
    void realConstraintResourceIsReadable() {
        PromptBuilder real = new PromptBuilder(
                new TraceProperties(), new PromptProperties(),
                new ClassPathResource("prompts/rag-answer-prompt.md"));

        Prompt assembled = real.assemble(original("你好"), List.of(), null);
        String systemText = ((SystemMessage) assembled.getInstructions().get(0)).getText();

        assertThat(systemText)
                .as("真实约束文件必须非空且被拼进 system")
                .isNotBlank();
    }

    // ========================================================================
    // 五、边界
    // ========================================================================

    @Test
    @DisplayName("★ 空历史与 null 片段都不报错 —— 新会话是最常见的路径")
    void emptyHistoryAndNullFragmentsAreSafe() {
        Prompt assembled = builder().assemble(original("第一句话"), List.of(), null);

        assertThat(assembled.getInstructions()).isNotEmpty();
        assertThat(assembled.getInstructions().get(assembled.getInstructions().size() - 1))
                .extracting(Message::getText).isEqualTo("第一句话");
    }

    @Test
    @DisplayName("★ 不传 ChatOptions —— 让框架自动填入已配置的默认值")
    void doesNotCarryChatOptions() {
        Prompt assembled = builder().assemble(original("你好"), List.of(), null);

        assertThat(assembled.getOptions())
                .as("实测字节码：DeepSeekChatModel.buildRequestPrompt 在 options 为 null 时"
                        + "填入 this.getOptions()（即配置里的 reasoning-effort / 模型名）。"
                        + "若这里显式传了 options，反而会覆盖配置，表现为『配置写了却没生效』")
                .isNull();
    }
}
