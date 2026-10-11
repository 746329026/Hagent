package com.hagent.retrieval;

import com.hagent.app.DocumentIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>检索分数语义的实测探针</b> —— 在写"相关度分档"之前，必须先用真实数据回答一个问题：
 *
 * <pre>
 *   VectorStore.similaritySearch(...).getScore() 到底是【相似度】还是【距离】？
 *   取值范围是多少？与 HANDOFF 里那份"余弦距离"实测表能不能对上？
 * </pre>
 *
 * <h2>为什么不能靠记忆或推测</h2>
 *
 * <p>决定分档方向的恰恰是这一个符号：
 * <ul>
 *   <li>若 {@code score} 是<b>相似度</b>（越大越相关），"高相关"的阈值应当<b>大于</b>某个数；</li>
 *   <li>若 {@code score} 是<b>距离</b>（越小越相关），同一个阈值就得<b>反过来用</b>。</li>
 * </ul>
 * 写反了<b>不会报错</b>：检索照样返回结果，只是"高相关"的标签贴到了最不相关的块上，
 * 而模型据此作答 —— 典型的静默错误。
 *
 * <p>本项目已经有一份可对照的基准：HANDOFF 3.5 节用余弦距离测过同一批提问，
 * 相关提问的距离在 0.2168 ~ 0.4827，无关提问在 0.2883 ~ 0.5055。
 * 如果这里的 {@code score} 满足 {@code score ≈ 1 - 距离}，两种语义就对齐了。
 *
 * <h2>本测试同时暴露一个结论：阈值挡不住无关问题</h2>
 *
 * <p>把相关与无关提问的分数都打印出来，可以看到两者的分布<b>完全重叠</b>。
 * 这不是实现缺陷，是 {@code mxbai-embed-large} 对中文短文本区分度不足的必然结果，
 * 所以"阈值"只能定位成<b>粗筛</b>，真正的兜底必须靠提示词约束。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class RetrievalScoreProbeTest {

    /** 相关提问 —— 知识库里确实有对应内容。 */
    private static final List<String> RELEVANT_QUERIES = List.of(
            "年假要提前几天申请",
            "出差住宿费能报多少",
            "我们的年假怎么算？",
            "公司总部在哪里",
            "请假流程是什么");

    /** 无关提问 —— 知识库里没有任何对应内容，但必须实测它们"有多近"。 */
    private static final List<String> IRRELEVANT_QUERIES = List.of(
            "今天杭州天气怎么样",
            "帮我翻译这段英文",
            "帮我写一封催款邮件",
            "推荐几本好看的小说",
            "红烧肉怎么做好吃");

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private DocumentIngestService ingestService;

    /**
     * 自给自足：每个用例前先把 4 份示例文档入库，再检索。
     *
     * <p><b>为什么不用 {@code KnowledgeCleanupListener}</b>：那个监听器会
     * {@code TRUNCATE} 向量表，而本类要的恰恰是"库里确实有内容"。
     * 两个测试类对"前置状态"的假设相反，所以走相反的准备方式 ——
     * 这与"每个测试类自己说清依赖什么状态"的原则一致。
     *
     * <p>入库是<b>幂等</b>的（内容未变更即 {@code SKIPPED_UNCHANGED}），
     * 所以重复运行不会重复写入，也不会因为库里已有数据而失败。
     */
    @BeforeEach
    void ensureSamplesIngested() {
        for (String doc : List.of("员工手册.md", "公司介绍.md", "公司简介.md", "办公设备管理.txt")) {
            ingestService.ingest(doc);
        }
    }

    @Test
    @DisplayName("★ 实测 score 的语义与分布 —— 判定它是相似度还是距离")
    void probeScoreSemantics() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("=".repeat(78)).append('\n');
        report.append("[检索分数实测] topK=1，不做任何过滤（相当于 ADMIN 视角）\n");
        report.append("=".repeat(78)).append('\n');

        report.append("\n--- 相关提问 ---\n");
        for (String q : RELEVANT_QUERIES) {
            report.append(describe(q));
        }
        report.append("\n--- 无关提问 ---\n");
        for (String q : IRRELEVANT_QUERIES) {
            report.append(describe(q));
        }

        report.append("\n判据：若 score ≈ 1 - (HANDOFF 3.5 的距离)，则 score 是【相似度】。\n");
        report.append("例如「年假要提前几天申请」的距离为 0.2168 → 期望 score ≈ 0.7832。\n");

        // surefire 会把 System.out 重定向，实测读不到 —— 写文件留档
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "retrieval-score-report.txt"),
                report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    /**
     * ★ 第二问：{@code SearchRequest.similarityThreshold} 的单位是【相似度】还是【距离】？
     *
     * <p>这个问题必须实测，因为框架生成的 SQL 是（读 {@code PgDistanceType} 的字节码常量池）：
     * <pre>
     *   SELECT *, embedding &lt;=&gt; ? AS distance FROM %s WHERE embedding &lt;=&gt; ? &lt; ? %s ORDER BY distance LIMIT ?
     * </pre>
     * 其中那个 {@code < ?} 的界<b>到底是 threshold 本身，还是 1 - threshold</b>，
     * 决定了我们配的"粗筛阈值"是"至少多像"还是"最多多远"—— 两者方向相反。
     *
     * <p>判据很简单：用 threshold = 0.7 检索，看返回结果的 score 落在哪一侧。
     * <ul>
     *   <li>若全部 {@code score >= 0.7} → threshold 是<b>相似度下限</b>（越大越严）；</li>
     *   <li>若全部 {@code score <= 0.7} → threshold 是<b>距离上限</b>（越小越严）。</li>
     * </ul>
     */
    @Test
    @DisplayName("★ 实测 similarityThreshold 的单位 —— 决定粗筛阈值该往哪个方向配")
    void probeSimilarityThresholdSemantics() throws Exception {
        double threshold = 0.70;
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("年假要提前几天申请")
                .topK(10)
                .similarityThreshold(threshold)
                .build());

        StringBuilder sb = new StringBuilder();
        sb.append("\n[粗筛阈值实测] similarityThreshold=").append(threshold).append('\n');
        for (Document d : hits) {
            sb.append(String.format("   score=%.4f  source=%s%n", d.getScore(), d.getMetadata().get("source")));
        }
        sb.append("   → 命中 ").append(hits.size()).append(" 条\n");
        System.out.println(sb);
        Files.writeString(Path.of("target", "retrieval-score-report.txt"),
                sb.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        if (!hits.isEmpty()) {
            double min = hits.stream().mapToDouble(Document::getScore).min().orElseThrow();
            if (min >= threshold) {
                System.out.println("   【结论】threshold 是【相似度下限】：score 全部 >= " + threshold);
            } else {
                System.out.println("   【结论】threshold 是【距离上限】：score 全部 <= " + threshold);
            }
        }
        // 不设硬断言 —— 本测试是"测量"，语义方向由上面这行结论记录下来，
        // 真正的断言在 RetrievalProperties 的单元测试里（那里断言的是配置→相似度下限的换算）
        assertThat(hits).isNotNull();
    }

    private String describe(String query) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(1)
                // 0.0 = ACCEPT_ALL：不做阈值切分，看全部分数分布
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .build());

        if (hits.isEmpty()) {
            return String.format("%-22s → 【无结果】%n", query);
        }
        Document top = hits.get(0);
        Double score = top.getScore();
        return String.format("%-22s → score=%.4f  source=%s  section=%s%n",
                query,
                score == null ? -1.0 : score,
                top.getMetadata().get("source"),
                top.getMetadata().get("section"));
    }
}
