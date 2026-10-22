package com.hagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆的契约测试 —— 一套断言，所有"层组合"都必须通过。
 *
 * <p>要用的搭配有三种：mysql + redis（生产）、mysql + none（降级）、file + none（零基础设施）。
 * 若每个组合各写一套测试，写着写着断言就不一样了，"换实现只改配置"这句话便没有证据
 * （配置能换，但行为可能不同、且没人发现）；有了这个基类，新实现天然被同一组断言检验。
 *
 * <p>断言写在【门面】这一层：{@link ConversationMemory} 是切面依赖的抽象，也是用户可观察的语义边界；
 * 记录层自己的语义（只按条数取尾部、不做 token 裁剪）由各实现的单元测试覆盖，不在这里重复。
 *
 * <p>每个用例用独立会话 ID：mysql / redis 组合共用真实库与实例，固定 ID 会让用例互相污染；用例结束即清理。
 */
abstract class ConversationMemoryContractTest {

    protected static final TokenBudget BIG = new TokenBudget(1_000_000, 1000);

    /** 每个实现自己决定怎么造出一个门面（可能就是 new，也可能要 Spring 上下文）。 */
    protected abstract ConversationMemory newMemory();

    private ConversationMemory memory;
    private String conversationId;

    @BeforeEach
    void freshMemoryAndConversation() {
        memory = newMemory();
        conversationId = "contract-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        try {
            memory.delete(conversationId);
        } catch (Exception ignored) {
            // 清理失败不影响测试结论；但测试自己必须保证用的是独立 ID
        }
    }

    @Test
    @DisplayName("追加后能原样读回，且消息类型正确还原")
    void appendThenLoadRoundTrip() {
        memory.append(conversationId, List.of(new UserMessage("我叫张三"), new AssistantMessage("你好张三")));

        List<Message> loaded = memory.load(conversationId, BIG);

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getText()).isEqualTo("我叫张三");
        assertThat(loaded.get(1).getText()).isEqualTo("你好张三");
        // 类型也要还原正确 —— 否则拼进 prompt 时角色会错（问被当成答）
        assertThat(loaded.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(loaded.get(1).getMessageType().name()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("返回时间正序（早 → 晚），可直接拼进 prompt")
    void returnsChronologicalOrder() {
        memory.append(conversationId, List.of(new UserMessage("第一句")));
        memory.append(conversationId, List.of(new UserMessage("第二句")));
        memory.append(conversationId, List.of(new UserMessage("第三句")));

        List<Message> loaded = memory.load(conversationId, BIG);

        assertThat(loaded).extracting(Message::getText)
                .containsExactly("第一句", "第二句", "第三句");
    }

    @Test
    @DisplayName("★ 条数预算从【最旧的一端】截断（最近的对话最相关）")
    void messageBudgetDropsTheOldestEnd() {
        for (int i = 1; i <= 10; i++) {
            memory.append(conversationId, List.of(new UserMessage("第" + i + "条")));
        }

        List<Message> loaded = memory.load(conversationId, new TokenBudget(1_000_000, 3));

        assertThat(loaded).hasSize(3);
        assertThat(loaded).extracting(Message::getText)
                .containsExactly("第8条", "第9条", "第10条");
    }

    @Test
    @DisplayName("★ 换一个实例（模拟重启 / 换缓存节点）仍能读到 —— 权威数据真的落库了")
    void dataSurvivesANewInstance() {
        memory.append(conversationId, List.of(new UserMessage("持久化验证")));

        // 这是"上下文层可丢、记录层权威"的直接证据：换一个门面实例后，
        // 即使上下文层是空的（新实例/新节点/TTL 已过），也必须能从记录层重建出同样的内容。
        ConversationMemory reopened = newMemory();

        assertThat(reopened.load(conversationId, BIG))
                .extracting(Message::getText)
                .containsExactly("持久化验证");
    }

    @Test
    @DisplayName("会话之间互不干扰")
    void conversationsAreIsolated() {
        String other = conversationId + "-other";
        try {
            memory.append(conversationId, List.of(new UserMessage("会话A的消息")));
            memory.append(other, List.of(new UserMessage("会话B的消息")));

            assertThat(memory.load(conversationId, BIG)).extracting(Message::getText)
                    .containsExactly("会话A的消息");
            assertThat(memory.load(other, BIG)).extracting(Message::getText)
                    .containsExactly("会话B的消息");
        } finally {
            memory.delete(other);
        }
    }

    @Test
    @DisplayName("未知会话返回空列表而不是 null（否则上游拼 prompt 时会 NPE）")
    void unknownConversationReturnsEmptyList() {
        assertThat(memory.load("contract-不存在的会话-" + UUID.randomUUID(), BIG))
                .isNotNull().isEmpty();
    }

    @Test
    @DisplayName("clear 清空内容，但会话仍可继续使用")
    void clearKeepsConversationUsable() {
        memory.append(conversationId, List.of(new UserMessage("要被清空的")));
        memory.clear(conversationId);

        assertThat(memory.load(conversationId, BIG)).isEmpty();

        memory.append(conversationId, List.of(new UserMessage("清空后的新消息")));
        assertThat(memory.load(conversationId, BIG)).extracting(Message::getText)
                .containsExactly("清空后的新消息");
    }

    @Test
    @DisplayName("delete 删除整个会话")
    void deleteRemovesConversation() {
        memory.append(conversationId, List.of(new UserMessage("要被删除的")));
        memory.delete(conversationId);

        assertThat(memory.load(conversationId, BIG)).isEmpty();
    }

    @Test
    @DisplayName("空输入不报错、不产生副作用")
    void emptyInputIsNoOp() {
        memory.append(conversationId, List.of());

        assertThat(memory.load(conversationId, BIG)).isEmpty();
    }

    @Test
    @DisplayName("★ 中文与 emoji 跨层往返完好（utf8mb4 的真实检验）")
    void chineseAndEmojiSurviveRoundTrip() {
        String text = "😀𠮷 中文测试：年假五个工作日";
        memory.append(conversationId, List.of(new UserMessage(text)));

        assertThat(memory.load(conversationId, BIG)).extracting(Message::getText)
                .containsExactly(text);
    }
}
