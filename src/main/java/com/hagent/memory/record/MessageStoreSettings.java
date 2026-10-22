package com.hagent.memory.record;

/**
 * 记录层实现需要的配置项 —— <b>由领域声明、由配置类实现</b>（依赖倒置）。
 *
 * <p>实现类若直接收 {@code com.hagent.config.MemoryProperties}，就让 memory 反过来依赖 config，
 * 而 config 本来就要依赖 memory（它负责装配实现）—— 两条边一合就是一个包级环。
 * 现在方向是单向的：本接口属于领域，{@code MemoryProperties} 去<b>实现</b>它。
 *
 * <p><b>两条约束（往这里加方法之前先读）</b>：
 * <ol>
 *   <li><b>只放"取值"，不放派生计算。</b>例如"两种模式的条数上限取较大者"是领域规则，
 *       它留在实现类里 —— 否则领域规则又会被搬回配置类；</li>
 *   <li><b>方法名沿用 {@code @ConfigurationProperties} 的 JavaBean 访问器名</b>（{@code getXxx}）。
 *       写成 {@code fileDir()} 会逼配置类再加一个同义方法 —— 一个值两个名字。</li>
 * </ol>
 *
 * <p>一个配置类可以同时实现多个端口，但每个实现只声明<b>它自己</b>需要的值：
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
