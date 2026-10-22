package com.hagent.memory.context;

/**
 * 上下文层缓存实现需要的配置项 —— <b>由领域声明、由配置类实现</b>（依赖倒置）。
 *
 * <p>理由与两条约束见 {@link com.hagent.memory.record.MessageStoreSettings} 的类注释。
 *
 * <p><b>这里刻意没有 {@code maxRetained}</b>：本层要保留的条数是"两种模式条数上限的较大者"，
 * 那是<b>领域规则</b>（本层不知道本次是哪个模式），由 {@link RedisConversationContextCache}
 * 自己算 —— 端口只给两个原始上限。
 */
public interface ContextCacheSettings {

    /** Redis key 前缀（避免与其它应用的 key 冲突）。 */
    String getRedisKeyPrefix();

    /**
     * 缓存寿命（秒）。
     *
     * <p>它是<b>缓存</b>寿命，不是数据寿命：过期只是下次读取回源记录层，历史不会丢。
     */
    long getRedisTtlSeconds();

    /** chat 模式的条数上限（本层据此推算"最多需要保留多少条"）。 */
    int getChatMaxMessages();

    /** workflow 模式的条数上限。 */
    int getWorkflowMaxMessages();
}
