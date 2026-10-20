package com.hagent.app;

import com.hagent.retrieval.KnowledgeSearch;

import com.hagent.document.AccessLevel;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.Role;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>权限过滤的端到端验证</b> —— 本项目里"最重要的一条断言"。
 *
 * <h2>★ 为什么必须打真库，不能只做单元测试</h2>
 *
 * <p>{@code RetrievalFilterBuilderTest} 只能证明"生成的 SQL 长这样"，
 * 它证明不了三件事：
 * <ol>
 *   <li><b>这个条件在真实 pgvector 上真的能执行</b> ——
 *       向量表的 {@code metadata} 列实测是 {@code json} 而不是 {@code jsonb}，
 *       若框架生成的 jsonpath 不被接受，查询会直接抛异常；</li>
 *   <li><b>条件真的作用在检索上</b>（而不是被某个开关忽略）；</li>
 *   <li><b>结果里确实没有越权内容</b> —— 这是唯一的、真正的验收标准。</li>
 * </ol>
 *
 * <h2>★ 一个必须警惕的陷阱：失败会伪装成"没命中"</h2>
 *
 * <p>{@code KnowledgeSearch} 在检索失败时<b>降级返回空列表</b>
 * （设计如此：知识检索不在关键路径上）。于是一个<b>坏掉的过滤条件</b>
 * 与"知识库里确实没有相关内容"在结果上完全一样 —— 都是空列表。
 *
 * <p>所以本类的第一个用例特意断言"员工视角<b>必须</b>检索到 INTERNAL 内容"：
 * 若过滤条件在真实库上不可执行，那次查询会抛异常、被降级成空列表，
 * 这条断言就会失败。<b>它同时验证了"过滤生效"与"过滤可执行"两件事。</b>
 *
 * <h2>★ 数据前置</h2>
 *
 * <p>用 {@link KnowledgeCleanupListener} 在每个方法前清库（自给自足），
 * 再由本类自己把 4 份示例文档入库 —— 与
 * {@code DocumentIngestIntegrationTest} 的做法一致：
 * <b>能独立运行的测试才是可靠的回归防线</b>。
 */
@SpringBootTest
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS,
        listeners = KnowledgeCleanupListener.class)
@TestPropertySource(properties = {
        "hagent.probe.live-call=false",
        "hagent.rag.ingest.root-dir=${user.dir}/knowledge"
})
class RetrievalPermissionIntegrationTest {

    private static final List<String> SAMPLE_DOCS =
            List.of("员工手册.md", "公司介绍.md", "公司简介.md", "办公设备管理.txt");

    /** 一个"答案明确在员工手册里"的提问 —— 实测相似度 0.7495，落在【高】档。 */
    private static final String POLICY_QUERY = "年假要提前几天申请";

    /** 员工手册的源文件名 —— 它在库里是 INTERNAL 密级。 */
    private static final String INTERNAL_SOURCE = "员工手册.md";

    @Autowired
    private KnowledgeSearch retrievalService;

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    private final UserContext anonymous = UserContext.ANONYMOUS;
    private final UserContext employee = new UserContext("zhangsan", Role.EMPLOYEE);
    private final UserContext admin = new UserContext("root", Role.ADMIN);

    @BeforeEach
    void ingestSamples() {
        SAMPLE_DOCS.forEach(ingestService::ingest);
    }

    // ========================================================================
    // 一、前提：库里确实有不同密级的内容
    // ========================================================================

    @Test
    @DisplayName("★ 前提核对：库里确实存在 INTERNAL 与 PUBLIC 两种密级的块")
    void knowledgeBaseHasBothLevels() {
        List<String> levels = pgVectorJdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'level' AS lvl FROM hagent_knowledge", String.class);

        assertThat(levels)
                .as("★ 若只有一种密级，下面所有『过滤』断言都会失去意义 —— 必须先确认前提")
                .contains("INTERNAL", "PUBLIC");
    }

