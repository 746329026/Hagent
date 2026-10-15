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
 * <p><b>★ 阶段 8 修正了本类的注释</b>：原文写"三个实现共用的唯一出处"，
 * 那是阶段 5 的旧措辞（当时记忆有 file / redis / mysql 三个平级实现）。
 * 阶段 7 把记忆改成"记录层 + 上下文层"两层之后，本类的使用者已经变了；
 * 阶段 8 起它的调用者是 {@code LayeredConversationMemory}（门面）<b>一处</b>。
 *
 * <p><b>为什么仍然必须抽成公共类</b>：截断规则是"业务语义"（从最旧的一端丢、
 * token 与条数双限制、保证返回正序），而且它是<b>预算真正被执行的唯一位置</b> ——
 * {@code TokenBudget} 只是一个声明，自身没有任何行为。
 * 若把这个规则散进各存储实现，就会出现"各层行为不一致"，
 * 而那正是参考实现踩过的坑（多个记忆实现各抄一份处理代码）。
 *
 * <h2>★ 一条重要的诚实说明：token 计数用的是"近似"</h2>
 *
 * <p>这里用 jtokkit 的 {@code o200k_base} 编码来估算 token 数。
 * <b>它不是 DeepSeek 的官方分词器</b> —— DeepSeek 未公开其分词表。
 *
 * <p>这意味着：
 * <ul>
 *   <li>计数是<b>近似值</b>，用于"控制历史规模"这个目的足够；</li>
 *   <li>它<b>不能</b>用来做精确的计费预测；</li>
 *   <li>真实 token 用量应以<b>API 响应里的 usage 字段</b>为准（阶段可观测性时会采集）。</li>
 * </ul>
 *
 * <p>把这个局限写在代码里而不是藏着，是因为"用一个近似值却声称精确"
 * 属于典型的误导性设计。
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
     * <p>规则（顺序不可换）：
     * <ol>
     *   <li>从<b>最旧的一端</b>开始丢弃；</li>
     *   <li>同时受 token 与条数两个上限约束，<b>谁先到就用谁</b>；</li>
     *   <li>结果按<b>时间正序</b>返回（早 → 晚），可直接拼进 prompt。</li>
     * </ol>
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
            // 注意"单条就超预算"的处理：此时 kept 为空，会直接 break，
            // 即这条也不保留 —— 因为保留它就已经突破预算了。
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
     * <p>除了正文，还要加上<b>每条消息的固定开销</b>（角色标记等）——
     * 这是"为什么纯 token 限制还需要条数上限兜底"的原因：
     * 大量超短消息的正文几乎不占 token，但固定开销会累积。
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
     * 取 4 是一个经验值：足够表达"每条消息都有额外成本"这个事实。
     */
    private static final int MESSAGE_OVERHEAD = 4;
}
