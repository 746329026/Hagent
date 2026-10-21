package com.hagent.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 回合账的 MySQL 实现（表 {@code chat_turn}）。
 *
 * <h2>★ 三条与 {@code MysqlToolInvocationStore} 同源的立场（不重复论证，只列）</h2>
 *
 * <ol>
 *   <li><b>启动自检</b>：表 / 三个索引缺任何一个 → <b>启动即失败</b>并打印可执行的修法。
 *       理由与记录层、工具审计完全相同：等到第一次收尾才发现，
 *       意味着"这之间的所有轮次永久没有账" —— 而账只能从此刻开始攒，事后无法补录。</li>
 *   <li><b>写入失败只记 ERROR，不抛异常</b>（见 {@link TurnStore} 的类注释）。</li>
 *   <li><b>按名字取数据源</b>：调用方必须 {@code @Qualifier("dataSource")}。
 *       按类型注入会静默拿到向量库（阶段 7 踩坑 29）。</li>
 * </ol>
 *
 * <h2>★ 为什么用默认构造参数（而不是像工具审计那样给一个 null 兜底）</h2>
 *
 * <p>本表<b>没有可空到"整行不用写"的字段</b>：{@code status}、{@code elapsed_ms}、
 * {@code model_calls} 都是本轮真实发生的值。所以 {@code record(null)} 只做无操作，
 * 一行都不写 —— 宁可少一行，也不写一行半真半假的。
 *
 * <h2>★ 为什么一次 INSERT 而不是"先插 RUNNING 再 UPDATE"</h2>
 *
 * <p>见 {@code db/chat_turn.sql} 的"为什么主键是 UUID"：{@code turn_id} 在轮次开始时
 * 就生成好了，工具审计当场就能带上它；收尾时这一行已经"齐了"，
 * 不需要 UPDATE、不需要僵尸行清理、不需要为并发写加锁。
 */
@Slf4j
public class MysqlTurnStore implements TurnStore {

    /** 表名。★ 刻意<b>不做成配置键</b>：没有"需要换表名"的消费者（见 {@code db/chat_turn.sql}）。 */
    private static final String TABLE = "chat_turn";

    /** 三个索引名（DDL 里各有一条注释说明它对应哪个查询）。 */
    private static final String[] INDEXES = {
            "idx_turn_conv_time", "idx_turn_user_time", "idx_turn_status_time"
    };

    private static final String DDL_HINT = """
            表 chat_turn（或它的索引）不存在，回合账无法工作。
            本项目的表由【显式 DDL】创建，应用不自动建表 ——
              原因是 CREATE TABLE IF NOT EXISTS 查不出"表在、但结构不对"。
            请在项目根目录执行：
              docker cp src/main/resources/db/chat_turn.sql hagent-mysql:/tmp/chat_turn.sql
              docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_turn.sql'
            """;

    private static final String INSERT = "INSERT INTO " + TABLE
            + " (turn_id, conversation_id, user_id, role, mode, status, finish_reason,"
            + " elapsed_ms, first_token_ms, model_calls, tool_calls, tool_ms,"
            + " prompt_tokens, completion_tokens, total_tokens, usage_missing_calls,"
            + " plan_steps, error_message)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** 读账要取的全部列（与 {@link #toRecord} 一一对应；写死在常量里，避免两处漂移）。 */
    private static final String SELECT_COLUMNS =
            "turn_id, conversation_id, user_id, role, mode, status, finish_reason,"
                    + " elapsed_ms, first_token_ms, model_calls, tool_calls, tool_ms,"
                    + " prompt_tokens, completion_tokens, total_tokens, usage_missing_calls,"
                    + " plan_steps, error_message";

    private final JdbcTemplate jdbc;

