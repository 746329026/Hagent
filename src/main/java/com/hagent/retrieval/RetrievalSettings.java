package com.hagent.retrieval;

/**
 * <b>检索能力的设置值对象</b>（阶段 12 第 3 步 / 方案 A）。
 *
 * <h2>★ 为什么领域类不再直接吃 {@code RetrievalProperties}</h2>
 *
 * <p>之前 {@code KnowledgeSearch} 的构造器接收 {@code config.RetrievalProperties}，
 * 于是形成 {@code config ⇄ retrieval} 的结构环，且领域类手里握着一个
 * <b>能读全项目配置</b>的句柄 —— 它实际只用到 5 个值。
 *
 * <p>现在领域只认<b>自己的</b>值对象：装配层（{@code RagConfig}）负责把
 * {@code RetrievalProperties} 映射成它。好处有三：
 * <ol>
 *   <li>领域包不再依赖 {@code config}，环消失；</li>
 *   <li>"档位判定"这条<b>领域规则</b>从配置类搬回领域（审计 P1-10）；</li>
 *   <li>值对象不可变，测试里可以随便造（不用构造整个配置类）。</li>
 * </ol>
 *
 * <h2>★ 为什么 {@code enabled} 也在这里</h2>
 *
 * <p>它是"这次检索能力用不用"的开关，与其余 5 个检索参数同属"检索的设置"。
 * 装配层另外还用它决定<b>要不要注册工具</b>（{@code ToolRegistration}），
 * 所以同一个值有两个消费者，放在同一个值对象里才不会出现两份来源。
 *
 * @param enabled             是否启用检索能力（关闭时模型看不到检索工具）
 * @param topK                取回几条片段
 * @param similarityThreshold 向量库侧的下限（粗筛）
 * @param maxDistance         档位判定的"粗筛"边界
 * @param highMaxDistance     判为"高"的边界
 * @param mediumMaxDistance   判为"中"的边界
 */
public record RetrievalSettings(
        boolean enabled,
        int topK,
        double similarityThreshold,
        double maxDistance,
        double highMaxDistance,
        double mediumMaxDistance) {

    /**
     * 浮点比较的容差。
     *
     * <p>★ 不写它就会出现"边界那一档偶尔判错"：{@code 1.0 - 0.70} 在浮点下是
     * {@code 0.30000000000000004}，于是 {@code <= 0.30} 为假。
     */
    private static final double BAND_EPSILON = 1e-9;

    /**
     * 按<b>距离</b>把相似度分档（阶段 12 从 {@code RetrievalProperties} 搬来）。
     *
     * <p>边界取"不超过"（{@code <=}）：阈值本身恰好命中时算作更相关的一档。
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
}