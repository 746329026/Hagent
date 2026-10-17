package com.hagent.memory.record;

import com.hagent.memory.LayerStatus;
import com.hagent.memory.MemoryProperties;
import com.hagent.memory.MessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 记录层的 MySQL 实现 —— 本项目的<b>权威</b>会话存储。
 *
 * <h2>★★ 一个真实的装配陷阱：不要按类型注入 {@code JdbcTemplate}</h2>
 *
 * <p>本类<b>刻意</b>接收 {@link DataSource} 而不是 {@code JdbcTemplate}。原因是项目里
 * 存在两个数据源（业务主库 MySQL、以及 pgvector 的 PostgreSQL），而：
 * <pre>
 *   Spring Boot 的 JdbcTemplateAutoConfiguration 带 @ConditionalOnMissingBean(JdbcOperations.class)
 *   而 VectorStoreConfig 的 pgVectorJdbcTemplate 是【用户配置】、先于【自动配置】注册
 *   → 条件不满足 → 自动配置【退避】→ 容器里没有指向 MySQL 的 JdbcTemplate
 *   → 此时按类型注入 JdbcTemplate，拿到的是 pgVectorJdbcTemplate（指向 PostgreSQL）
 * </pre>
 * 后果是"<b>你以为在写 MySQL，其实写进了向量库</b>"—— 而症状只是
 * "表 chat_message 不存在"，很容易被误判成"忘了建表"。
 *
 * <p>所以这里由调用方（{@code MemoryConfig}）用
 * {@code @Qualifier("dataSource")} <b>按名字</b>取业务主库。
 *
 * <h2>★★ 为什么本类没有"序号生成"与"撞车重试"</h2>
 *
 * <p>它<b>曾经有</b>：表上有一列 {@code seq}（会话内序号）+ 唯一键，
 * 追加时先读 {@code MAX(seq)} 再插入，撞车就重读重试。那段代码被实测推翻了：
 * <ul>
 *   <li><b>普通读 + 重试</b>：REPEATABLE READ 下普通读是<i>一致性读、不加锁</i>，
 *       并发事务必然读到同一个 MAX。实测 2 线程各 3 次追加就把 3 次重试全部耗尽，
 *       报"本轮未落库"；</li>
 *   <li><b>锁定读 FOR UPDATE</b>：空区间上取<i>间隙锁</i>，而 INSERT 又要申请插入意向锁
 *       → 经典死锁。实测直接抛 {@code CannotAcquireLockException}。</li>
 * </ul>
 *
 * <p>而 {@code seq} 提供的属性（"会话内第 N 条"、"无洞"）<b>没有任何消费者</b>——
 * 真正需要的只是<b>顺序</b>，而自增主键 {@code id} 天然提供顺序、无竞态、无锁。
 * 于是 {@code seq} 与它的唯一键一起删掉，本类也就没有了那两条失败路径。
 * 完整论证见 {@code src/main/resources/db/chat_message.sql} 的注释
 * （那是一段"为一个没人要的属性付了锁与死锁代价"的记录）。
 *
 * <p><b>可迁移的判据</b>：引入一个"更规整"的属性（无洞序号、有序编号…）之前，
 * 先问<b>谁消费它</b>；如果答案是"排序能用上"，那已经有更好的东西在提供它了。
 */
@Slf4j
public class MysqlConversationRecordStore implements ConversationRecordStore {

    /** 表名只允许普通标识符 —— 它是拼进 SQL 的，不能来自不可信输入。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 读"某会话最近 N 条"要走的索引名（启动自检会核对它存在）。 */
    private static final String CONVERSATION_INDEX = "idx_conv_id";

    /** 阶段 10 新增的思考列名（启动自检会核对它存在）。 */
    private static final String REASONING_COLUMN = "reasoning";

