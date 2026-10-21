-- ============================================================================
-- 迁移 001（阶段 14）：chat_tool_invocation 补“谁”与“哪一轮”
-- ============================================================================
--
-- 【本项目第一次表迁移 —— 这条约定从这里开始】
--   新库：按 `db/chat_tool_invocation.sql` 建表（那份文件已经包含这两列）。
--   已有库：执行本文件一次。
--   应用启动时会校验这两列存在（`MysqlToolInvocationStore#verifySchemaOrThrow`），
--   缺任何一列 → 启动失败并打印本文件的执行方式（决策 8：不静默降级）。
--
-- 【为什么不改那一份 DDL 就够了】
--   `db/chat_tool_invocation.sql` 是“建表语句的唯一出处”，刻意不写 IF NOT EXISTS
--   （它查不出“表在、但结构不对”）。表已经存在时它只会报 table already exists，
--   所以“给已有库加列”必须有独立的一次性脚本 —— 那就是本文件的全部职责。
--
-- 【为什么 user_id 给默认值 'unknown'，而不是可空】
--   “谁做的”是本表的核心维度，可空会让查询到处写 COALESCE，且容易被误读成“没人做过”。
--   'unknown' 是一条**诚实的**事实：阶段 14 之前的行，我们不知道是谁。
--   ⚠️ 不要为了让数据“好看”去回填猜测值 —— 那等于伪造审计。
--
-- 【为什么 turn_id 可空】
--   它关联的 `chat_turn` 表在阶段 14 才出现，历史行不可能有对应轮次。
--   可空在这里表达的是“这条记录发生在‘轮次’这个概念存在之前”。
--
-- 【执行方式（手动一次）】
--   docker cp src/main/resources/db/migrations/001-chat_tool_invocation-add-who.sql hagent-mysql:/tmp/001.sql
--   docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/001.sql'
--
-- 【为什么不写 ALTER TABLE ... IF NOT EXISTS】
--   MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`。重复执行会明确报
--   "Duplicate column name" —— 那正是我们要的：让人知道“已经迁移过了”，而不是静默成功。
-- ============================================================================

ALTER TABLE chat_tool_invocation
    ADD COLUMN turn_id CHAR(36) NULL
        COMMENT '本轮唯一标识（= chat_turn.turn_id；阶段 14 之前的行是 NULL）'
        AFTER conversation_id,
    ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT 'unknown'
        COMMENT '调用方标识；阶段 14 之前的行是 unknown（不伪造）'
        AFTER turn_id;

-- 按“哪一轮”查工具活动是新出现的主力查询（对账、复盘一次运行）
CREATE INDEX idx_tool_turn ON chat_tool_invocation (turn_id);