    // ========================================================================
    // 二、员工视角：过滤必须生效、且必须可执行
    // ========================================================================

    @Test
    @DisplayName("★★ 员工检索年假问题：必须拿到 INTERNAL 内容（同时证明 jsonpath 过滤在真库上可执行）")
    void employeeRetrievesInternalContent() {
        List<RetrievedChunk> chunks = retrievalService.retrieve(POLICY_QUERY, employee);

        assertThat(chunks)
                .as("★ 这条断言同时验证两件事：① 过滤条件没把可见内容挡掉；"
                        + "② 过滤条件在真实 pgvector 上【可执行】—— 若不可执行，"
                        + "查询会抛异常并被降级成空列表，这里就会失败")
                .isNotEmpty();

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.source()).isEqualTo(INTERNAL_SOURCE);
            assertThat(chunk.level()).isEqualTo(AccessLevel.INTERNAL);
        });
    }

    @Test
    @DisplayName("★ 员工视角不含 CONFIDENTIAL（示例库里目前没有该密级，属于结构性确认）")
    void employeeNeverSeesConfidential() {
        List<RetrievedChunk> chunks = retrievalService.retrieve(POLICY_QUERY, employee);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.level()).isNotEqualTo(AccessLevel.CONFIDENTIAL));
    }

    // ========================================================================
    // 三、匿名视角：只能看到 PUBLIC —— 这是越权防线
    // ========================================================================

    @Test
    @DisplayName("★★ 匿名检索同一个问题：返回的每一条都必须是 PUBLIC")
    void anonymousOnlySeesPublic() {
        List<RetrievedChunk> chunks = retrievalService.retrieve(POLICY_QUERY, anonymous);

        assertThat(chunks)
                .allSatisfy(chunk -> assertThat(chunk.level())
                        .as("★ 越权防线：匿名身份拿到非 PUBLIC 内容即为安全事故。"
                                + "来源=%s", chunk.source())
                        .isEqualTo(AccessLevel.PUBLIC));
    }

    @Test
    @DisplayName("★★ 匿名视角一定拿不到员工手册的任何块（过滤确实作用在检索上）")
    void anonymousCannotReachInternalSource() {
        List<RetrievedChunk> chunks = retrievalService.retrieve(POLICY_QUERY, anonymous);

        assertThat(chunks)
                .as("这条断言的对照面是 employeeRetrievesInternalContent —— "
                        + "同一提问、同一库，员工拿到了员工手册的块，匿名一条都拿不到")
                .noneSatisfy(chunk -> assertThat(chunk.source()).isEqualTo(INTERNAL_SOURCE));
    }

    @Test
    @DisplayName("★ 多个不同提问下，匿名视角的可见范围都只含 PUBLIC（不是碰巧）")
    void anonymousOnlySeesPublicAcrossQueries() {
        for (String query : List.of(
                "年假要提前几天申请",
                "出差住宿费能报多少",
                "请假流程是什么",
                "公司总部在哪里",
                "办公设备怎么申请")) {

            List<RetrievedChunk> chunks = retrievalService.retrieve(query, anonymous);

            assertThat(chunks)
                    .as("提问=%s", query)
                    .allSatisfy(chunk -> assertThat(chunk.level()).isEqualTo(AccessLevel.PUBLIC));
        }
    }

    // ========================================================================
    // 四、三种视角的对照（把"过滤真的改变了结果"摆出来）
    // ========================================================================

    @Test
    @DisplayName("★ 三种视角对照：匿名 ⊆ 员工 ⊆ 管理员，且员工比匿名多出 INTERNAL 内容")
    void threePerspectivesAreNested() {
        List<RetrievedChunk> asAnonymous = retrievalService.retrieve(POLICY_QUERY, anonymous);
        List<RetrievedChunk> asEmployee = retrievalService.retrieve(POLICY_QUERY, employee);
        List<RetrievedChunk> asAdmin = retrievalService.retrieve(POLICY_QUERY, admin);

        // 可见范围是嵌套的，所以结果集也应当体现这种关系
        assertThat(asEmployee).allSatisfy(c -> assertThat(c.level()).isIn(
                AccessLevel.PUBLIC, AccessLevel.INTERNAL));
        assertThat(asAdmin.size()).isGreaterThanOrEqualTo(asEmployee.size());
        assertThat(asEmployee.size()).isGreaterThanOrEqualTo(asAnonymous.size());

        // "员工比匿名多看到东西"——如果过滤没生效，两者会完全一样
        assertThat(asEmployee.stream().map(RetrievedChunk::source).toList())
                .as("★ 若过滤根本没生效，两者结果会完全相同 —— 这条对照就是用来抓这种情况的")
                .isNotEqualTo(asAnonymous.stream().map(RetrievedChunk::source).toList());

        writeReport(asAnonymous, asEmployee, asAdmin);
    }

    /** 把三种视角的实测结果写文件留档 —— 这是权限过滤唯一的"证据"。 */
    private void writeReport(List<RetrievedChunk> anonymous, List<RetrievedChunk> employee,
                             List<RetrievedChunk> admin) {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(78)).append('\n');
        sb.append("[权限过滤端到端实测] 提问：").append(POLICY_QUERY).append('\n');
        sb.append("=".repeat(78)).append('\n');
        appendPerspective(sb, "匿名（仅可见 PUBLIC）", anonymous);
        appendPerspective(sb, "员工（可见 PUBLIC+INTERNAL）", employee);
        appendPerspective(sb, "管理员（全部可见）", admin);
        sb.append("""

                判读要点：
                  1) 匿名那一栏的【密级】必须全是 PUBLIC
                  2) 员工比匿名多出的条目，其来源应为 INTERNAL 文档（员工手册.md）
                  3) 若三栏完全相同，说明过滤没生效；若员工那栏为空，
                     说明过滤条件在真实库上不可执行（被降级成空列表）
                """);
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "retrieval-permission-report.txt"),
                    sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 写报告只是为了留档，失败不应让测试失败
            System.out.println("[权限过滤实测] 报告写入失败：" + e.getMessage());
        }
    }

    private void appendPerspective(StringBuilder sb, String label, List<RetrievedChunk> chunks) {
        sb.append("\n--- ").append(label).append("：").append(chunks.size()).append(" 条 ---\n");
        for (RetrievedChunk chunk : chunks) {
            sb.append(String.format("   相似度=%.4f  档位=%s  密级=%-12s  出处=%s%n",
                    chunk.similarity(), chunk.band().displayName(),
                    chunk.level(), chunk.displaySource()));
        }
    }

    // ========================================================================
    // 五、失败降级与"没命中"的可区分性
    // ========================================================================

    @Test
    @DisplayName("★ 空查询直接跳过（不返回一堆无意义的最近邻）")
    void blankQueryReturnsNothing() {
        assertThat(retrievalService.retrieve("", employee)).isEmpty();
        assertThat(retrievalService.retrieve("   ", employee)).isEmpty();
        assertThat(retrievalService.retrieve(null, employee)).isEmpty();
    }

    @Test
    @DisplayName("★ 完全不相关的问题仍会返回候选（阈值是粗筛）—— 记录这条已知局限")
    void irrelevantQueryStillReturnsCandidates() {
        List<RetrievedChunk> chunks = retrievalService.retrieve("今天杭州天气怎么样", admin);

        // 实测相似度 0.7402，距离 0.2598 —— 轻松通过 0.5 的粗筛。
        // 这条断言不是为了证明"能返回"是好事，而是把"阈值挡不住无关问题"
        // 这个已知局限钉在测试里：将来有人想靠调阈值解决它时，会先看到这条。
        assertThat(chunks)
                .as("★ 已知局限：无关提问也会通过粗筛，真正的兜底在提示词约束里")
                .isNotEmpty();
    }
}
