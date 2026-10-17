-- ============================================================================
-- 阶段 10：会话【目录】—— 一个会话一行（标题 / 模式 / 时间）
-- ============================================================================
--
-- 【这是什么】
--   `chat_message` 存的是「会话里有什么」（消息，一会话 N 行）；
--   本表存的是「有哪些会话」（会话本身，一会话 1 行）。
--
--   两者是【一对多】，所以不能合并：消息表里每一行都重复一遍标题，
--   改标题就要 UPDATE 全表，而且"标题"这个概念会出现 N 份副本（改一处漏一处）。
--
-- 【为什么必须有这张表 —— 三个消费者，缺一不可】
--   1. 侧栏要"列出我的会话"：消息表里 `SELECT DISTINCT conversation_id` 能列出 ID，
--      但【拿不到标题】，前端只能显示一串 UUID；
--   2. 标题必须有地方存：用户要能改名（PATCH），模型回答完也不该让标题变；
--   3. ★ `mode`（chat / workflow）必须有地方存 —— 这是本表最重要的一个理由：
--      模式决定记忆预算（5000 vs 50000 token，差 10 倍）。
--      若模式只由客户端每次请求现传，客户端就可以【每轮换一次模式】，
--      同一会话的历史预算在 5000 与 50000 之间来回跳，且没有任何记录说明它为什么变。
--      存进表之后，"这个会话是什么模式"是一条【事实】，而不是一次请求的参数。
--
-- 【为什么标题允许 NULL】
--   会话是在第一句话时【懒创建】的（见 AgentPipeline#writeBack）——
--   创建当时一定能拿到第一句用户消息，所以实际上不会 NULL。
--   留 NULL 是给"将来先建会话、后发消息"的形态留位；前端遇到 NULL 显示"新会话"。
--
-- 【为什么没有 updated_at 的触发器】
--   应用写入时显式给值。触发器会让"谁改了它、什么时候改的"藏进数据库，
--   排查时看不见 —— 本项目的一条既有立场：状态变化要在代码里可读。
--
-- 【执行方式】
--   本人手动执行一次（与 chat_message.sql 相同：应用不自动建表，
--   因为 CREATE TABLE IF NOT EXISTS 查不出"表在、但结构不对"）。
--   刻意不写 IF NOT EXISTS：重复执行会明确报 "table already exists"。
-- ============================================================================

CREATE TABLE chat_conversation (
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话 ID（与 chat_message.conversation_id 同值，服务端生成的随机 UUID）',
    title           VARCHAR(128) NULL     COMMENT '会话标题（默认取第一句用户消息的前 128 字，用户可改名）',
    mode            VARCHAR(16)  NOT NULL COMMENT '记忆模式：CHAT / WORKFLOW（决定历史预算；写入后不再由请求改变）',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (conversation_id),
    -- 侧栏按"最近活跃"倒序排列：ORDER BY updated_at DESC LIMIT n 走这条索引，
    -- 不随会话总数增长而变慢（与 chat_message 的 idx_conv_id 是同一个思路）。
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='会话目录（一会话一行）：标题 / 模式 / 时间；消息正文在 chat_message';
