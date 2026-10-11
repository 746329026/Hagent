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
 * <h2>★ 单位：本类<b>一律用"距离"</b>，而不是"相似度"</h2>
 *
 * <p>这里有一个必须显式决策的地方：框架返回的 {@code Document.getScore()} 是
 * <b>相似度</b>（{@code 1 - 余弦距离}，越大越相关，已实测），
 * 而本项目校准阈值用的那张表（HANDOFF 3.5 与
 * {@code RetrievalScoreProbeTest} 的报告）是<b>距离</b>。
 *
 * <p>两种表示各占一半，必然要选一边。本项目选<b>距离</b>，理由：
 * <ol>
 *   <li><b>与校准证据同单位。</b>换 embedding 模型后要重新测一张距离表，
 *       照表改配置即可，中间不需要心算"1 减多少"；</li>
 *   <li><b>语义是"上限"。</b>"距离不超过 0.30"是一个封顶值，
 *       而"相似度不低于 0.70"是下限 —— 前者在做"筛选"时读起来更自然；</li>
 *   <li><b>配置项名里带 {@code distance} 字样</b>，杜绝"这是哪个单位"的歧义。
 *       只靠注释说明单位是不够的 —— 注释会被忽略，键名不会。</li>
 * </ol>
 *
 * <p><b>代价与对冲</b>：代码里多了一处单位换算。为不让它变成"隐式转换"，
 * 换算只允许发生在 {@link #similarityThreshold()} 与 {@link #bandOf(double)} 两个方法里，
 * 且启动时把<b>两个单位的值一起打进日志</b>（见 {@link #afterPropertiesSet()}）——
 * 换算结果始终可见，不会静默。
 *
 * <h2>★ 阈值只能"粗筛"，这不是实现缺陷</h2>
 *
 * <p>本项目的实测结论（HANDOFF 3.5 + {@code RetrievalScoreProbeTest}）：
 * <b>相关提问与无关提问的距离分布完全重叠</b>。要让"请假流程是什么"（距离 0.4585）通过，
 * 得上限 &gt; 0.46；要挡住"今天杭州天气怎么样"（距离 0.2598），得上限 &lt; 0.26 ——
 * <b>没有这样的数存在</b>。
 *
 * <p>所以 {@link #maxDistance} 的定位是"把明显不沾边的挡掉"，真正的兜底在
 * {@code prompts/rag-answer-prompt.md} 的提示词约束里。
 * <b>不要指望调大这个数能提升问答质量。</b>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.retrieval")
public class RetrievalProperties implements InitializingBean {

    /**
     * 是否启用检索。关掉后 {@code RagRetrievalAdvisor} 不产片段，
     * 链路其余部分照常工作（此时相当于"没有知识库的普通对话"）。
     *
     * <p>存在的理由：排查"回答质量问题到底来自检索还是来自模型"时，
     * 需要一个开关把检索整段摘掉，而不是改代码。
     */
    private boolean enabled = true;

    /**
     * 最多注入几条片段。
     *
     * <p><b>取值依据（3 条的账）</b>：每条块最大 400 字（{@code chunking.max-size}），
     * 3 条约 1200 字 ≈ 1200 token，占会话历史预算 5000 token 的 <b>24%</b>。
     * 再大就会把历史挤掉 —— 而多轮对话里历史同样是回答质量的来源。
     */
    private int topK = 3;

    /**
     * 粗筛：<b>距离上限</b>。超过它的候选直接不返回。
     *
     * <p>0.5 的定位是"粗筛"（决策 15）。实测它的作用很弱 ——
     * 十次提问里最近的候选距离最大也只有 0.4853，全都通过。
     * <b>这是刻意的</b>：宁可放进来由提示词约束处理，也不要因为阈值过严
     * 把"请假流程"这类真正相关的内容挡在外面。
     */
    private double maxDistance = 0.5;

    /** 高相关：距离不超过此值（决策 5）。 */
    private double highMaxDistance = 0.30;

    /** 中相关：距离不超过此值（决策 5）。超过则归为低。 */
    private double mediumMaxDistance = 0.45;

    /**
     * 分档比较的容差 —— <b>为什么必须有它</b>。
     *
     * <p>{@code bandOf} 要算 {@code distance = 1.0 - similarity}，而这一步在浮点下
     * <b>不精确</b>：
     * <pre>
     *   1.0 - 0.70 = 0.30000000000000004     ← 不是 0.30
     * </pre>
     * 于是"相似度 0.70、阈值 0.30"这个<b>恰好落在边界</b>的输入，
     * 会因为 {@code 0.30000000000000004 <= 0.30} 为假而被判成【中】——
     * 而按设计它应当是【高】。
     *
     * <p>这个偏差只有 {@code 1e-16}，但它的表现是"边界那一档偶尔判错"，
     * <b>没有异常、没有日志</b>。所以显式给一个远大于浮点误差、
     * 又远小于任何真实分数间距（实测分数间距在 0.01 量级）的容差。
     *
     * <p>这条是<b>评审时被单元测试抓出来的</b>（{@code RetrievalPropertiesTest}）——
     * 真实检索永远不会恰好落在边界上，所以手工测试一辈子碰不到它。
     */
    private static final double BAND_EPSILON = 1e-9;

    // ========================================================================
    // 单位换算 —— 唯一允许出现"1 - x"的地方
    // ========================================================================

    /**
     * 换算成框架 {@code SearchRequest.similarityThreshold} 需要的<b>相似度下限</b>。
     *
     * <p>该参数的单位已实测确认（{@code RetrievalScoreProbeTest}）：
     * 传 0.70 时只返回 {@code score >= 0.70} 的结果，即<b>相似度下限</b>，
     * {@code 0.0}（{@code SIMILARITY_THRESHOLD_ACCEPT_ALL}）表示不筛。
     */
    public double similarityThreshold() {
        return 1.0 - maxDistance;
    }

    /**
     * 按<b>距离</b>把相似度分档。
     *
     * <p>边界取"不超过"（{@code <=}）而非"小于"：阈值本身恰好命中时算作更相关的一档。
     * 这条选择必须写死在一个地方，否则"0.30 到底算高还是中"会随调用点变化。
     *
     * <p>比较带 {@link #BAND_EPSILON} 容差 —— 因为 {@code 1 - x} 在浮点下不精确，
     * 详见该常量的注释。这一条不写就会"边界那一档偶尔判错"。
     */
    public RelevanceBand bandOf(double similarity) {
        double distance = 1.0 - similarity;
        if (distance <= highMaxDistance + BAND_EPSILON) {
            return RelevanceBand.HIGH;
        }
        if (distance <= mediumMaxDistance + BAND_EPSILON) {
            return RelevanceBand.MEDIUM;
        }
        return RelevanceBand.LOW;
    }

    /**
     * 启动期校验 —— <b>配置写错就让应用起不来</b>，而不是运行时静默用错值。
     *
     * <p>与 {@code MemoryConfigValidator}、{@code vectorTableSelfCheck} 同一立场（决策 8）：
     * 这类参数错了<b>不会报错</b>，只会让检索结果变差 ——
     * 而"检索结果变差"是没法从日志里看出来的。所以只能在最早的时刻拦住。
     *
     * <p>校验的是三件事：① topK 为正；② 三个阈值有序（高 ≤ 中 ≤ 粗筛）；
     * ③ 阈值落在余弦距离的合法区间 [0, 2] 内。
     * 其中②最容易写错：把 high 配成比 medium 大，则中间的档位永远不会出现，
     * 而<b>分档函数照样正常返回</b>，没有任何提示。
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
