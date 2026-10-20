package com.hagent.memory.record;

/**
 * 记录层实现需要的配置项 —— <b>由领域声明、由配置类实现</b>（依赖倒置）。
 *
 * <h2>★ 为什么需要这个接口（阶段 12 解环）</h2>
 *
 * <p>在此之前，{@link FileMessageStore} 与 {@link MysqlMessageStore} 直接收
 * {@code com.hagent.config.MemoryProperties}。那让 {@code memory} 反过来依赖
 * {@code config}，而 {@code config} 本来就要依赖 {@code memory}（它负责装配实现）
 * —— 两条边一合，就是一个包级环。
 *
 * <p>现在方向是单向的：<b>本接口属于领域</b>（实现者用它取参数），
 * {@code MemoryProperties} 去<b>实现</b>它（配置类认识领域，而不是反过来）。
 *
 * <h2>★ 两条约束（往这里加方法之前先读）</h2>
 *
 * <ol>
 *   <li><b>只放"取值"，不放派生计算。</b>例如"两种模式的条数上限取较大者"是领域规则，
 *       它留在 {@code RedisConversationContextCache} 里，不要变成这里的方法 ——
 *       否则领域规则又会被搬回配置类（与 {@code RetrievalProperties#bandOf} 同一种错）。</li>
 *   <li><b>方法名沿用 {@code @ConfigurationProperties} 的 JavaBean 访问器名</b>
 *       （{@code getXxx}）。若写成 {@code fileDir()}，配置类就得再加一个<b>同义方法</b>
 *       —— 一个值两个名字，正是本阶段在清的那类问题。</li>
 * </ol>
 *
 * <p>★ 一个配置类可以同时实现多个端口（{@code MemoryProperties} 实现了本包、
 * {@code catalog} 与 {@code context} 的三个），但每个实现只声明<b>它自己</b>需要的值：
 * 文件实现看不到 Redis 的 TTL，MySQL 实现看不到工作流预算。
 */
public interface MessageStoreSettings {

    /** 记录层 {@code file} 实现的存储目录。 */
    String getFileDir();

    /**
     * 记录层 {@code mysql} 实现的表名。
     *
     * <p>它会被拼进 SQL，所以<b>校验由实现类做</b>（不可信来源的值不能直接进 SQL），
     * 本接口只负责把值给过去。
     */
    String getMysqlTableName();
}
