package com.hagent.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import javax.sql.DataSource;

/**
 * 集成测试的<b>数据前置清理</b> —— 每个测试方法之前清空向量表。
 *
 * <h2>★ 它解决什么问题（一次真实的测试失败）</h2>
 *
 * <p>{@code DocumentIngestIntegrationTest} 单独运行能通过，<b>全量跑时失败 4 个</b>：
 * <pre>
 *   firstIngestOfMarkdownIsAdded   expected: ADDED   but was: SKIPPED_UNCHANGED
 * </pre>
 * 原因：用例断言"首次入库应当 ADDED"，但数据库里残留着<b>上一次运行</b>写入的数据。
 * 于是"首次入库"变成了"重复入库"，被正确地判定为跳过 ——
 * <b>被测系统的行为是对的，错的是测试对环境状态的假设。</b>
 *
 * <h2>为什么必须自动化，而不是"跑测试前记得清库"</h2>
 *
 * <ol>
 *   <li><b>会忘</b> —— 而忘记的表现是"测试失败"，会把排查方向误导到业务代码上；</li>
 *   <li><b>CI 上没有人可提醒</b> —— 自动化流程里没有"记得先做某件事"这一步；</li>
 *   <li><b>会掩盖真实问题</b> —— 长期失败的测试会被习惯性忽略，
 *       于是它连"回归防线"的作用都失去了。</li>
 * </ol>
 *
 * <h2>为什么用 {@code TestExecutionListener} 而不是 {@code @BeforeEach}</h2>
 *
 * <p>{@code @BeforeEach} 也能清理，但那是把"环境准备"写在测试类里，
 * 于是<b>每个新的集成测试类都要重复一遍</b>（或被迫继承某个基类）。
 * {@code TestExecutionListener} 是测试框架级别的钩子，与本项目在
 * {@code MemoryConfigValidator} / {@code FailureMapper} 上的取向一致：
 * <b>横切的事情集中一处，而不是每个使用点各写一遍。</b>
 *
 * <p>注册方式是 {@code @TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)} ——
 * 必须带这个 mergeMode，否则会<b>覆盖掉框架的默认监听器</b>
 * （依赖注入、事务、事件发布全靠它们），表现为测试里 {@code @Autowired} 拿不到值。
 *
 * <h2>★ 只 TRUNCATE，不 DROP</h2>
 *
 * <p>表由框架创建（{@code initializeSchema=true}），而"表能否被创建出来"
 * 本身就是要验证的行为（{@code vectorTableIsCreated} 用例）。
 * 若在这里 {@code DROP}，那个用例就会因为"测试自己把表删了"而失去意义 ——
 * 它要验证的是<b>应用启动时把表建出来了</b>，不是"测试建了表"。
 */
public class KnowledgeCleanupListener extends AbstractTestExecutionListener {

    /** 向量表名 —— 与 {@code spring.ai.vectorstore.pgvector.table-name} 保持一致。 */
    private static final String TABLE = "hagent_knowledge";

    @Override
    public void beforeTestMethod(TestContext testContext) {
        // TestExecutionListener 不是 Spring Bean，拿不到自动注入，
        // 所以从应用上下文按名取。必须按名 —— 容器里有两个 DataSource
        // （业务主库 MySQL 与 pgvector 的 PostgreSQL），按类型会歧义。
        DataSource pgVectorDataSource = testContext.getApplicationContext()
                .getBean("pgVectorDataSource", DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(pgVectorDataSource);

        // 表可能尚不存在（若 initializeSchema 未生效）—— 那种情况由
        // vectorTableIsCreated 用例给出明确失败，这里不掩盖它
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, TABLE);
        if (exists != null && exists > 0) {
            // TRUNCATE 比 DELETE 快得多（不做逐行 WAL 日志），且会重置表空间
            jdbc.execute("TRUNCATE TABLE " + TABLE);
        }
    }
}
