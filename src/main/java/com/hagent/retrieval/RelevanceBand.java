package com.hagent.retrieval;

/**
 * 检索结果的<b>相关度分档</b> —— 高 / 中 / 低。
 *
 * <p>不把原始分数交给模型：余弦相似度"0.42"是<b>没有刻度的数字</b>（像"不到一半"也像"42 分"），
 * 而真实的判定边界是 0.55 / 0.70（实测校准出来的），它比一个粗糙的档位更容易被误用。
 * 且写进提示词等于把校准细节泄漏进去：换 embedding 模型后分数分布整体平移，那句话就失效 ——
 * 所以<b>配置里保留数值阈值，提示词里只出现档位名</b>，换模型只改 {@code application.yml}。
 *
 * <p><b>提示词约束依赖这里的取值</b>：{@code prompts/rag-answer-prompt.md} 里
 * "若某片段的【相关度】为「低」，不要用它作答"读的就是 {@link #displayName()}，
 * 所以改显示名必须同步改提示词（已在该提示词文件顶部注明）。
 */
public enum RelevanceBand {

    /** 高：距离不超过 high-max-distance（默认 0.30），基本可以放心引用。 */
    HIGH("高"),

    /** 中：距离在 high 与 medium 阈值之间，可以参考，但模型应自行判断是否切题。 */
    MEDIUM("中"),

    /**
     * 低：距离超过 medium-max-distance。
     *
     * <p><b>⚠️ 必须诚实对待这个档位</b>：实测表明<b>相关与无关提问的距离分布完全重叠</b>
     * （见 {@code RetrievalScoreProbeTest}）—— 相关提问「请假流程是什么」距离 0.4585 落到【低】，
     * 无关提问「今天杭州天气怎么样」距离 0.2598 落到【高】。
     * 也就是说<b>"低"不等于"不相关"</b>，只是"比其它候选更远"。
     * 这正是提示词要求模型"结合问题自行判断"、并禁止编造来源的原因：
     * <b>阈值只能粗筛，兜底必须靠提示词约束。</b>
     */
    LOW("低");

    private final String displayName;

    RelevanceBand(String displayName) {
        this.displayName = displayName;
    }

    /** 写进 prompt 的中文档位名。 */
    public String displayName() {
        return displayName;
    }
}
