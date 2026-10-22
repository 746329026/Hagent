package com.hagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史截断逻辑的回归测试 —— 截断规则是三个记忆实现共用的唯一逻辑。
 *
 * <p>它"错了不容易发现"（截断多丢几条只是模型记性差一点，不报错）却又很要命：
 * 从新的一端丢会让模型只记得最早的寒暄、丢掉刚说的话。
 */
class MemoryTrimmerTest {

    private static List<Message> history(int rounds) {
        List<Message> list = new ArrayList<>();
        for (int i = 1; i <= rounds; i++) {
            list.add(new UserMessage("第" + i + "轮问题"));
            list.add(new AssistantMessage("第" + i + "轮回答"));
        }
        return list;
    }

    @Test
    void emptyHistoryReturnsEmpty() {
        assertThat(MemoryTrimmer.trim(List.of(), new TokenBudget(1000, 10))).isEmpty();
        assertThat(MemoryTrimmer.trim(null, new TokenBudget(1000, 10))).isEmpty();
    }

    @Test
    void messageCountLimitIsHonored() {
        // 条数上限 4：只保留最后 4 条
        List<Message> kept = MemoryTrimmer.trim(history(10), new TokenBudget(1_000_000, 4));
        assertThat(kept).hasSize(4);
    }

    @Test
    void trimKeepsNewestAndDropsOldest() {
        // ★ 最核心的断言：从最旧的一端丢，保留最新的。
        // 若实现写反了（从新端丢），这条测试会失败。
        List<Message> kept = MemoryTrimmer.trim(history(5), new TokenBudget(1_000_000, 2));
        assertThat(kept).hasSize(2);
        // 最新的两条 = 第5轮的问答
        assertThat(kept.get(0).getText()).isEqualTo("第5轮问题");
        assertThat(kept.get(1).getText()).isEqualTo("第5轮回答");
    }

    @Test
    void resultKeepsChronologicalOrder() {
        // 返回顺序必须是"早 → 晚"，才能直接拼进 prompt
        List<Message> kept = MemoryTrimmer.trim(history(5), new TokenBudget(1_000_000, 4));
        assertThat(kept).hasSize(4);
        assertThat(kept.get(0).getText()).isEqualTo("第4轮问题");
        assertThat(kept.get(3).getText()).isEqualTo("第5轮回答");
    }

    @Test
    void tokenLimitStopsCollecting() {
        // 给一个很小的 token 预算，应显著少于条数上限允许的数量
        List<Message> kept = MemoryTrimmer.trim(history(50), new TokenBudget(20, 1000));
        assertThat(kept.size()).isLessThan(10);
        // 且保留的仍是最新的（最后一条一定是第50轮的问答之一）
        assertThat(kept).isNotEmpty();
        assertThat(kept.get(kept.size() - 1).getText()).isEqualTo("第50轮回答");
    }

    @Test
    void singleMessageLargerThanBudgetIsDropped() {
        // 边界：单条就超过预算时应直接不保留（保留它就已经突破预算了）
        List<Message> one = List.of(new UserMessage("这是一条非常长的消息".repeat(50)));
        List<Message> kept = MemoryTrimmer.trim(one, new TokenBudget(5, 10));
        assertThat(kept).isEmpty();
    }

    @Test
    void budgetRejectsInvalidValues() {
        // 预算参数非法时应快速失败，而不是"静默按 0 处理"
        assertThatThrownBy(() -> new TokenBudget(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBudget(100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void estimateTokensCountsOverhead() {
        // 空文本也要计固定开销 —— 这正是"为什么还需要条数上限兜底"的原因
        assertThat(MemoryTrimmer.estimateTokens(new AssistantMessage(""))).isPositive();
        assertThat(MemoryTrimmer.estimateTokens(new UserMessage("你好"))).isPositive();
    }
}
