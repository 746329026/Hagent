package com.hagent.app;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import javax.sql.DataSource;

/**
 * 集成测试的<b>数据前置清理</b> —— 每个测试方法之前清空向量表。
 *
 * <p>不清理时单跑能过、全量跑会失败：库里残留着上一次运行的数据，
 * "首次入库应当 ADDED"就变成"重复入库被正确跳过" ——
 * <b>被测系统的行为是对的，错的是测试对环境状态的假设。</b> 必须自动化：
 * 忘记清库的表现是测试失败，会把排查方向误导到业务代码上；而长期失败的测试
 * 会被习惯性忽略，连回归防线的作用都失去。
 *
 * <p>用 {@code TestExecutionListener} 而非 {@code @BeforeEach}：横切的环境准备集中一处，
 * 不必每个新的集成测试类重复一遍。注册时必须带
 * {@code @TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)}，
 * 否则会<b>覆盖掉框架的默认监听器</b>（依赖注入、事务、事件发布），
 * 表现为测试里 {@code @Autowired} 拿不到值。
 *
 * <p>只 TRUNCATE 不 DROP：表由框架创建，而"表能否被创建出来"本身是
 * {@code vectorTableIsCreated} 要验证的行为，DROP 会让它因"测试自己把表删了"而失去意义。
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
