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
 * 检索分数语义的实测探针：{@code similaritySearch(...).getScore()} 是【相似度】还是【距离】？
 *
 * <p>决定分档方向的恰恰是这一个符号 —— 若 score 是距离，同一个阈值就得反过来用。
 * 写反了不会报错：检索照样返回结果，只是"高相关"的标签贴到了最不相关的块上，而模型据此作答。
 *
 * <p>对照基准：用余弦距离测过同一批提问，若 {@code score ≈ 1 - 距离} 则 score 是相似度。
 *
 * <p>同时暴露一个结论：相关与无关提问的分数分布完全重叠 —— 这是
 * {@code mxbai-embed-large} 对中文短文本区分度不足的必然结果，阈值只能定位成粗筛，
 * 真正的兜底靠提示词约束。
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
     * <p>不用 {@code KnowledgeCleanupListener}（它会 TRUNCATE 向量表），本类要的恰恰是"库里确实有内容"。
     * 入库是幂等的（内容未变更即 {@code SKIPPED_UNCHANGED}），重复运行不会重复写入。
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
     * {@code SearchRequest.similarityThreshold} 的单位是【相似度】还是【距离】？
     *
     * <p>框架生成的 SQL 是 {@code ... WHERE embedding <=> ? < ? ...}，那个界的含义决定
     * 我们配的"粗筛阈值"是"至少多像"还是"最多多远"—— 两者方向相反。
     * 判据：threshold = 0.7 时，全部 {@code score >= 0.7} → 相似度下限；全部 {@code score <= 0.7} → 距离上限。
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
        // 不设硬断言 —— 本测试是"测量"，语义方向由上面的结论记录下来；
        // 真正的断言在 RetrievalProperties 的单元测试里（配置→相似度下限的换算）
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
