package com.hagent.retrieval;

/**
 * 检索结果的<b>相关度分档</b> —— 高 / 中 / 低。
 *
 * <h2>★ 为什么给分档，而不把原始分数告诉模型</h2>
 *
 * <p>原始分数是<b>余弦相似度</b>（越大越像）。把它直接写进 prompt 有两个问题：
 * <ol>
 *   <li><b>方向会误导。</b>"相似度 0.42"看起来像"不到一半"，也像"42 分"。
 *       模型很可能据此认为这条不可信、或者反过来认为它很可信 ——
 *       而真实的判定边界是 0.55 / 0.70（本项目实测校准出来的）。
 *       一个没有刻度的数字，比一个粗糙的档位更容易被误用。</li>
 *   <li><b>它把校准细节泄漏到了提示词里。</b>将来换 embedding 模型，
 *       分数分布会整体平移，提示词里那句"0.42 算不算高"就跟着失效了；
 *       而档位阈值改在配置里，提示词一个字都不用动。</li>
 * </ol>
 *
 * <p>所以：<b>配置里保留可校准的数值阈值，提示词里只出现档位名。</b>
 * 换模型时改 {@code application.yml}，提示词与代码都不动。
 *
 * <h2>★ 提示词约束依赖这里的取值</h2>
 *
 * <p>{@code prompts/rag-answer-prompt.md} 里有一条约束是
 * "若某片段的【相关度】为「低」，不要用它作答"。它读的就是
 * {@link #displayName()} 这三个字。所以<b>改这里的显示名必须同步改提示词</b> ——
 * 这是一处真实的耦合，已在该提示词文件顶部注明。
 */
public enum RelevanceBand {

    /** 高：距离不超过 high-max-distance（默认 0.30），基本可以放心引用。 */
    HIGH("高"),

    /** 中：距离在 high 与 medium 阈值之间，可以参考，但模型应自行判断是否切题。 */
    MEDIUM("中"),

    /**
     * 低：距离超过 medium-max-distance。
     *
     * <p><b>⚠️ 这个档位必须诚实对待</b>：本项目的实测表明，
     * <b>相关与无关提问的距离分布完全重叠</b>（见 HANDOFF 3.5 与
     * {@code RetrievalScoreProbeTest} 的报告）：
     * <pre>
     *   「请假流程是什么」（相关）→ 距离 0.4585  → 落在【低】
     *   「今天杭州天气怎么样」（无关）→ 距离 0.2598 → 落在【高】
     * </pre>
     * 也就是说<b>"低"不等于"不相关"</b>，只是"比其它候选更远"。
     * 这正是提示词里要求模型"结合问题自行判断"、并禁止编造来源的原因 ——
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
