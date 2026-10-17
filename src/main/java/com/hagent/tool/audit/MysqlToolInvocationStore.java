package com.hagent.tool.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * 工具调用审计的 MySQL 实现。
 *
 * <h2>★ 三条设计要点（每条都对应一个具体的失败模式）</h2>
 *
 * <ol>
 *   <li><b>启动自检</b>：表不存在 / 缺索引 → <b>启动即失败</b>，并打印可直接执行的修法。
 *       理由与阶段 7 的记录层完全相同：等到第一次工具调用才发现，
 *       意味着"这之间的所有工具活动永久没有审计" —— 而审计是<b>只能从现在开始攒</b>的数据。</li>
 *   <li><b>写入失败只记 ERROR，不抛异常</b>：审计不在关键路径上（见接口注释）。</li>
 *   <li><b>按名字取数据源</b>：用 {@code @Qualifier("dataSource")} 取业务主库，
 *       不能按类型注入 —— 本项目的向量库也是一个 {@code DataSource}，
 *       而 {@code @Primary} 会让按类型注入静默拿到错的那个（阶段 7 踩过，见 HANDOFF 踩坑 29）。</li>
 * </ol>
 *
 * <h2>★ 为什么不用事务把"工具调用 + 结果"写成一批</h2>
 *
 * <p>每次工具调用是<b>一次独立的事实</b>：调用发生了、结果拿到了。
 * 它们不像"问 + 答"那样必须同生共死（记忆记录层用事务是怕出现"有问无答"的半截对话，
 * 那会让历史读起来是错的）。审计少一条只是少一条记录，不会让别的记录变得不可信。
 */
@Slf4j
public class MysqlToolInvocationStore implements ToolInvocationStore {

    /** 读审计要走的索引名（启动自检会核对它存在）。 */
    private static final String CONVERSATION_INDEX = "idx_tool_conv_step";

    private static final String DDL_HINT = """
            表 chat_tool_invocation 不存在，工具调用审计无法工作。
            本项目的表由【显式 DDL】创建，应用不自动建表 ——
              原因是 CREATE TABLE IF NOT EXISTS 查不出"表在、但结构不对"。
            请在项目根目录执行下面两条：
              docker cp src/main/resources/db/chat_tool_invocation.sql hagent-mysql:/tmp/chat_tool_invocation.sql
              docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_tool_invocation.sql'
            """;

    private static final String TABLE = "chat_tool_invocation";

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
                            + " (conversation_id, step_index, tool_name, arguments, result)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    invocation.conversationId(),
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
        return jdbc.query("SELECT conversation_id, step_index, tool_name, arguments, result"
                        + " FROM " + table + " WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new ToolInvocation(
                        rs.getString("conversation_id"),
                        rs.getInt("step_index"),
                        rs.getString("tool_name"),
                        rs.getString("arguments"),
                        rs.getString("result")),
                conversationId, limit);
    }

    /**
     * 启动自检：表必须存在、读审计要用的索引必须在。
     *
     * <p>不符就<b>让应用起不来</b>并打印可直接执行的修法 ——
     * 与 {@code MysqlConversationRecordStore#verifySchemaOrThrow}、
     * {@code MemoryConfigValidator} 是同一立场（决策 8：不静默降级）。
     *
     * <p><b>为什么不校验字符集</b>：这张表存的是工具名、参数与结果文本，
     * 其中结果可能含中文 —— 建表语句已写 {@code DEFAULT CHARSET=utf8mb4}。
     * 记录层之所以要专门校验 {@code content} 列字符集，是因为那条链路上
     * 曾经有过"用 3 字节 utf8 导致 emoji 被截断"的真实风险；
     * 这里若也要校验，应校验的是"这张表的默认字符集"，
     * 而那属于<b>同一类检查的第二个实例</b> —— 等它真出问题时再加（协议第 7 条）。
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
            log.info("[工具-审计] 启动自检通过：表={} 索引={}（工具调用将逐条落库，可追溯）",
                    table, CONVERSATION_INDEX);
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
