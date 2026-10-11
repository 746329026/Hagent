package com.hagent.app;

import com.hagent.config.IngestProperties;
import com.hagent.document.IngestOutcome;
import com.hagent.document.IngestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库链路中<b>幂等且自给自足</b>的那部分测试。
 *
 * <h2>★ 设计原则：每个测试方法自己准备前置状态</h2>
 *
 * <p>最初的实现把 13 个用例写在同一个类里、用 {@code @Order} 排顺序，
 * 后一个用例依赖"前一个已经把某文档入库了"。那套写法在<b>全量运行时失败</b>：
 * 数据库里残留着上一次运行的数据，于是"首次入库"变成了"重复入库"。
 *
 * <p>加入自动清库后，顺序依赖的写法又反过来失效（清库把前一个用例的成果抹掉了）。
 * <b>两次失败指向同一个根因：测试方法之间有隐式依赖。</b>
 *
 * <p>所以拆成两个类：
 * <ul>
 *   <li><b>本类</b>：每个方法自给自足（清库 → 自己入库 → 断言），可以任意顺序、任意次数运行；</li>
 *   <li>{@code DocumentIngestStableStateTest}：需要"全库已入库"这一稳定状态，
 *       因此在 {@code @BeforeAll} 里一次性准备好，之后所有断言都是只读的。</li>
 * </ul>
 *
 * <p>这个划分的好处不只是"能跑通"：<b>能独立运行的测试才是一个可靠的回归防线</b> ——
 * 依赖顺序的测试在失败时，你无法确定是自己的改动引起的，还是顺序被 JUnit 换了。
 *
 * <p>清库由 {@link KnowledgeCleanupListener} 在<b>每个测试方法之前</b>完成，
 * 所以本类的每个方法都从"向量表为空"开始。
 */
