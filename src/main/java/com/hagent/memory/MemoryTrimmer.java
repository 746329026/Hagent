package com.hagent.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史截断工具 —— <b>预算的唯一执行者</b>。
 *
 * <p>{@code TokenBudget} 只是一个声明，自身没有行为；截断规则（从最旧端丢、token 与条数双限制、
 * 返回正序）是<b>业务语义</b>，必须收在一处 —— 散进各存储实现就会出现"各层行为不一致"。
 *
 * <h2>token 计数用的是"近似"</h2>
 *
 * <p>用 jtokkit 的 {@code o200k_base} 估算，<b>它不是 DeepSeek 的官方分词器</b>（未公开分词表）。
 * 所以：计数只够用来"控制历史规模"这个目的，<b>不能</b>做精确的计费预测；
 * 真实用量应以 API 响应里的 {@code usage} 字段为准。
 * 把局限写在代码里而不是藏着，是因为"用近似值却声称精确"属于典型的误导性设计。
 */
public final class MemoryTrimmer {

    /**
     * jtokkit 的编码。{@code o200k_base} 是较新的通用编码，
     * 对中英文混合文本的估算误差比旧的 cl100k_base 更小。
     */
    private static final Encoding ENCODING;

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncoding(EncodingType.O200K_BASE);
    }

    private MemoryTrimmer() {
    }

    /**
     * 按预算截断历史。
     *
     * <p>规则（顺序不可换）：从<b>最旧的一端</b>开始丢弃；同时受 token 与条数两个上限约束，
     * <b>谁先到就用谁</b>；结果按<b>时间正序</b>返回，可直接拼进 prompt。
     *
     * @param history 完整历史（时间正序）
     * @param budget  预算
     * @return 截断后的历史（时间正序）；可能为空列表
     */
    public static List<Message> trim(List<Message> history, TokenBudget budget) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        // 从最新往旧累计，直到任一上限被突破
        List<Message> kept = new ArrayList<>();
        int tokens = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            int cost = estimateTokens(m);

            // 条数上限：已够多就不再取
            if (kept.size() >= budget.maxMessages()) {
                break;
            }
            // token 上限：加上这一条会超预算就停。
            // 单条就超预算时 kept 为空、直接 break，即这条也不保留 —— 保留它就已经突破预算了。
            if (tokens + cost > budget.maxTokens()) {
                break;
            }
            kept.add(m);
            tokens += cost;
        }

        // 上面是"从新到旧"收集的，需要反转回时间正序
        java.util.Collections.reverse(kept);
        return kept;
    }

    /**
     * 估算一条消息的 token 数。
     *
     * <p>除了正文还要加上<b>每条消息的固定开销</b>（角色标记等）—— 这正是"纯 token 限制还需要
     * 条数上限兜底"的原因：大量超短消息的正文几乎不占 token，但固定开销会累积。
     */
    public static int estimateTokens(Message message) {
        String text = message.getText();
        if (text == null || text.isEmpty()) {
            return MESSAGE_OVERHEAD;
        }
        return ENCODING.countTokens(text) + MESSAGE_OVERHEAD;
    }

    /**
     * 每条消息的固定 token 开销估算（角色标记、分隔符等）。
     * 取 4 是经验值：足够表达"每条消息都有额外成本"这个事实。
     */
    private static final int MESSAGE_OVERHEAD = 4;
}
