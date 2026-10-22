package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 专用数据源的配置项，绑定 {@code hagent.vectorstore.datasource.*}。
 *
 * <p><b>为什么不复用 {@code spring.datasource.*}</b>：那是"业务主库"的位置。一个应用里业务主库
 * 只能有一个，其它库必须各自命名前缀 —— 否则等第二个数据源出现时，要回头重构所有注入点。
 *
 * <p>用可变 POJO 而不是 record：配置绑定对松散绑定（kebab-case → camelCase）支持最成熟的是
 * JavaBean setter 方式，这里确定性优先，不为语法糖冒绑定失败的风险。
 *
 * <p><b>默认值只是兜底</b>，不是"真实配置"：真实取值一律以 {@code application.yml} 为准 ——
 * "为什么是这个数"必须写在配置里让人一眼看到，而不是让人去翻源码。判断标准：
 * <b>能被环境改变的 = 配置项</b>（池大小、超时）；<b>这个数据源固有的 = 代码常量</b>（驱动类名、池名）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.vectorstore.datasource")
public class VectorStoreDataSourceProperties {

    private String url;
    private String username;
    private String password;

    /** 固有属性：连 PostgreSQL 就必须用这个驱动，不是"可调参数"。 */
    private String driverClassName = "org.postgresql.Driver";

    /** 固有属性：池名只用于日志与监控辨识。 */
    private String poolName = "HagentPgVectorPool";

    /** 可调：连接池上限。真实取值见 application.yml，此处仅为缺省兜底。 */
    private int maximumPoolSize = 5;

    /** 可调：常驻空闲连接数。 */
    private int minimumIdle = 1;

    /** 可调：获取连接的最长等待毫秒数（决定向量库故障时降级响应的快慢）。 */
    private long connectionTimeout = 5000L;
}