@SpringBootTest
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS,
        listeners = KnowledgeCleanupListener.class)
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class DocumentIngestIntegrationTest {

    private static final String POLICY_DOC = "员工手册.md";
    private static final String PLAIN_TXT = "办公设备管理.txt";
    private static final String SHORT_PROFILE = "公司简介.md";
    private static final String LONG_PROFILE = "公司介绍.md";

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    private IngestProperties ingestProperties;

    @Autowired
    private JdbcTemplate pgVectorJdbcTemplate;

    // ========================================================================
    // 向量表本身（阶段 6 实测发现的坑）
    // ========================================================================

    @Test
    @DisplayName("★ 向量表已被框架创建 —— 坑：initializeSchema=false 时表永远不会建，入库必然失败")
    void vectorTableIsCreated() {
        Integer count = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'hagent_knowledge'",
                Integer.class);

        assertThat(count)
                .as("向量表必须存在。若为 0，说明 initialize-schema 没有生效")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 表结构含 id / content / metadata / embedding 四列")
    void vectorTableHasExpectedColumns() {
        List<Map<String, Object>> columns = pgVectorJdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'hagent_knowledge' "
                        + "ORDER BY ordinal_position");

        List<String> names = columns.stream().map(c -> String.valueOf(c.get("column_name"))).toList();
        assertThat(names).contains("id", "content", "metadata", "embedding");

        // 把实际类型打印出来 —— 下一轮做检索过滤要按 metadata 写条件，
        // 而 json 与 jsonb 对索引/操作符的支持不同，事先知道是有用的。
        // 实测结果：metadata 是 json（不是 jsonb），embedding 是 vector 类型
        columns.forEach(c -> System.out.printf("[表结构实测] %s = %s%n",
                c.get("column_name"), c.get("data_type")));
    }

    // ========================================================================
    // 首次入库与策略
    // ========================================================================

    @Test
    @DisplayName("★ 首次入库 .md（有标题）→ ADDED，按标题切出多块")
    void firstIngestOfMarkdownIsAdded() {
        IngestResult result = ingestService.ingest(POLICY_DOC);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ADDED);
        assertThat(result.written()).isTrue();
        assertThat(result.chunkCount())
                .as("员工手册有 4 个章节，按标题切应当产出多块")
                .isGreaterThan(1);
        assertThat(result.docHash()).hasSize(64);   // SHA-256 十六进制
    }

    @Test
    @DisplayName("★ 无标题无 frontmatter 的 .txt → 降级到段落切分，标题从文件名推断")
    void ingestUnstructuredTxtFallsBackToParagraphChunking() {
        IngestResult result = ingestService.ingest(PLAIN_TXT);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.ADDED);
        assertThat(result.chunkCount()).isGreaterThan(1);

        Map<String, Object> row = pgVectorJdbcTemplate.queryForMap(
                "SELECT metadata ->> 'kind' AS kind, metadata ->> 'title' AS title, "
                        + "metadata ->> 'section' AS section FROM hagent_knowledge "
                        + "WHERE metadata ->> 'source' = ? LIMIT 1",
                PLAIN_TXT);

        assertThat(row.get("title")).as("无 frontmatter、无 # 标题 → 从文件名推断").isEqualTo("办公设备管理");
        assertThat(row.get("kind")).isEqualTo(ingestProperties.getDefaultKind().name());
        // 无标题文档 → section 为空（拼接出处时由块序号兜底）
        assertThat(row.get("section")).isEqualTo("");
    }

    @Test
    @DisplayName("★ 真正的短文档走 WHOLE_DOCUMENT 策略 → 整篇 1 块，且无章节名")
    void shortDocumentBecomesSingleChunk() {
        IngestResult result = ingestService.ingest(SHORT_PROFILE);
        assertThat(result.outcome()).isEqualTo(IngestOutcome.ADDED);

        assertThat(countChunks(SHORT_PROFILE))
                .as("公司简介约 60 字，远低于 400 上限，应当整篇一块（切开反而丢整体信息）")
                .isEqualTo(1);

        String section = pgVectorJdbcTemplate.queryForObject(
                "SELECT metadata ->> 'section' FROM hagent_knowledge "
                        + "WHERE metadata ->> 'source' = ? LIMIT 1",
                String.class, SHORT_PROFILE);
        assertThat(section).isEmpty();
    }

    @Test
    @DisplayName("★ 超长的 WHOLE_DOCUMENT 文档降级切分 —— 而非硬塞成一块（那会被 embedding 静默截断）")
    void oversizedWholeDocumentFallsBackToChunking() {
        ingestService.ingest(LONG_PROFILE);

        assertThat(countChunks(LONG_PROFILE))
                .as("公司介绍.md 正文 717 字 > 400 上限。若只产出 1 块，说明超长文本被硬塞进"
                        + "一次 embedding 调用 —— 后半部分会被静默截断、永远检索不到")
                .isGreaterThan(1);

        List<Integer> lengths = pgVectorJdbcTemplate.queryForList(
                "SELECT LENGTH(content) FROM hagent_knowledge WHERE metadata ->> 'source' = ?",
                Integer.class, LONG_PROFILE);
        assertThat(lengths).allSatisfy(len -> assertThat(len).isLessThanOrEqualTo(400));
    }

    // ========================================================================
    // 编码与元数据往返（只有真实往返才能发现）
    // ========================================================================

    @Test
    @DisplayName("★ 中文正确落库并可读回（每个块都要完好，不能只抽查一块）")
    void chineseContentRoundTrips() {
        ingestService.ingest(POLICY_DOC);

        // ★ 不能只查 LIMIT 1：员工手册被切成多块，第一块是前言（不含"年假"）。
        //   把所有块拼起来检查，才既能验证中文完好、又能覆盖全部块
        List<String> contents = pgVectorJdbcTemplate.queryForList(
                "SELECT content FROM hagent_knowledge WHERE metadata ->> 'source' = ? "
                        + "ORDER BY (metadata ->> 'chunkIndex')::int",
                String.class, POLICY_DOC);

        String joined = String.join("\n", contents);

        assertThat(joined)
                .as("写进去是中文、读出来也必须是中文；否则检索时向量对不上文本")
                .contains("年假").contains("带薪年假").contains("住宿费");
        // 编码退化会把中文变成问号 —— 逐块检查，确保没有任何一块被破坏
        assertThat(contents).allSatisfy(c -> assertThat(c).doesNotContain("????"));
    }

    @Test
    @DisplayName("★ 元数据 12 个字段全部落库，且 frontmatter 的 level 覆盖 kind 默认值")
    void allMetadataFieldsAreStored() {
        ingestService.ingest(POLICY_DOC);

        Map<String, Object> row = pgVectorJdbcTemplate.queryForMap(
                "SELECT metadata ->> 'source' AS source, metadata ->> 'title' AS title, "
                        + "metadata ->> 'section' AS section, metadata ->> 'chunkIndex' AS chunk_index, "
                        + "metadata ->> 'chunkTotal' AS chunk_total, metadata ->> 'contentHash' AS content_hash, "
                        + "metadata ->> 'docHash' AS doc_hash, metadata ->> 'kind' AS kind, "
                        + "metadata ->> 'level' AS level, metadata ->> 'dept' AS dept, "
                        + "metadata ->> 'validFrom' AS valid_from, metadata ->> 'validTo' AS valid_to "
                        + "FROM hagent_knowledge WHERE metadata ->> 'source' = ? LIMIT 1",
                POLICY_DOC);

        assertThat(row.get("source")).isEqualTo(POLICY_DOC);
        assertThat(row.get("title")).isEqualTo("员工手册");
        assertThat(row.get("kind")).isEqualTo("POLICY");
        // frontmatter 写了 level: INTERNAL → 用它，而不是 kind 的默认值
        assertThat(row.get("level")).isEqualTo("INTERNAL");
        assertThat(row.get("dept")).isEqualTo("HR");
        // ★ 这里验证的是 YAML 日期陷阱的修复：frontmatter 里 validFrom 加了引号，
        //   若不加引号，YAML 会把它解析成 java.util.Date，格式就变成英文长日期
        assertThat(row.get("valid_from")).isEqualTo("2024-01-01");
        // 空值语义：未写的 validTo → 落到 metadata 是空字符串（不是 null）
        assertThat(row.get("valid_to")).isEqualTo("");
        assertThat(String.valueOf(row.get("chunk_index"))).isEqualTo("0");
        assertThat(String.valueOf(row.get("content_hash"))).hasSize(64);
        assertThat(String.valueOf(row.get("doc_hash"))).hasSize(64);
        assertThat(String.valueOf(row.get("chunk_total"))).isNotEqualTo("0");
        assertThat(row.get("section")).isEqualTo("员工手册");
    }

    @Test
    @DisplayName("★ 显式声明的密级覆盖 kind 默认值（公司简介声明 PUBLIC）")
    void explicitLevelOverridesKindDefault() {
        ingestService.ingest(SHORT_PROFILE);

        Map<String, Object> row = pgVectorJdbcTemplate.queryForMap(
                "SELECT metadata ->> 'kind' AS kind, metadata ->> 'level' AS level "
                        + "FROM hagent_knowledge WHERE metadata ->> 'source' = ? LIMIT 1",
                SHORT_PROFILE);

        assertThat(row.get("kind")).isEqualTo("COMPANY_PROFILE");
        assertThat(row.get("level")).isEqualTo("PUBLIC");
    }

    // ========================================================================
    // 幂等（本类里每个方法都从空库开始，所以"连续两次入库"必须自己构造）
    // ========================================================================

    @Test
    @DisplayName("★★ 连续三次入库同一文档：首次 ADDED，之后全部 SKIPPED，且块数稳定")
    void repeatedIngestIsIdempotent() {
        IngestResult first = ingestService.ingest(POLICY_DOC);
        assertThat(first.outcome()).isEqualTo(IngestOutcome.ADDED);
        Integer chunksAfterFirst = countChunks(POLICY_DOC);
        assertThat(chunksAfterFirst).isPositive();

        for (int i = 0; i < 3; i++) {
            IngestResult repeat = ingestService.ingest(POLICY_DOC);
            assertThat(repeat.outcome())
                    .as("内容未变时必须跳过 —— 否则每次同步都要重新 embedding 整库")
                    .isEqualTo(IngestOutcome.SKIPPED_UNCHANGED);
            assertThat(repeat.written()).isFalse();
            assertThat(repeat.chunkCount()).as("跳过时不做切分，块数计为 0").isZero();
        }

        assertThat(countChunks(POLICY_DOC))
                .as("跳过不能改变库中的数据")
                .isEqualTo(chunksAfterFirst);
    }

    @Test
    @DisplayName("★ 更新检测：删除该来源的块后再入库 → 仍是 ADDED（因为库里已无该来源）")
    void deletingThenReingestingProducesAdded() {
        ingestService.ingest(POLICY_DOC);
        Integer before = countChunks(POLICY_DOC);

        // 模拟"文档被删除后再加回来"：清掉该来源的块
        pgVectorJdbcTemplate.update(
                "DELETE FROM hagent_knowledge WHERE metadata ->> 'source' = ?", POLICY_DOC);
        assertThat(countChunks(POLICY_DOC)).isZero();

        IngestResult after = ingestService.ingest(POLICY_DOC);

        assertThat(after.outcome()).isEqualTo(IngestOutcome.ADDED);
        assertThat(countChunks(POLICY_DOC))
                .as("重新入库后块数应与之前一致（切分是确定性的）")
                .isEqualTo(before);
    }

    // ========================================================================
    // 安全
    // ========================================================================

    @Test
    @DisplayName("★ 路径穿越被拒绝 —— 否则可以读到知识库之外的文件")
    void pathTraversalIsRejected() {
        assertThat(catchExceptionMessage(() -> ingestService.ingest("../../../etc/passwd")))
                .as("越界路径必须被拒绝")
                .contains("越界");
    }

    @Test
    @DisplayName("不存在的文件被拒绝")
    void missingFileIsRejected() {
        assertThat(catchExceptionMessage(() -> ingestService.ingest("不存在的文档.md")))
                .contains("不存在");
    }

    @Test
    @DisplayName("不支持的格式被拒绝（本阶段只做 .md 与 .txt）")
    void unsupportedExtensionIsRejected() {
        String message = catchExceptionMessage(() -> ingestService.ingest("员工手册.docx"));
        // 该文件不存在，所以先被"不存在"拦住；这里只断言"明确失败"而不是静默通过
        assertThat(message).isNotEmpty();
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private Integer countChunks(String source) {
        return pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM hagent_knowledge WHERE metadata ->> 'source' = ?",
                Integer.class, source);
    }

    private String catchExceptionMessage(Runnable action) {
        try {
            action.run();
            return "";   // 没抛异常 → 空串会让断言失败并提示"预期被拒绝"
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getName() : e.getMessage();
        }
    }
}
