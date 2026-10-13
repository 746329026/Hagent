-- ============================================================================
-- 阶段 7：会话记忆的【记录层】—— MySQL 权威存储
-- ============================================================================
--
-- 【这是什么】
--   用户可见的对话记录 —— 权威、完整、永久。
--   与它相对的是【上下文层】（Redis）：模型这一次要用的上下文，有损、带 TTL、
--   可从本表重建。两层的关系是「派生」，不是「缓存同一份数据」。
--
-- 【谁执行】
--   本人手动执行一次。本文件是 DDL 的唯一出处。
--   应用每次启动会校验表结构与字符集，不符即【启动失败】并提示重跑本文件。
--
-- 【为什么不让应用自动建表】
--   1. 权限边界：改库结构通常是 DBA 的职责，应用不该有建表权；
--   2. `CREATE TABLE IF NOT EXISTS` 查不出「表在、但结构不对」——
--      结构漂移时会【静默沿用旧表】（阶段 6 踩过同类坑）。
--   所以本文件【刻意不写 IF NOT EXISTS】：重复执行会明确报
--   "table already exists"，而不是悄悄什么都不做。
--   重建时请先 `DROP TABLE chat_message;`。
--
-- 【为什么必须 utf8mb4】（工作协议 §9.4 第 1 条点名的两个不可逆项之一）
--   3 字节的 utf8 存不下 emoji 与部分生僻字（如「𠮷」）——
--   插入时被截断或报错，且【不可逆】。
--
-- ============================================================================
-- ★★ 一次被实测推翻的设计：曾经有 `seq` 列，现在删掉了
-- ============================================================================
--
-- 【原设计】多一个 `seq`（会话内序号，从 1 开始、无洞）+ 唯一键
--            UNIQUE KEY uk_conv_seq (conversation_id, seq)
--   理由写的是"排序与分页都靠它，且唯一键能挡住序号撞车"。
--
-- 【为什么推翻】为了生成"无洞的会话内序号"，追加时必须先读 `MAX(seq)` 再插入。
--   两个并发请求会读到同一个 MAX —— 修法只有两条，实测两条都不成立：
--     · 普通读 + 撞车重试：REPEATABLE READ 下普通读是【一致性读、不加锁】，
--       并发下重试会被耗尽。实测 2 线程各追加 3 次就把 3 次重试全部用光。
--     · 锁定读 FOR UPDATE：空区间上取【间隙锁】，而 INSERT 又要申请插入意向锁
--       → 经典死锁。实测直接报 CannotAcquireLockException。
--   代价是：额外的锁、死锁风险、重试逻辑，以及"耗尽重试就丢一轮对话"。
--
-- 【关键一问】`seq` 提供的属性，到底谁消费？
--   排序      → `id` 天然提供（自增，写入顺序即会话顺序）
--   分页游标  → `id` 更好：keyset 分页（AND id < ?）比 seq 偏移更稳
--   会话内第N条 → ★ 没有任何消费者。本项目不展示"这是第几条"。
--   ⇒ 结论：为"第 N 条"这个没人要的属性，付了锁与死锁的代价。删掉。
--
-- 【现在的做法】只用自增 `id` 排序：
--   · PRIMARY KEY (id) —— 自增，全局唯一、单调，无竞态；
--   · KEY idx_conv_id (conversation_id) —— 读"某会话最近 N 条"走它。
--     ★ InnoDB 的二级索引会自动附加主键，所以 (conversation_id) 上的索引
--       实际是按 (conversation_id, id) 有序的 ——
--       `WHERE conversation_id=? ORDER BY id DESC LIMIT n` 正好是它的反向扫描，
--       不需要额外的 (conversation_id, id) 复合索引。
--   · 应用层不再有 MAX 查询、不再有重试循环、不再有那两条失败路径。
--
-- 【唯一键呢？还需要"幂等屏障"吗】
--   不需要。早先给唯一键定的职责是"挡序号撞车"，而不是"挡同一轮被写两次"
--   （后者在语义上就是两轮：客户端重发 = 用户又问了一次）。
--   seq 一旦消失，序号撞车这件事就不存在了，屏障也就没有存在理由。
--
-- 【collation：只写 charset、不写 COLLATE —— 附实测结果】
--   ★ 实测（MySQL 8.0.39，建表后 SHOW CREATE TABLE）：建出来是
--       DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
--     注意它【不是】@@collation_server（本机是 utf8mb4_general_ci），
--     而是【utf8mb4 这个字符集自己的默认 collation】。
--   为什么留空而不是显式写死：MySQL 8 下用同样写法建的表会拿到同一个
--   collation，将来 JOIN 不会撞 "Illegal mix of collations"。
--   实际值由启动自检核对（不靠这份注释）。
-- ============================================================================

CREATE TABLE chat_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)     NOT NULL COMMENT '会话 ID（服务端生成的随机 UUID）',
    role            VARCHAR(16)     NOT NULL COMMENT 'SYSTEM / USER / ASSISTANT / TOOL（TOOL 为阶段 8 预留）',
    content         MEDIUMTEXT      NOT NULL COMMENT '消息正文（utf8mb4，可存 emoji 与生僻字）',
    extra           JSON            NULL COMMENT '阶段 8 预留：tool_name / tool_call_id 等',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv_id (conversation_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='会话记忆的记录层（权威·完整·永久）；模型的上下文缓存另在 Redis';
