-- ============================================================================
-- 迁移 002（阶段 14 后补）：chat_message 补 turn_id —— 让历史回放也能显示那一行统计
-- ============================================================================
--
-- 【为什么必须加这一列】
--   实时那一行统计来自 SSE 的 `stats` 事件；但**刷新页面后它消失了**，
--   因为历史回放（`GET /chat/sessions/{id}/messages`）拿不到"这条回答是哪一轮"。
--   没有这个关联键，只能靠"会话 + 时间"猜 —— 猜错的表现是**数字配错消息**，且不报错。
--
-- 【为什么加在 chat_message 而不是在 chat_turn 里加一个消息数组】
--   关系方向是「一条消息 → 一个轮次」（多对一）。反过来存成数组列：
--   ① 不能索引、不能 join；② 一次写入要读改写整行；③ 违反第一范式。
--
-- 【为什么允许 NULL】
--   阶段 14 之前写入的历史没有轮次可关联（那时还没有 chat_turn）。
--   NULL 在这里表达的是"这条回答发生在'回合账'存在之前" —— **不伪造**。
--
-- 【写入方与读取方（唯一出处）】
--   写：`MessageCodec.toStored` 从 AssistantMessage 的 properties 取
--       `hagent_turnId`（由 `AgentPipeline#writeBack` 挂上），两个存储实现统一走它。
--   读：`ConversationService#detail` 用 `MessageStore.readAll` 拿回消息，
--       再用 `TurnStore.find(turnIds)` 批量取账 —— **数字只有一个来源（chat_turn）**。
--
-- 【执行方式（手动一次）】
--   docker cp src/main/resources/db/migrations/002-chat_message-add-turn-id.sql hagent-mysql:/tmp/002.sql
--   docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/002.sql'
--
-- 【为什么不写 IF NOT EXISTS】
--   MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`；重复执行会明确报 "Duplicate column name"
--   —— 那正是我们要的：让人知道"已经迁移过了"，而不是静默成功。
-- ============================================================================

ALTER TABLE chat_message
    ADD COLUMN turn_id CHAR(36) NULL
        COMMENT '产生该回答的轮次（= chat_turn.turn_id；阶段 14 之前的行是 NULL）'
        AFTER reasoning;

-- 历史回放按 turn_id 批量取回合账（IN 查询）走它
CREATE INDEX idx_msg_turn ON chat_message (turn_id);