    /**
     * @param dataSource <b>业务主库</b>的数据源（调用方须用 {@code @Qualifier("dataSource")} 按名字取）
     */
    public MysqlTurnStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        verifySchemaOrThrow();
    }

    @Override
    public java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) {
            return java.util.Map.of();
        }
        // 去重 + 去掉 null：历史里可能有很多阶段 14 之前的消息（turn_id 为 NULL）
        java.util.List<String> ids = turnIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        // ★ 一次 IN 查询，不是逐个查（历史回放要显示整段对话里每一轮的数字，N+1 会明显卡）
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.Map<String, TurnRecord> result = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                        + " WHERE turn_id IN (" + placeholders + ")",
                rs -> {
                    TurnRecord record = toRecord(rs);
                    result.put(record.turnId(), record);
                },
                ids.toArray());
        return result;
    }

    /** 单行 → {@link TurnRecord}（列顺序与 {@link #SELECT_COLUMNS} 一致）。 */
    private static TurnRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TurnRecord(
                rs.getString("turn_id"),
                rs.getString("conversation_id"),
                rs.getString("user_id"),
                rs.getString("role"),
                rs.getString("mode"),
                TurnStatus.valueOf(rs.getString("status")),
                rs.getString("finish_reason"),
                rs.getLong("elapsed_ms"),
                // ★ 可空整数必须用 getObject：getLong 会把 NULL 变成 0，
                //   而"首字 0ms"与"根本没等到首字"是两件事（本阶段的核心口径之一）
                (Long) rs.getObject("first_token_ms", Long.class),
                rs.getInt("model_calls"),
                rs.getInt("tool_calls"),
                rs.getLong("tool_ms"),
                rs.getObject("prompt_tokens", Integer.class),
                rs.getObject("completion_tokens", Integer.class),
                rs.getObject("total_tokens", Integer.class),
                rs.getInt("usage_missing_calls"),
                rs.getObject("plan_steps", Integer.class),
                rs.getString("error_message"));
    }

    @Override
    public void record(TurnRecord record) {
        if (record == null) {
            return;
        }
        try {
            jdbc.update(INSERT,
                    record.turnId(),
                    record.conversationId(),
                    record.userId(),
                    record.role(),
                    record.mode(),
                    record.status().name(),
                    record.finishReason(),
                    record.elapsedMs(),
                    record.firstTokenMs(),
                    record.modelCalls(),
                    record.toolCalls(),
                    record.toolMs(),
                    record.promptTokens(),
                    record.completionTokens(),
                    record.totalTokens(),
                    record.usageMissingCalls(),
                    record.planSteps(),
                    record.errorMessage());
        } catch (Exception e) {
            // ★ 见 TurnStore 的类注释：账本不在关键路径上 → 不抛异常。
            //   但必须记 ERROR 且写清后果 —— 静默丢账会让成功率凭空失真。
            log.error("[回合账] 写入失败（本次对话不受影响；但这一轮【没有留下任何统计】，"
                            + "成功率与成本都会少一条）：轮次={} 会话={} 状态={} 原因={}",
                    record.turnId(), record.conversationId(), record.status(),
                    e.getMessage(), e);
        }
    }

    /**
     * 启动自检：表 + 三个索引必须都在。
     *
     * <p>不符就<b>让应用起不来</b>并打印可直接执行的修法 ——
     * 与 {@code MysqlMessageStore} / {@code MysqlToolInvocationStore} 同一立场（决策 8）。
     */
    private void verifySchemaOrThrow() {
        try {
            Integer tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, TABLE);
            if (tableCount == null || tableCount == 0) {
                throw new IllegalStateException(DDL_HINT);
            }
            for (String index : INDEXES) {
                Integer indexCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                        Integer.class, TABLE, index);
                if (indexCount == null || indexCount == 0) {
                    throw new IllegalStateException(String.format(
                            "表 %s 缺少索引 %s —— 对应的查询（按会话 / 按人 / 按状态统计）会退化成全表扫描，"
                                    + "数据一多就明显变慢，而且不会报错。"
                                    + "请按 src/main/resources/db/chat_turn.sql 重建该表。",
                            TABLE, index));
                }
            }
            log.info("[回合账] 启动自检通过：表={} 索引={} 个（每轮一行：耗时 / 成功与否 / 工具次数 / token）",
                    TABLE, INDEXES.length);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(String.format(
                    "回合账的启动自检失败（表=%s）：%s。"
                            + "请确认 MySQL 已启动、连接参数正确，且已执行 src/main/resources/db/chat_turn.sql",
                    TABLE, e.getMessage()), e);
        }
    }
}
