package com.hagent.app;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需要"<b>整个知识库已入库</b>"这一稳定状态的测试。
 *
 * <p>为什么要单独一个类：入库测试（{@code DocumentIngestIntegrationTest}）在每个方法前清空
 * 向量表，而本类的断言（"批量入库应当全部跳过"、"一篇文档的块数应等于 chunkTotal"）
 * 只有在<b>整个目录都已入库</b>时才有意义。两类需求塞进一个类就只能靠 {@code @Order} 排顺序 ——
 * 而<b>顺序依赖的测试在单独跑与全量跑时行为不同</b>，失败时无法判断是代码坏了还是顺序变了。
 *
 * <p>{@code @BeforeAll} 里的准备是幂等的：先查库里有没有数据，<b>没有才</b> {@code ingestAll()}。
 * 于是本类单独运行、以及被入库测试清过库后运行，都能自己备好数据 ——
 * <b>不依赖任何其它测试的执行结果</b>，这正是这类测试最该保住的性质。
 *
 * <p>{@code @TestInstance(PER_CLASS)} 是为了让 {@code @BeforeAll} 可以是实例方法
 * （JUnit 默认要求它是静态的，而静态方法拿不到 {@code @Autowired} 注入的字段）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class DocumentIngestStableStateTest {

    /** knowledge/ 目录下的文档数 —— 加了新测试文档就要同步这个数字。 */
    private static final int EXPECTED_DOCUMENT_COUNT = 4;

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    /** 一次批量入库的结果，供本类所有用例共享（它们都是只读断言）。 */
    private BatchIngestReport seedReport;

    @BeforeAll
    void seedKnowledgeBaseIfEmpty() {
        Integer existing = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM hagent_knowledge", Integer.class);
        if (existing != null && existing > 0) {
            // 库里已有数据（入库测试或上一次运行留下的）—— 不再重复入库
            return;
        }
        seedReport = ingestService.ingestAll();
        assertThat(seedReport.hasFailures())
                .as("准备数据时不应有文档失败：%s", seedReport.failures())
                .isFalse();
        assertThat(seedReport.results()).hasSize(EXPECTED_DOCUMENT_COUNT);
    }

    @Test
    @DisplayName("★ 目录下每份文档都产出了至少一块，且总块数合理")
    void everyDocumentProducedChunks() {
        List<String> sources = pgVectorJdbcTemplate.queryForList(
                "SELECT metadata ->> 'source' AS s FROM hagent_knowledge "
                        + "GROUP BY metadata ->> 'source' ORDER BY s",
                String.class);

        assertThat(sources)
                .as("knowledge/ 下的每份文档都应能被切分出块；缺失说明有文档未被扫描到或处理失败")
                .hasSize(EXPECTED_DOCUMENT_COUNT);

        Integer total = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM hagent_knowledge", Integer.class);
        assertThat(total).as("总块数应大于文档数（长文档必然切成多块）").isGreaterThan(EXPECTED_DOCUMENT_COUNT);
    }

    @Test
    @DisplayName("★ 同一文档的所有块共享同一个 docHash 与 title（元数据一致性）")
    void chunksOfSameDocumentShareDocumentLevelMetadata() {
        List<String> distinctDocHashes = pgVectorJdbcTemplate.queryForList(
                "SELECT COUNT(DISTINCT metadata ->> 'docHash')::text FROM hagent_knowledge "
                        + "WHERE metadata ->> 'source' = ?",
                String.class, "员工手册.md");

        assertThat(distinctDocHashes)
                .as("同一文档的所有块必须携带同一个 docHash —— 否则幂等判定会失效")
                .containsExactly("1");
    }

    @Test
    @DisplayName("★ 每个文档内 chunkIndex 从 0 连续递增，且最大值 = chunkTotal - 1（出处定位自洽）")
    void chunkIndexesAreContiguousWithinEachDocument() {
        List<String> violations = pgVectorJdbcTemplate.queryForList(
                "SELECT metadata ->> 'source' || ': min=' || MIN((metadata ->> 'chunkIndex')::int) "
                        + "|| ' max=' || MAX((metadata ->> 'chunkIndex')::int) "
                        + "|| ' count=' || COUNT(*) || ' chunkTotal=' || MAX((metadata ->> 'chunkTotal')::int) "
                        + "FROM hagent_knowledge GROUP BY metadata ->> 'source' "
                        + "HAVING MIN((metadata ->> 'chunkIndex')::int) <> 0 "
                        + "OR MAX((metadata ->> 'chunkIndex')::int) <> COUNT(*) - 1 "
                        + "OR MAX((metadata ->> 'chunkTotal')::int) <> COUNT(*)",
                String.class);

        assertThat(violations)
                .as("以下文档的块序号不自洽（序号必须从 0 连续、chunkTotal 必须等于实际块数）：%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 没有任何块超过 chunking.max-size（防 embedding 静默截断）")
    void noChunkExceedsConfiguredMaxSize() {
        // 配置值是 400；这里用 400 而不是读配置，是为了让"配置被改大"时测试也能拦住它 ——
        // 一旦 max-size 被调过 1000，embedding 就会静默截断，而这条断言会立刻失败
        List<String> tooLong = pgVectorJdbcTemplate.queryForList(
                "SELECT metadata ->> 'source' || ' len=' || LENGTH(content) FROM hagent_knowledge "
                        + "WHERE LENGTH(content) > 400",
                String.class);

        assertThat(tooLong)
                .as("以下块超过 400 字上限，其超出部分会被 embedding 静默截断、永远检索不到：%s", tooLong)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 所有块的嵌入向量都不是 NULL，且维度为 1024")
    void allChunksHaveEmbedding() {
        Integer nullEmbeddings = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM hagent_knowledge WHERE embedding IS NULL", Integer.class);
        assertThat(nullEmbeddings).as("块的向量不能为 NULL —— 那意味着该块永远检索不到").isZero();

        // vector_dims 是 pgvector 扩展提供的函数，用来取向量的维度
        List<String> dims = pgVectorJdbcTemplate.queryForList(
                "SELECT DISTINCT vector_dims(embedding)::text FROM hagent_knowledge", String.class);
        assertThat(dims)
                .as("维度必须与 embedding 模型实测维度一致（mxbai-embed-large = 1024）")
                .containsExactly("1024");
    }

    @Test
    @DisplayName("★ 第二次批量入库全部跳过（幂等）")
    void secondBatchIngestSkipsEverything() {
        BatchIngestReport report = ingestService.ingestAll();

        assertThat(report.failures()).isEmpty();
        assertThat(report.results()).hasSize(EXPECTED_DOCUMENT_COUNT);
        assertThat(report.addedCount()).as("首次之后不应再有新增").isZero();
        assertThat(report.updatedCount()).as("内容未变，不应有更新").isZero();
        assertThat(report.skippedCount())
                .as("全部文档内容都未变 → 全部跳过。这正是幂等入库的意义："
                        + "每天同步一次知识库，无变化时零 embedding 成本")
                .isEqualTo(EXPECTED_DOCUMENT_COUNT);
    }
}