    private static final String DDL_HINT = """
            表 %s 不存在，会话记忆的记录层无法工作。
            本项目的表由【显式 DDL】创建，应用不自动建表 ——
              原因是 CREATE TABLE IF NOT EXISTS 查不出"表在、但结构不对"，
              结构漂移时会被静默沿用（阶段 6 踩过同类坑）。
            请在项目根目录执行下面两条：
              docker cp src/main/resources/db/chat_message.sql hagent-mysql:/tmp/chat_message.sql
              docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_message.sql'
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String table;

    /**
     * @param dataSource         <b>业务主库</b>的数据源（调用方须用 {@code @Qualifier("dataSource")}
     *                           按名字取，理由见类注释）
     * @param transactionManager 事务管理器（与上面同一个数据源）
     */
    public MysqlConversationRecordStore(DataSource dataSource,
                                        PlatformTransactionManager transactionManager,
                                        MemoryProperties properties) {
        String raw = properties.getMysqlTableName();
        if (raw == null || !IDENTIFIER.matcher(raw.trim()).matches()) {
            throw new IllegalStateException(
                    "hagent.memory.chat.mysql-table-name 不是合法标识符：" + raw
                            + "（它会被拼进 SQL，只允许字母、数字、下划线且不以数字开头）");
        }
        this.table = raw.trim();
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);

        // ★ 启动自检：表不存在 / 字符集不对 / 索引缺失 → 启动就失败，
        //   而不是等第一次写入（或第一次慢查询）才发现。
        //   阶段 6 的坑：两个开关都为假时框架既不建表也不校验，一路绿灯，直到第一次写入才炸。
        verifySchemaOrThrow();
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 放在事务里：batchUpdate 如果不带 rewriteBatchedStatements，驱动会拆成多条 INSERT，
        // 中途失败就会留下"只写了问、没写答"的半截状态 —— 那是接口契约不允许的。
        tx.executeWithoutResult(status -> insertBatch(conversationId, messages));
    }

    @Override
    public List<Message> readLatest(String conversationId, int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        // 走 idx_conv_id 的反向扫描（二级索引自动附加主键，实际按 (conversation_id, id) 有序），
        // 只读 N 行 —— 不随会话长度增长。
        // ★ 排序用 id 而不是"会话内序号"：id 是自增主键，写入顺序即会话顺序，且无需任何锁。
        List<MessageCodec.StoredMessage> rows = jdbc.query(
                "SELECT role, content, reasoning FROM " + table
                        + " WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> toStored(rs),
                conversationId, maxMessages);
        if (rows.isEmpty()) {
            return List.of();
        }
        // SQL 按 id 倒序取"最近 N 条"，但要按时间正序交回调用方
        List<MessageCodec.StoredMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        // ★ 本层不做 token 预算裁剪（那是门面的事）；未知类型由 fromStoredList 记录并跳过
        return MessageCodec.fromStoredList(chronological);
    }

    /**
     * 读出该会话的<b>全部</b>消息（时间正序）。
     *
     * <p><b>唯一消费者是给前端的"历史回放"</b>（{@code GET /chat/sessions/{id}/messages}），
     * 与 {@link #readLatest} 的分工是：
     * <pre>
     *   readLatest(n)  → 给【模型】的上下文，按预算截断，越大越慢
     *   readAll()      → 给【用户】的完整记录，不截断，因为它就是"记录"本身
     * </pre>
     * 所以这里<b>刻意不做分页</b>：分页要游标、要"下一页"的交互，而当前没有消费者需要它。
     * 会话再长也只是几万行文本（一次 HTTP 响应能装下），
     * 真需要分页时再加一个带 {@code afterId} 的重载即可 —— 表结构不用动（id 天然是游标）。
     */
    @Override
    public List<Message> readAll(String conversationId) {
        List<MessageCodec.StoredMessage> rows = jdbc.query(
                "SELECT role, content, reasoning FROM " + table
                        + " WHERE conversation_id = ? ORDER BY id ASC",
                (rs, rowNum) -> toStored(rs),
                conversationId);
        return MessageCodec.fromStoredList(rows);
    }

    /**
     * 一行结果集 → 可持久化结构。
     *
     * <p>抽成方法而不是在三个地方各写一遍 lambda：列名拼错在 lambda 里只表现为
     * "某个字段恒为 null"，而抽出后它只有一处。
     */
    private MessageCodec.StoredMessage toStored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MessageCodec.StoredMessage(
                rs.getString("role"), rs.getString("content"), rs.getString("reasoning"));
    }

    @Override
    public void clear(String conversationId) {
        int removed = jdbc.update("DELETE FROM " + table + " WHERE conversation_id = ?", conversationId);
        log.debug("[记忆-记录-mysql] 已清空会话 {}（删除 {} 条）", conversationId, removed);
    }

    @Override
    public void delete(String conversationId) {
        // 本层没有独立的"会话"实体（会话就是 conversation_id 这一列），
        // 所以 delete 与 clear 的效果相同 —— 区别在语义，由调用点保证
        clear(conversationId);
    }

    @Override
    public LayerStatus probe() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                return LayerStatus.down("record", "mysql", "连通性查询返回异常结果");
            }
            String charset = contentCharset();
            if (!"utf8mb4".equalsIgnoreCase(charset)) {
                return LayerStatus.down("record", "mysql",
                        "content 列字符集是 " + charset + "，必须是 utf8mb4");
            }
            if (!indexExists()) {
                return LayerStatus.down("record", "mysql",
                        "缺少索引 " + CONVERSATION_INDEX + "（读历史会退化成全表扫描）");
            }
            if (!columnExists(REASONING_COLUMN)) {
                return LayerStatus.down("record", "mysql",
                        "缺少 " + REASONING_COLUMN + " 列（阶段 10 新增，思考内容无法落库）");
            }
            return LayerStatus.up("record", "mysql",
                    "表 " + table + " 可用，content 字符集=" + charset + "，索引 " + CONVERSATION_INDEX + " 就位");
        } catch (Exception e) {
            // 探测绝不抛异常：失败本身就是一种结果
            return LayerStatus.down("record", "mysql", "MySQL 不可达或表缺失：" + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /** 在给定事务内批量插入 —— 没有序号要算，因此没有 MAX 查询、没有重试。 */
    private void insertBatch(String conversationId, List<Message> messages) {
        List<Object[]> batchArgs = new ArrayList<>(messages.size());
        for (Message message : messages) {
            MessageCodec.StoredMessage stored = MessageCodec.toStored(message);
            // ★ 阶段 10：reasoning 与 content 同一行 —— 一个回合就是一行，
            //   思考是该回合的附属事实，拆表只会让"读一个回合"变成两次查询。
            batchArgs.add(new Object[]{conversationId, stored.type(), stored.text(), stored.reasoning()});
        }
        // extra 列刻意不写（保持 NULL）：它留给真正结构化的数据，思考不是（见 chat_message.sql）
        jdbc.batchUpdate("INSERT INTO " + table
                + " (conversation_id, role, content, reasoning) VALUES (?, ?, ?, ?)", batchArgs);
    }

    private String contentCharset() {
        return jdbc.queryForObject(
                "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'content'",
                String.class, table);
    }

    private boolean indexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, CONVERSATION_INDEX);
        return count != null && count > 0;
    }

    /**
     * 某列是否存在。
     *
     * <p>为什么单独查而不是"启动时试跑一次 SELECT"：试跑会掩盖问题
     * ——{@code SELECT} 里少写一列照样成功，而 {@code INSERT} 才炸。
     * 直接问 {@code information_schema} 得到的是"结构事实"，不依赖任何语句的写法。
     */
    private boolean columnExists(String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    /**
     * 启动自检：表必须存在、{@code content} 必须是 utf8mb4、
     * 读历史要用的索引与阶段 10 的 {@code reasoning} 列必须在。
     *
     * <p>不符就<b>让应用起不来</b>并打印可直接执行的修法 ——
     * 与 {@code VectorStoreConfig.vectorTableSelfCheck}、
     * {@code MemoryConfigValidator} 是同一立场（决策 8：不静默降级）。
     */
    private void verifySchemaOrThrow() {
        try {
            Integer tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            if (tableCount == null || tableCount == 0) {
                throw new IllegalStateException(DDL_HINT.formatted(table));
            }
            String charset = contentCharset();
            if (!"utf8mb4".equalsIgnoreCase(charset)) {
                throw new IllegalStateException(String.format(
                        "表 %s 的 content 列字符集是 %s，必须是 utf8mb4。"
                                + "3 字节的 utf8 存不下 emoji 与部分生僻字，且插入时截断【不可逆】。"
                                + "请按 src/main/resources/db/chat_message.sql 重建该表。",
                        table, charset));
            }
            if (!indexExists()) {
                throw new IllegalStateException(String.format(
                        "表 %s 缺少索引 %s —— 读会话历史会退化成全表扫描（数据一多就会明显变慢，"
                                + "而且不会报错）。请按 src/main/resources/db/chat_message.sql 重建该表。",
                        table, CONVERSATION_INDEX));
            }
            if (!columnExists(REASONING_COLUMN)) {
                throw new IllegalStateException(String.format("""
                        表 %s 缺少 %s 列（阶段 10 新增：模型思考内容落库）。
                        不修的话每一次对话写回都会 SQL 报错，而症状只是"历史里没有思考"。
                        请在项目根目录执行（存量库升级，只跑一次）：
                          ALTER TABLE %s
                              ADD COLUMN reasoning MEDIUMTEXT NULL
                              COMMENT '阶段 10：该回合模型的完整思考（多步用 @@step:N 标记分隔）'
                              AFTER content;
                        """, table, REASONING_COLUMN, table));
            }
            log.info("[记忆-记录-mysql] 启动自检通过：表={} content 字符集={} 索引={} 列={}",
                    table, charset, CONVERSATION_INDEX, REASONING_COLUMN);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(String.format(
                    "会话记忆的记录层启动自检失败（表=%s）：%s。"
                            + "请确认 MySQL 已启动、连接参数正确，且已执行 src/main/resources/db/chat_message.sql",
                    table, e.getMessage()), e);
        }
    }
}
