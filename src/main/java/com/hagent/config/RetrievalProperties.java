package com.hagent.config;

import com.hagent.retrieval.RelevanceBand;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索链路的可调参数，绑定 {@code hagent.rag.retrieval.*}。
 *
 * <p>★ <b>单位一律用"距离"</b>，而不是"相似度"：框架返回的 {@code Document.getScore()} 是
 * <b>相似度</b>（{@code 1 - 余弦距离}，越大越相关，已实测），而本项目校准阈值用的那张表是
 * <b>距离</b>。两种表示各占一半，必须选一边，本项目选距离，理由三条：
 * <ol>
 *   <li><b>与校准证据同单位</b>：换 embedding 模型后重新测一张距离表，照表改配置即可，不用心算"1 减多少"；</li>
 *   <li><b>语义是"上限"</b>："距离不超过 0.30"是封顶值，做筛选时读起来更自然；</li>
 *   <li><b>键名里带 {@code distance}</b>，杜绝"这是哪个单位"的歧义 —— 注释会被忽略，键名不会。</li>
 * </ol>
 *
 * <p>代价是多了一处单位换算。为不让它变成"隐式转换"，换算只允许出现在
 * {@link #similarityThreshold()} 一个方法里，且启动时把<b>两个单位的值一起打进日志</b>
 * （见 {@link #afterPropertiesSet()}）—— 换算结果始终可见。
 *
 * <p>★ 阈值只能"粗筛"，这不是实现缺陷：实测<b>相关提问与无关提问的距离分布完全重叠</b> ——
 * 要让"请假流程是什么"（0.4585）通过需上限 &gt; 0.46，要挡住"今天杭州天气怎么样"（0.2598）
 * 需上限 &lt; 0.26，<b>没有这样的数存在</b>。真正的兜底在
 * {@code prompts/rag-answer-prompt.md} 的提示词约束里，<b>不要指望调大这个数能提升问答质量。</b>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.retrieval")
public class RetrievalProperties implements InitializingBean {

    /**
     * 是否启用检索。关掉后 {@code AgentPipeline} 的第③步不产片段，
     * 链路其余部分照常工作（相当于"没有知识库的普通对话"）。
     *
     * <p>存在的理由：排查"回答质量问题来自检索还是来自模型"时，需要开关整段摘掉检索。
     */
    private boolean enabled = true;

    /**
     * 最多注入几条片段。
     *
     * <p><b>取值依据（3 条的账）</b>：每条块最大 400 字，3 条约 1200 token，占会话历史预算
     * 5000 token 的 <b>24%</b>。再大就会把历史挤掉 —— 多轮对话里历史同样是回答质量的来源。
     */
    private int topK = 3;

    /**
     * 粗筛：<b>距离上限</b>。超过它的候选直接不返回。
     *
     * <p>0.5 的定位是"粗筛"：实测它的作用很弱（十次提问里最近的候选距离最大也只有 0.4853，
     * 全都通过）。<b>这是刻意的</b> —— 宁可放进来由提示词约束处理，
     * 也不要因为阈值过严把"请假流程"这类真正相关的内容挡在外面。
     */
    private double maxDistance = 0.5;

    /** 高相关：距离不超过此值。 */
    private double highMaxDistance = 0.30;

    /** 中相关：距离不超过此值。超过则归为低。 */
    private double mediumMaxDistance = 0.45;


    // ========================================================================
    // 单位换算 —— 唯一允许出现"1 - x"的地方
    // ========================================================================

    /**
     * 换算成框架 {@code SearchRequest.similarityThreshold} 需要的<b>相似度下限</b>。
     *
     * <p>该参数的单位已实测：传 0.70 时只返回 {@code score >= 0.70} 的结果，即<b>相似度下限</b>；
     * {@code 0.0}（{@code SIMILARITY_THRESHOLD_ACCEPT_ALL}）表示不筛。
     */
    public double similarityThreshold() {
        return 1.0 - maxDistance;
    }


    /**
     * 启动期校验 —— <b>配置写错就让应用起不来</b>，而不是运行时静默用错值。
     *
     * <p>这类参数错了<b>不会报错</b>，只会让检索结果变差，而"检索结果变差"没法从日志里看出来，
     * 所以只能在最早的时刻拦住。校验三件事：① topK 为正；② 三个阈值有序（高 ≤ 中 ≤ 粗筛）；
     * ③ 阈值落在余弦距离的合法区间 [0, 2]。其中②最容易写错：把 high 配成比 medium 大，
     * 中间档位就永远不会出现，而<b>分档函数照样正常返回</b>，没有任何提示。
     */
    @Override
    public void afterPropertiesSet() {
        if (topK < 1) {
            throw new IllegalStateException(
                    "hagent.rag.retrieval.top-k 必须 >= 1，当前=" + topK);
        }
        if (maxDistance < 0 || maxDistance > 2) {
            throw new IllegalStateException(
                    "hagent.rag.retrieval.max-distance 必须落在 [0, 2]（余弦距离范围），当前=" + maxDistance);
        }
        if (highMaxDistance < 0 || mediumMaxDistance < 0) {
            throw new IllegalStateException(String.format(
                    "相关度分档阈值不能为负：high-max-distance=%s medium-max-distance=%s",
                    highMaxDistance, mediumMaxDistance));
        }
        if (!(highMaxDistance <= mediumMaxDistance)) {
            throw new IllegalStateException(String.format(
                    "分档阈值必须有序（high <= medium），当前 high-max-distance=%s > medium-max-distance=%s。"
                            + "这种配置下「中」这一档永远不会出现，而分档函数不会报任何错。",
                    highMaxDistance, mediumMaxDistance));
        }
        if (mediumMaxDistance > maxDistance) {
            throw new IllegalStateException(String.format(
                    "medium-max-distance(%s) 不应大于 max-distance(%s)：前者是分档边界，"
                            + "后者是粗筛上限；分档边界超过粗筛上限意味着「低」这一档永远不会出现。",
                    mediumMaxDistance, maxDistance));
        }
        if (!enabled) {
            log.warn("[检索] hagent.rag.retrieval.enabled=false —— 本轮不会做任何知识库检索，"
                    + "回答将完全依赖模型自身知识");
        }

        // 把两个单位的值一起打出来：换算结果始终可见，避免"配了距离、以为是相似度"。
        log.info("[检索] 参数：topK={} 粗筛=距离<= {}（相似度>= {}） 分档=距离(<= {}, <= {}) "
                        + "→ 实际相似度下限(>= {}, >= {})",
                topK, maxDistance, String.format("%.4f", similarityThreshold()),
                highMaxDistance, mediumMaxDistance,
                String.format("%.4f", 1.0 - highMaxDistance),
                String.format("%.4f", 1.0 - mediumMaxDistance));
    }
}
