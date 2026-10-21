-- ============================================================================
-- 阶段 9：工具调用审计表 —— 「谁在什么时候调了什么工具、拿到了什么」
-- ============================================================================
--
-- 【这是什么】
--   一次对话里每一次工具调用的**完整记录**：工具名、参数、返回结果、本轮第几步。
--
-- 【为什么它必须独立成表，而不是塞进 chat_message】
--   ★ 这是设计讨论中明确的一个判断，理由有三条，每条都独立成立：
--
--   1. 语义不同。`chat_message` 是【用户可见的对话记录】——
--      将来做"给用户的聊天历史"时直接 SELECT 它。
--      工具消息混进去，用户就会在聊天窗口里看到模型的"手机屏幕"：
--         user      | 帮我查一下年假
--         tool_call | {"name":"retrieveKnowledge","args":{...}}   ← 用户看到这个？
--         tool      | {"result":"员工每年享有五天带薪年假..."}      ← 显示成"模型说的话"
--         assistant | 你每年有五天年假
--      解决办法只能是"读的时候过滤掉"—— 而**不混进来**比**混进来再过滤**可靠得多。
--
--   2. 基数不同。`chat_message` 一行 = 一条消息；
--      而一轮对话可能有 **N 次**工具调用（每次 = 1 条调用 + 1 条结果）。
--      往 chat_message 的某一列里塞整轮的工具活动，就得先回答
--      "挂在哪一行上"（"问"那行还是"答"那行？）—— 那是个没有正确答案的问题。
--
--   3. 保留策略不同。对话记录是**永久**的（用户要看）；
--      审计数据的保留期通常短得多（合规要求会给出具体年限）。
--      两者的生命周期不该绑在一起。
--
-- 【谁执行】
--   本人手动执行一次。本文件是这张表 DDL 的唯一出处。
--   应用每次启动会校验表结构，不符即【启动失败】并提示重跑本文件。
--
-- 【为什么不写 IF NOT EXISTS】
--   与 chat_message.sql 同一条理由：它查不出"表在、但结构不对"，
--   结构漂移时会静默沿用旧表。重建时请先 DROP TABLE。
--
-- 【为什么 arguments / result 用 TEXT 而不是 JSON】
--   arguments 确实是 JSON（模型生成的），但 result **不是** ——
--   它是工具返回的**任意文本**（可能是一段中文制度、一张表格的文本形式）。
--   把非 JSON 塞进 JSON 列会被 MySQL 拒绝（或需要额外转义），
--   而审计表的第一要求是**可靠写入**：宁可存原文，不要在写入路径上加解析风险。
--   ⇒ 两列都用 TEXT/MEDIUMTEXT 存原文。
--   （若将来要做"按参数查询"，那时再加生成列或 JSON 列，并单独验证。）
--
-- 【为什么 result 用 MEDIUMTEXT】
--   一次知识库检索可能返回 3 条 × 400 字的片段（本项目 chunkSize=400、topK=3）。
--   TEXT 上限 64 KB 够用，但 MEDIUMTEXT（16 MB）留出余量，
--   避免将来某个工具返回长文本时**静默截断**——那正是本项目最警惕的一类失败。
--
-- ============================================================================

CREATE TABLE chat_tool_invocation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)     NOT NULL COMMENT '会话 ID（与 chat_message 同源，可用于关联对话）',
    -- ★ 阶段 14 新增两列：审计必须说得清"谁、哪一轮"
    --   已有库请执行 src/main/resources/db/migrations/001-chat_tool_invocation-add-who.sql
    turn_id         CHAR(36)        NULL     COMMENT '本轮唯一标识（= chat_turn.turn_id；阶段 14 之前的行是 NULL）',
    user_id         VARCHAR(64)     NOT NULL DEFAULT 'unknown' COMMENT '调用方标识；阶段 14 之前的行是 unknown（不伪造）',
    step_index      INT             NOT NULL COMMENT '本轮第几步（从 1 开始；一次对话可能多步循环）',
    tool_name       VARCHAR(64)     NOT NULL COMMENT '工具名（与 @Tool 的 name 一致）',
    arguments       TEXT            NULL     COMMENT '模型给出的参数原文（JSON 字符串）',
    result          MEDIUMTEXT      NULL     COMMENT '工具返回的文本原文（模型看到的同一份）',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 审计最常见的两个查询：① 某会话的工具活动（按步序）② 某工具最近被怎么用
    KEY idx_tool_conv_step (conversation_id, step_index),
    KEY idx_tool_name_time (tool_name, created_at),
    -- ★ 阶段 14：第三个查询 —— 这一轮调了哪些工具（与 chat_turn.tool_calls 对账）
    KEY idx_tool_turn (turn_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='工具调用审计（阶段 9）；与用户可见的对话记录 chat_message 分开存';
