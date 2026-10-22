package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>业务主库</b>（MySQL）数据源的配置项，绑定 {@code spring.datasource.*}。
 *
 * <p>★★ 为什么需要这个类：{@link VectorStoreConfig} 为了"别让 PgVectorStore 抢到 MySQL 连接"
 * 声明了 {@code pgVectorDataSource}，而这恰好把自动配置的 MySQL 数据源<b>顶掉</b>了
 * （用户配置先于自动配置注册，{@code @ConditionalOnMissingBean} 于是不满足）。
 * 后果是 {@code spring.datasource.*} 整段（url / 账号 / Hikari 池参数）<b>一直被静默忽略</b>，
 * 而"全局 DataSource 是 MySQL"这句话是 {@code false} —— 只要还没有代码真正使用业务库，
 * 就不会有任何症状。
 *
 * <p>同一个原因还有一条约束：{@code JdbcTemplateAutoConfiguration} 的
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)} 也会因 {@code pgVectorJdbcTemplate}
 * 而退避，因此 MySQL 访问<b>不要</b>按类型注入 {@code JdbcTemplate}，要按 bean 名取
 * {@code dataSource}（见 {@code MemoryConfig.mysqlMessageStore}）。
 *
 * <p>字段默认值只是"属性缺省时仍可启动"的兜底，<b>真实取值一律以配置文件为准</b>。
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
     * <p>做成嵌套对象而不是平铺字段，是为了配置文件里的键名保持 Boot 的惯例不变。
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
