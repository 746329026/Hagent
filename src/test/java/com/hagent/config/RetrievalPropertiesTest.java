package com.hagent.config;

import com.hagent.retrieval.RelevanceBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>阈值单位换算与分档边界</b> —— 两件"不报错但结果错"的事，必须钉死。
 *
 * <p>为什么单位换算要单独测：配置用【距离】（与校准证据同单位），而框架的
 * {@code SearchRequest.similarityThreshold} 与 {@code Document.getScore()} 用【相似度】。
 * 一处 {@code 1 - x} 写反或漏写，<b>不会有任何异常</b>：
 * <ul>
 *   <li>{@code similarityThreshold} 算错 → 返回的候选多几条或少几条，看起来"检索质量一般"；</li>
 *   <li>{@code bandOf} 算错 → "高相关"标签贴到了最不相关的块上，而模型据此作答。</li>
 * </ul>
 *
 * <p>边界值是重点：分档用 {@code <=} 还是 {@code <}，只在"分数恰好等于阈值"时有差别 ——
 * 而这种输入在真实检索里几乎不会出现，<b>手工测试永远碰不到它</b>，
 * 单元测试在这里直接喂精确的边界值补上。
 */
class RetrievalPropertiesTest {

    /** 默认配置：粗筛距离 0.5，分档 0.30 / 0.45。 */
    private RetrievalProperties defaults() {
        return new RetrievalProperties();
    }

    // ========================================================================
    // 单位换算
    // ========================================================================

