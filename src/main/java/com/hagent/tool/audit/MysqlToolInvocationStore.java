package com.hagent.tool.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 工具调用审计的 MySQL 实现。
 *
 * <p><b>三条设计要点（每条都对应一个具体的失败模式）</b>：
 *
 * <ol>
 *   <li><b>启动自检</b>：表不存在 / 缺索引 / 缺列 → <b>启动即失败</b>，并打印可直接执行的修法。
 *       等到第一次工具调用才发现，意味着这之间的所有工具活动永久没有审计 ——
 *       而审计是<b>只能从现在开始攒</b>的数据。</li>
 *   <li><b>写入失败只记 ERROR，不抛异常</b>：审计不在关键路径上（见接口注释）。</li>
 *   <li><b>按名字取数据源</b>：用 {@code @Qualifier("dataSource")} 取业务主库，不能按类型注入 ——
 *       本项目的向量库也是一个 {@code DataSource}，按类型注入会静默拿到错的那个。</li>
 * </ol>
 *
 * <p><b>不用事务把"工具调用 + 结果"写成一批</b>：每次工具调用是<b>一次独立的事实</b>，
 * 不像"问 + 答"那样必须同生共死；审计少一条只是少一条记录，不会让别的记录变得不可信。
 */
@Slf4j
public class MysqlToolInvocationStore implements ToolInvocationStore {

    /** 读审计要走的索引名（启动自检会核对它们存在）。 */
    private static final String CONVERSATION_INDEX = "idx_tool_conv_step";

    /** 按"哪一轮"查工具活动（对账 / 复盘一次运行）。 */
    private static final String TURN_INDEX = "idx_tool_turn";

    private static final String DDL_HINT = """
            表 chat_tool_invocation 不存在，工具调用审计无法工作。
            本项目的表由【显式 DDL】创建，应用不自动建表 ——
              原因是 CREATE TABLE IF NOT EXISTS 查不出"表在、但结构不对"。
            请在项目根目录执行下面两条：
              docker cp src/main/resources/db/chat_tool_invocation.sql hagent-mysql:/tmp/chat_tool_invocation.sql
              docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_tool_invocation.sql'
            """;

    private static final String TABLE = "chat_tool_invocation";

    /** 额外的两列（启动自检会核对它们存在）。 */
    private static final String[] REQUIRED_COLUMNS = {"turn_id", "user_id"};

    private static final String MIGRATION_HINT = """
            表 chat_tool_invocation 缺少阶段 14 新增的列（turn_id / user_id），工具审计无法记录"谁、哪一轮"。
            本项目的表结构变更由【显式迁移脚本】完成，应用不自动改表 ——
              原因是 ALTER 失败一半（部分列已加）会让后续启动表现成"看起来正常、实际缺列"。
            请在项目根目录执行：
              docker cp src/main/resources/db/migrations/001-chat_tool_invocation-add-who.sql hagent-mysql:/tmp/001.sql
              docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/001.sql'
            """;

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * @param dataSource  <b>业务主库</b>的数据源（调用方须用 {@code @Qualifier("dataSource")} 按名字取）
     * @param tableName   表名；只允许普通标识符（它会被拼进 SQL）
     */
    public MysqlToolInvocationStore(DataSource dataSource, String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException(
                    "审计表名不是合法标识符：" + tableName
                            + "（它会被拼进 SQL，只允许字母、数字、下划线且不以数字开头）");
        }
        this.table = tableName;
        this.jdbc = new JdbcTemplate(dataSource);
        verifySchemaOrThrow();
    }

    @Override
    public void record(ToolInvocation invocation) {
        if (invocation == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO " + table
                            + " (conversation_id, turn_id, user_id, step_index, tool_name, arguments, result)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    invocation.conversationId(),
                    invocation.turnId(),
                    invocation.userId(),
                    invocation.stepIndex(),
                    invocation.toolName(),
                    invocation.arguments(),
                    invocation.result());
        } catch (Exception e) {
            // ★ 见接口注释：审计不在关键路径上 → 不抛异常。
            //   但必须记 ERROR 且写清后果 —— 静默丢审计等于没审计。
            log.error("[工具-审计] 写入失败（本次对话不受影响；但这条工具调用【没有留下审计记录】，"
                            + "事后无法追溯）：会话={} 步={} 工具={} 原因={}",
                    invocation.conversationId(), invocation.stepIndex(),
                    invocation.toolName(), e.getMessage(), e);
        }
    }

    @Override
    public List<ToolInvocation> latest(String conversationId, int limit) {
        return jdbc.query("SELECT conversation_id, turn_id, user_id, step_index, tool_name, arguments, result"
                        + " FROM " + table + " WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new ToolInvocation(
                        rs.getString("conversation_id"),
                        rs.getString("turn_id"),
                        rs.getString("user_id"),
                        rs.getInt("step_index"),
                        rs.getString("tool_name"),
                        rs.getString("arguments"),
                        rs.getString("result")),
                conversationId, limit);
    }

    /**
     * 启动自检：表必须存在，读审计要用的索引与列也必须在。
     *
     * <p>不符就<b>让应用起不来</b>并打印可直接执行的修法 —— 与项目其余 schema 校验同一立场：
     * <b>不静默降级</b>。只校验表与索引、不校验列的话，旧库会"自检通过"却在第一次写审计时抛
     * {@code Unknown column}，而那时这轮对话已经花掉 token。
     *
     * <p><b>为什么不校验字符集</b>：这张表存的是工具名、参数与结果文本（可能含中文），
     * 建表语句已写 {@code DEFAULT CHARSET=utf8mb4}；记录层之所以专门校验 {@code content} 列，
     * 是因为那条链路上真实出现过"3 字节 utf8 导致 emoji 被截断"的风险，这里没有。
     */
    private void verifySchemaOrThrow() {
        try {
            Integer tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            if (tableCount == null || tableCount == 0) {
                throw new IllegalStateException(DDL_HINT);
            }
            Integer indexCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, CONVERSATION_INDEX);
            if (indexCount == null || indexCount == 0) {
                throw new IllegalStateException(String.format(
                        "表 %s 缺少索引 %s —— 按会话查审计会退化成全表扫描"
                                + "（数据一多就明显变慢，而且不会报错）。"
                                + "请按 src/main/resources/db/chat_tool_invocation.sql 重建该表。",
                        table, CONVERSATION_INDEX));
            }
            Integer turnIndexCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, TURN_INDEX);
            if (turnIndexCount == null || turnIndexCount == 0) {
                throw new IllegalStateException(MIGRATION_HINT + "缺索引：" + TURN_INDEX);
            }
            // ★ 两列也必须校验：只校验表与索引的话，旧库会"自检通过"
            //   却在第一次写审计时抛 Unknown column —— 而那时这轮对话已经花掉 token。
            for (String column : REQUIRED_COLUMNS) {
                Integer columnCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                        Integer.class, table, column);
                if (columnCount == null || columnCount == 0) {
                    throw new IllegalStateException(MIGRATION_HINT + "缺列：" + column);
                }
            }
            log.info("[工具-审计] 启动自检通过：表={} 索引={} 列={}（工具调用将逐条落库，可追溯到人）",
                    table, CONVERSATION_INDEX, String.join("/", REQUIRED_COLUMNS));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(String.format(
                    "工具调用审计的启动自检失败（表=%s）：%s。"
                            + "请确认 MySQL 已启动、连接参数正确，且已执行 "
                            + "src/main/resources/db/chat_tool_invocation.sql",
                    table, e.getMessage()), e);
        }
    }
}
