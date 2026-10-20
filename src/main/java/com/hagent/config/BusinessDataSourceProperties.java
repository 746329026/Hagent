package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>业务主库</b>（MySQL）数据源的配置项，绑定 {@code spring.datasource.*}。
 *
 * <h2>★★ 为什么需要这个类（一段真实的踩坑，值得完整读）</h2>
 *
 * <p>本项目原本<b>没有</b>这个类，因为大家都以为"业务主库由 Spring Boot 的
 * {@code spring.datasource.*} 自动装配" —— 包括 {@link VectorStoreConfig} 的类注释
 * 与 {@link VectorStoreDataSourceProperties} 的类注释都这么写。
 *
 * <p><b>但那个数据源从来就不存在。</b>阶段 7 接 MySQL 时实测（条件评估报告原文）：
 * <pre>
 *   DataSourceAutoConfiguration.PooledDataSourceConfiguration:
 *     Did not match:
 *     - @ConditionalOnMissingBean (types: javax.sql.DataSource, javax.sql.XADataSource)
 *       found beans of type 'javax.sql.DataSource' pgVectorDataSource
 * </pre>
 *
 * <p>也就是说：{@link VectorStoreConfig} 为了"别让 PgVectorStore 抢到 MySQL 连接"
 * 而声明了 {@code pgVectorDataSource} —— <b>而这个声明恰好把自动配置的 MySQL 数据源
 * 顶掉了</b>（用户配置先于自动配置注册，{@code @ConditionalOnMissingBean} 于是不满足）。
 *
 * <p>后果是 {@code application-local.yml} 里那整段 {@code spring.datasource.*}
 * （url / 账号 / 周期 / Hikari 池参数）<b>一直被静默忽略</b>，
 * 而"全局 DataSource 是 MySQL"这句话变成了 {@code false}。
 *
 * <p><b>为什么长期没暴露</b>：在阶段 7 之前，<b>没有任何代码使用业务主库</b> ——
 * 没有消费方，就没有症状。这正是本项目第 7 条一直在防的那类问题：
 * <b>不是写错了代码，而是没意识到这段配置根本没有被使用。</b>
 *
 * <p>顺带说明：{@code JdbcTemplateAutoConfiguration} 的
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)} 也会因为
 * {@code pgVectorJdbcTemplate} 而退避，所以本项目的 MySQL 访问<b>不要</b>按类型注入
 * {@code JdbcTemplate}，而应按 bean 名取 {@code dataSource}（见
 * {@code MemoryConfig.mysqlMessageStore}）。
 *
 * <h2>关于默认值</h2>
 *
 * <p>与 {@link VectorStoreDataSourceProperties} 同一立场：字段默认值只是"属性缺省时仍可启动"
 * 的兜底，<b>真实取值一律以配置文件为准</b>。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.datasource")
public class BusinessDataSourceProperties {

    private String url;
    private String username;
    private String password;

    /** 固有属性：连 MySQL 就必须用这个驱动，不是"可调参数"。 */
    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    /**
     * {@code spring.datasource.hikari.*} 那一段。
     *
     * <p>做成嵌套对象而不是平铺字段，是为了让配置文件里的键名保持原样
     * （那段配置是在自动装配还被使用时写的，键名是 Boot 的惯例）。
     */
    private final Hikari hikari = new Hikari();

    @Getter
    @Setter
    public static class Hikari {
        /** 可调：连接池上限。真实取值见 application-local.yml。 */
        private int maximumPoolSize = 10;

        /** 可调：常驻空闲连接数。 */
        private int minimumIdle = 2;

        /** 可调：获取连接的最长等待毫秒数。 */
        private long connectionTimeout = 5000L;

        /** 池名，只用于日志与监控辨识。 */
        private String poolName = "HagentMysqlPool";
    }
}