    @Test
    @DisplayName("★ 距离上限 0.5 → 相似度下限 0.5（1 - 0.5）")
    void distanceLimitConvertsToSimilarityFloor() {
        RetrievalProperties props = defaults();
        props.setMaxDistance(0.5);

        assertThat(props.similarityThreshold())
                .as("配置是【距离上限】，框架要【相似度下限】")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("★ 换一组数值确认换算方向：距离上限 0.3 → 相似度下限 0.7（更大 = 更严）")
    void smallerDistanceMeansStricterSimilarityFloor() {
        RetrievalProperties props = defaults();
        props.setMaxDistance(0.3);

        assertThat(props.similarityThreshold())
                .as("距离上限越小越严 → 相似度下限越大。若换算写反，这里会得到 0.3 —— "
                        + "那会让粗筛从『严格』变成『宽松』，且不报错")
                .isEqualTo(0.7);
    }

    // ========================================================================
    // 分档边界
    // ========================================================================

    @Test
    @DisplayName("★ 分档边界：距离 0.30 恰好命中【高】，0.45 恰好命中【中】")
    void bandBoundariesAreInclusive() {
        RetrievalProperties props = defaults();

        // 距离 0.30 → 相似度 0.70
        assertThat(settingsOf(props).bandOf(0.70))
                .as("边界取『不超过』（<=），所以恰好等于阈值算作更相关的一档")
                .isEqualTo(RelevanceBand.HIGH);

        // 距离 0.45 → 相似度 0.55
        assertThat(settingsOf(props).bandOf(0.55)).isEqualTo(RelevanceBand.MEDIUM);

        // 略超边界
        assertThat(settingsOf(props).bandOf(0.6999)).isEqualTo(RelevanceBand.MEDIUM);
        assertThat(settingsOf(props).bandOf(0.5499)).isEqualTo(RelevanceBand.LOW);
    }

    @Test
    @DisplayName("★ 用实测数据核对分档 —— 相关度分档对真实分数给出的档位")
    void bandMatchesMeasuredScores() {
        RetrievalProperties props = defaults();

        // 下表取自 target/retrieval-score-report.txt（真的跑出来的分数）
        assertThat(settingsOf(props).bandOf(0.7495)).as("年假要提前几天申请").isEqualTo(RelevanceBand.HIGH);
        assertThat(settingsOf(props).bandOf(0.7582)).as("出差住宿费能报多少").isEqualTo(RelevanceBand.HIGH);
        assertThat(settingsOf(props).bandOf(0.7369)).as("我们的年假怎么算？").isEqualTo(RelevanceBand.HIGH);
        assertThat(settingsOf(props).bandOf(0.8171)).as("公司总部在哪里").isEqualTo(RelevanceBand.HIGH);
        assertThat(settingsOf(props).bandOf(0.5415)).as("请假流程是什么（相关却落【低】）").isEqualTo(RelevanceBand.LOW);

        // 无关提问也拿到高分 —— 这不是 bug，是模型的区分度问题。
        // 写下来是为了让"阈值挡不住无关问题"这件事在被遗忘之前留档。
        assertThat(settingsOf(props).bandOf(0.7402))
                .as("今天杭州天气怎么样 —— 无关，却落【高】。提示词约束正是为它而设")
                .isEqualTo(RelevanceBand.HIGH);
    }

    @Test
    @DisplayName("★ score 缺失（null）时落到 0.0 → 【低】档，而不是「看起来不错」的中间值")
    void missingScoreFallsToLowestBand() {
        RetrievalProperties props = defaults();
        assertThat(settingsOf(props).bandOf(0.0)).isEqualTo(RelevanceBand.LOW);
    }

    @Test
    @DisplayName("★★ 浮点边界：1 - 0.70 在 double 下是 0.30000000000000004，分档必须带容差")
    void floatingPointBoundaryStillClassifiesCorrectly() {
        RetrievalProperties props = defaults();

        // 这正是本类存在的价值之一：真实检索永远不会恰好落在边界上，
        // 所以这个偏差在手工测试里一辈子碰不到 —— 而它会让"边界那一档"判错。
        double inexact = 1.0 - 0.70;
        assertThat(inexact)
                .as("先把浮点事实本身钉住：它确实不是精确的 0.30")
                .isNotEqualTo(0.30)
                .isGreaterThan(0.30);

        // 没有容差时 0.30000000000000004 <= 0.30 为假 → 会被判成【中】
        // 带容差后应正确判成【高】
        assertThat(settingsOf(props).bandOf(0.70))
                .as("相似度 0.70 恰好是【高】的边界，必须判成高")
                .isEqualTo(RelevanceBand.HIGH);

        // 中档边界同理
        assertThat(settingsOf(props).bandOf(0.55)).isEqualTo(RelevanceBand.MEDIUM);
    }

    // ========================================================================
    // 启动期校验（配置写错就起不来）
    // ========================================================================

    @Test
    @DisplayName("★ high > medium 时必须启动失败 —— 这种配置下【中】档永远不出现，却不报错")
    void rejectsUnorderedBandThresholds() {
        RetrievalProperties props = defaults();
        props.setHighMaxDistance(0.45);
        props.setMediumMaxDistance(0.30);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须有序");
    }

    @Test
    @DisplayName("★ medium > max 时必须启动失败 —— 否则【低】档永远不出现")
    void rejectsMediumBeyondCoarseLimit() {
        RetrievalProperties props = defaults();
        props.setMaxDistance(0.4);
        props.setMediumMaxDistance(0.45);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("分档边界");
    }

    @Test
    @DisplayName("★ topK < 1 时必须启动失败")
    void rejectsNonPositiveTopK() {
        RetrievalProperties props = defaults();
        props.setTopK(0);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k");
    }

    @Test
    @DisplayName("★ 距离超出余弦范围 [0,2] 时必须启动失败")
    void rejectsOutOfRangeDistance() {
        RetrievalProperties props = defaults();
        props.setMaxDistance(3.0);

        assertThatThrownBy(props::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[0, 2]");
    }

    @Test
    @DisplayName("默认配置必须通过校验（否则应用根本起不来）")
    void defaultConfigurationIsValid() {
        RetrievalProperties props = defaults();
        props.afterPropertiesSet();

        assertThat(props.getTopK()).isEqualTo(3);
        assertThat(props.getMaxDistance()).isEqualTo(0.5);
        assertThat(props.getHighMaxDistance()).isEqualTo(0.30);
        assertThat(props.getMediumMaxDistance()).isEqualTo(0.45);
    }

    /** 分档规则住在领域值对象里 —— 属性类只负责绑定配置，不再持有领域规则。 */
    private static com.hagent.retrieval.RetrievalSettings settingsOf(com.hagent.config.RetrievalProperties p) {
        return new com.hagent.retrieval.RetrievalSettings(
                p.isEnabled(), p.getTopK(), p.similarityThreshold(),
                p.getMaxDistance(), p.getHighMaxDistance(), p.getMediumMaxDistance());
    }
}
