package com.hagent.memory.catalog;

/**
 * 会话目录实现需要的配置项 —— <b>由领域声明、由配置类实现</b>（依赖倒置）。
 *
 * <p>理由与两条约束见 {@link com.hagent.memory.record.MessageStoreSettings} 的类注释，
 * 这里是同一模式的应用：{@link FileSessionStore} 与 {@link MysqlSessionStore}
 * 不再直接依赖 {@code com.hagent.config.MemoryProperties}。
 *
 * <p>★ 它与记录层<b>共用同一个开关</b>（{@code record-store}）与同一个目录，
 * 所以两个端口都声明了 {@code getFileDir()} —— 同一个值、同一个实现者，
 * 不是两个配置项。
 */
public interface SessionStoreSettings {

    /** 会话目录 {@code file} 实现所在目录（与记录层同一个目录，靠 sidecar 文件区分）。 */
    String getFileDir();

    /**
     * 会话目录 {@code mysql} 实现的表名。
     *
     * <p>与消息表是<b>两张表</b>：一张存"会话里有什么"，一张存"有哪些会话"。
     */
    String getConversationTableName();
}
