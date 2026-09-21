package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 专用数据源的配置项。
 *
 * <p><b>为什么不复用 spring.datasource.*</b>：那是"业务主库"的位置。
 * 一个应用里业务主库只能有一个，其它库必须各自命名前缀 —— 否则等第二个数据源
 * 出现时，就要回头重构所有 Bean 的注入点。
 *
 * <p>用可变 POJO 而不是 record：Spring Boot 的配置绑定对松散绑定
 * （kebab-case 到 camelCase）支持最成熟的是 JavaBean setter 方式，
 * 这里确定性优先，不为语法糖冒绑定失败的风险。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.vectorstore.datasource")
public class VectorStoreDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private String poolName = "HagentPgVectorPool";
    private int maximumPoolSize = 5;
    private int minimumIdle = 1;
    private long connectionTimeout = 5000L;
}