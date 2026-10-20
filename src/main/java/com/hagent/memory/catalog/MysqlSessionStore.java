package com.hagent.memory.catalog;

import com.hagent.memory.MemoryMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 会话目录的 MySQL 实现。
 *
 * <h2>★ 装配上的同一个坑（与 {@code MysqlConversationRecordStore} 一致）</h2>
 *
 * <p>接收 {@link DataSource} 而不是 {@code JdbcTemplate}，由调用方
 * {@code MemoryConfig} 用 {@code @Qualifier("dataSource")} 按<b>名字</b>取业务主库。
 * 理由见 {@code MysqlConversationRecordStore} 的类注释：
 * 项目里有两个数据源（MySQL 主库 / pgvector 的 PostgreSQL），
 * 按类型注入会拿到<b>指向 PostgreSQL</b> 的那个，而症状只是"表不存在"。
 *
 * <h2>★ {@link #touch} 用一条语句完成"懒创建 + 不覆盖"</h2>
 *
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE updated_at = ...}：
 * 会话不存在时插入（标题与模式取本次参数），已存在时<b>只更新活跃时间</b>，
 * 标题与模式原地不动 —— 这正是接口契约要的语义，而且
 * <b>没有"先 SELECT 再决定 INSERT 还是 UPDATE"的竞态</b>。
 *
 * <p>★ 刻意<b>不</b>用 {@code VALUES(col)} 语法（{@code ON DUPLICATE KEY UPDATE title = VALUES(title)}）：
 * 那样会把标题覆盖回"第一句话"，用户改过的名字会在下一句话时消失。
 * 这类"少写一个条件就静默丢用户输入"的地方，编译器帮不上忙，只能靠注释与测试。
 * （也不写 {@code VALUES()} 函数：它在 MySQL 8.0.20+ 已是 deprecated，
 * 早晚要改；不需要它的语义，就不要引入这个依赖。）
 */
@Slf4j
public class MysqlSessionStore implements SessionStore {

    /** 表名只允许普通标识符 —— 它会被拼进 SQL，不能来自不可信输入。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 侧栏一次最多返回多少条（由调用方给，这里只是兜底上限）。 */
    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final String table;

    public MysqlSessionStore(DataSource dataSource, SessionStoreSettings settings) {
        String raw = settings.getConversationTableName();
        if (raw == null || !IDENTIFIER.matcher(raw.trim()).matches()) {
            throw new IllegalStateException(
                    "hagent.memory.chat.conversation-table-name 不是合法标识符：" + raw
                            + "（它会被拼进 SQL，只允许字母、数字、下划线且不以数字开头）");
        }
        this.table = raw.trim();
        this.jdbc = new JdbcTemplate(dataSource);
        verifySchemaOrThrow();
    }

    @Override
    public List<Session> list(int limit) {
        int effective = Math.min(limit, MAX_LIMIT);
        if (effective <= 0) {
            return List.of();
        }
        // 走 idx_updated_at 的反向扫描：只读 N 行，不随会话总数增长。
        List<Session> sessions = jdbc.query(
                "SELECT conversation_id, title, mode, created_at, updated_at FROM " + table
                        + " ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> toSession(rs),
                effective);
        log.debug("[记忆-会话目录-mysql] 列出 {} 个会话（上限 {}）", sessions.size(), effective);
        return sessions;
    }

    @Override
    public Optional<Session> find(String conversationId) {
        List<Session> found = jdbc.query(
                "SELECT conversation_id, title, mode, created_at, updated_at FROM " + table
                        + " WHERE conversation_id = ?",
                (rs, rowNum) -> toSession(rs),
                conversationId);
        return found.stream().findFirst();
    }

    @Override
    public void touch(String conversationId, MemoryMode mode, String titleSeed) {
        MemoryMode safeMode = mode == null ? MemoryMode.DEFAULT : mode;
        String title = SessionStore.titleOf(titleSeed);
        // 一条语句承担两种语义：不存在→插入；存在→只更新 updated_at。
        // 标题与模式【刻意】不出现在 UPDATE 子句里（见类注释）。
        jdbc.update("INSERT INTO " + table
                        + " (conversation_id, title, mode, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NOW(3), NOW(3))"
                        + " ON DUPLICATE KEY UPDATE updated_at = NOW(3)",
                conversationId, title, safeMode.wireName());
    }

    @Override
    public boolean rename(String conversationId, String title) {
        if (title == null || title.isBlank()) {
            // 空标题没有意义：侧栏会显示成一行空白，用户以为会话丢了。
            // 由调用方在 HTTP 层用 @NotBlank 拦住，这里兜底为"不改"。
            return false;
        }
        int affected = jdbc.update("UPDATE " + table + " SET title = ?, updated_at = NOW(3)"
                + " WHERE conversation_id = ?", SessionStore.titleOf(title), conversationId);
        if (affected > 0) {
            return true;
        }
        // ★ 一个真实的 MySQL 坑：UPDATE 的影响行数在"新值与旧值完全相同"时是 0，
        //   所以 affected==0 有两种可能 —— "会话不存在"与"标题没变"。
        //   直接把 0 当成"不存在"会让前端在用户原样提交时收到 404（看起来像会话丢了）。
        return find(conversationId).isPresent();
    }

    @Override
    public void delete(String conversationId) {
        int removed = jdbc.update("DELETE FROM " + table + " WHERE conversation_id = ?", conversationId);
        log.debug("[记忆-会话目录-mysql] 已删除会话目录项 {}（影响 {} 行）", conversationId, removed);
    }

    // ==================== 内部方法 ====================

    private Session toSession(ResultSet rs) throws SQLException {
        return new Session(
                rs.getString("conversation_id"),
                rs.getString("title"),
                rs.getString("mode"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 启动自检：表必须存在且列齐。
     *
     * <p>与记录层的自检同一立场（决策 8：不静默降级）——
     * 表不存在的话症状是"侧栏永远是空的"，而它<b>不会报错</b>，
     * 排查方向会被引向"前端没渲染"。
     */
    private void verifySchemaOrThrow() {
        try {
            Integer tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            if (tableCount == null || tableCount == 0) {
                throw new IllegalStateException(String.format("""
                        表 %s 不存在，会话目录无法工作（表现：前端侧栏永远是空的，且不报错）。
                        本项目的表由【显式 DDL】创建，应用不自动建表。
                        请在项目根目录执行：
                          docker cp src/main/resources/db/chat_conversation.sql hagent-mysql:/tmp/chat_conversation.sql
                          docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_conversation.sql'
                        """, table));
            }
            for (String column : List.of("conversation_id", "title", "mode", "created_at", "updated_at")) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                        Integer.class, table, column);
                if (count == null || count == 0) {
                    throw new IllegalStateException(String.format(
                            "表 %s 缺少列 %s。请按 src/main/resources/db/chat_conversation.sql 重建该表。",
                            table, column));
                }
            }
            log.info("[记忆-会话目录-mysql] 启动自检通过：表={}", table);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(String.format(
                    "会话目录启动自检失败（表=%s）：%s。请确认 MySQL 已启动，且已执行 db/chat_conversation.sql",
                    table, e.getMessage()), e);
        }
    }
}
