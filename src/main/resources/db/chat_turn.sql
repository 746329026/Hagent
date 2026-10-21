-- ============================================================================
-- 阶段 14：一轮一行的【回合账】—— 审计主体 + 可观测性的原始事实
-- ============================================================================
--
-- 【这是什么】
--   一次用户请求（"一次输入 → 一次收尾"）的完整账：谁、什么模式、多久、
--   调了几次模型、几次工具、花了多少 token、成没成。
--
-- 【为什么它必须独立成表（三条硬理由，每条独立成立）】
--
--   1. ★ 失败与取消的轮次【没有 assistant 消息】。
--      `chat_message` 的两条（问 + 答）由 AgentPipeline#writeBack 在【收尾】时一起写入，
--      而模型抛错 / 客户端断开时根本走不到收尾 —— 于是这两种轮次在 MySQL 里【零痕迹】。
--      任何"把统计挂在 chat_message 上"的方案，天然记不到失败轮；
--      而失败轮恰恰是【成功率的分母】。只有成功路径能打的点，成功率永远是 100%。
--
--   2. 不能并进 `chat_conversation`（一会话一行）：那样"每轮耗时 / 每轮 token"
--      会被下一轮覆盖成"最后一次"。这正是阶段 14 之前 `Final.usage` 的那个病
--      （一个格子装多次的值：实测一轮 workflow 调了 7 次模型，只记了最后一次的 3419 token）。
--
--   3. 粒度不同：轮次 1 条 vs 工具调用 N 条。合并就得回答"工具活动挂在哪一行"
--      —— 那是个没有正确答案的问题（`chat_tool_invocation.sql` 顶部论证过一遍）。
--
-- 【它与另外三张表的关系】
--   chat_conversation  1 会话 : N 轮   （本表的 conversation_id）
--   chat_turn          1 轮   : N 工具 （chat_tool_invocation.turn_id = 本表 turn_id）
--   chat_message       1 轮   : 0 或 2 条（问 + 答；★ 失败轮是 0 条，见上面第 1 条）
--
-- 【为什么主键是 UUID（turn_id）而不是自增 id】
--   工具审计在【轮次中途】就要写（那时收尾还没发生）。自增主键要等 INSERT 之后才有值，
--   于是记录工具调用的那一刻根本拿不到 turn_id。
--   ⇒ turn_id 在轮次【开始时】由服务端生成（UUID），工具审计当场就能把它带上；
--     收尾时本表一次性 INSERT 这一行 —— 不需要 UPDATE，也不需要 RUNNING 状态与僵尸行清理。
--   代价（写进 HANDOFF 的诚实清单）：进程被 kill 的轮次不会留下任何记录。
--
-- 【为什么没有 enabled 开关】
--   工具审计有一个开关，理由是 `result` 可能是几 KB 的制度全文、表增长最快。
--   本表一行约 100 字节（实测口径：一轮 1 条，一天一千轮 ≈ 100 KB），量级差两个数量级；
--   真要止血，一条 DELETE 就够。多一个能关错的键，就多一种"以为在统计其实没有"。
--
-- 【字段口径（与 AgentPipeline / TurnStats 必须一致，改一处要改两处）】
--   elapsed_ms      订阅开始 → Final 产出（不含写回与落库）
--   first_token_ms  第一片 Token/Reasoning 到达；NULL = 一轮下来一个字都没吐出来
--   model_calls     本轮调了几次模型（chat = 步数；workflow = 规划 1 + 各步之和）
--   tool_calls      本轮工具调用次数（= chat_tool_invocation 里 turn_id 相同的行数，可对账）
--   tool_ms         工具段总耗时（框架的 executeToolCalls 是一次整体调用，没有单工具钩子）
--   *_tokens        【整轮累加】，不是"最后一次调用"
--   usage_missing_calls  其中几次没拿到 usage —— ★ 必须有它：EmptyUsage 的
--                        getPromptTokens() 返回 0（javap 实证），不区分就会把
--                        "没数据"记成"这轮花了 0 token"
--   plan_steps      工作流计划步数；chat 为 NULL
--
-- 【谁执行】
--   本人手动执行一次（与 chat_message.sql / chat_conversation.sql / chat_tool_invocation.sql 同）。
--   应用每次启动会校验表与三个索引，不符即【启动失败】并提示重跑本文件。
--
-- 【为什么不写 IF NOT EXISTS】
--   与另外三张表同一条理由：它查不出"表在、但结构不对"，结构漂移时会静默沿用旧表。
--   重建时请先 DROP TABLE。
-- ============================================================================

CREATE TABLE chat_turn (
    turn_id             CHAR(36)     NOT NULL COMMENT '本轮唯一标识（服务端 UUID；工具审计按它归轮）',
    conversation_id     VARCHAR(64)  NOT NULL COMMENT '会话 ID（与 chat_message.conversation_id 同源）',
    user_id             VARCHAR(64)  NOT NULL COMMENT '谁（匿名时为 anonymous）',
    role                VARCHAR(16)  NOT NULL COMMENT '角色（token 给的 user_id + 服务端判的 role）',
    mode                VARCHAR(16)  NOT NULL COMMENT '模式线上名：chat / workflow',
    status              VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED / CANCELLED',
    finish_reason       VARCHAR(32)  NULL     COMMENT 'stop / maxSteps / stepFailed / returnDirect / NULL',
    elapsed_ms          INT          NOT NULL COMMENT '整轮耗时（毫秒）',
    first_token_ms      INT          NULL     COMMENT '首个 token 到达耗时；NULL = 一个字都没出来',
    model_calls         INT          NOT NULL COMMENT '本轮模型调用次数',
    tool_calls          INT          NOT NULL COMMENT '本轮工具调用次数（与审计表可对账）',
    tool_ms             INT          NOT NULL COMMENT '工具段总耗时（毫秒）',
    prompt_tokens       INT          NULL     COMMENT '整轮输入 token（累加；NULL = 没拿到）',
    completion_tokens   INT          NULL     COMMENT '整轮输出 token（累加）',
    total_tokens        INT          NULL     COMMENT '整轮合计 token（累加）',
    usage_missing_calls INT          NOT NULL COMMENT '没拿到 usage 的调用次数（区分"0 token"与"没数据"）',
    plan_steps          INT          NULL     COMMENT '工作流计划步数；chat 为 NULL',
    error_message       VARCHAR(512) NULL     COMMENT '失败/取消的原因（只存 message，不存堆栈）',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (turn_id),
    -- 三条索引，每条对上一个真实查询；不建多余的（索引也占写入成本）
    KEY idx_turn_conv_time (conversation_id, created_at),   -- 这个会话的每一轮 / 与工具审计对账
    KEY idx_turn_user_time (user_id, created_at),           -- 谁最贵、谁最近老失败
    KEY idx_turn_status_time (status, created_at)           -- 成功率（按时间窗）
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='回合账（一轮一行）：审计主体 + 耗时/成功率/工具计数/token；失败与取消的轮次也在这里';
