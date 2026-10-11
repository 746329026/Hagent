package com.hagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 向量库装配 —— 本项目"存储分层"的关键一环。
 *
 * <p><b>要解决的问题（实测得出，不是猜的）</b>：
 * 解压 spring-ai-autoconfigure-vector-store-pgvector-2.0.1.jar 读它的
 * spring-configuration-metadata.json 可以证实：spring.ai.vectorstore.pgvector.*
 * 下【只有】表名 / 维度 / 索引类型 / 距离度量 / 初始化开关等，
 * <b>没有任何数据源参数</b> —— 它复用 Spring 全局 DataSource。
 *
 * <p>而本项目的全局 DataSource 是业务主库 MySQL。于是自动装配的 PgVectorStore
 * 会拿到 MySQL 连接：轻则启动报方言错误，重则连到别的库读到别人的数据
 * （首次启动确实观察到它读到了另一个库里的同名表 vector_store）。
 *
 * <p><b>三个刻意的设计决定</b>：
 * <ol>
 *   <li><b>自己声明 PgVectorStore Bean，不用自动装配</b>：自动装配在两个
 *       DataSource 之间"选哪个"的逻辑不透明；显式构造则"用哪个 DataSource、
 *       用哪个 EmbeddingModel"一目了然，配置错误在启动阶段就暴露。</li>
 *   <li><b>pgVectorDataSource 不标 @Primary</b>：业务主库（MySQL，由
 *       spring.datasource 自动装配）才是主数据源，保持默认 @Primary 语义，
 *       避免事务管理器等误连到 PostgreSQL。</li>
 *   <li><b>两个数据源的失败策略相反</b>：MySQL 保持默认 fail-fast（业务数据
 *       不可缺失，连不上就该启动失败）；pgvector 关掉 fail-fast
 *       （向量库不可用时检索降级即可，不该阻断整个应用启动）。</li>
 * </ol>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({VectorStoreDataSourceProperties.class, VectorStoreProperties.class})
public class VectorStoreConfig {

    /** pgvector 专用数据源 Bean 名，供探针按名获取（避免探针硬依赖它）。 */
    public static final String PG_VECTOR_DATASOURCE_BEAN = "pgVectorDataSource";

    /**
     * pgvector 专用数据源（PostgreSQL）。
     *
     * <p>不标 {@code @Primary}：让 spring.datasource 装配出的 MySQL 数据源
     * 继续作为主数据源。
     */
    @Bean(name = PG_VECTOR_DATASOURCE_BEAN, destroyMethod = "close")
    public DataSource pgVectorDataSource(VectorStoreDataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getUrl());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        config.setDriverClassName(props.getDriverClassName());
        config.setPoolName(props.getPoolName());
        config.setMaximumPoolSize(props.getMaximumPoolSize());
        config.setMinimumIdle(props.getMinimumIdle());
        config.setConnectionTimeout(props.getConnectionTimeout());
        // 向量库不可用不应阻断应用启动：检索能力降级即可
        config.setInitializationFailTimeout(-1);

        log.info("[向量库] PostgreSQL 数据源已创建：pool={} url={}", props.getPoolName(), props.getUrl());
        return new HikariDataSource(config);
    }

    /**
     * 显式装配 PgVectorStore：把它与本项目的 PostgreSQL 数据源、Ollama embedding 模型绑定。
     *
     * <p><b>★ 这里踩过一个隐蔽的坑，值得记下来</b>：
     * {@code PgVectorStore.builder(...)} 是一个"裸"构建器，它<b>不会</b>去读
     * {@code spring.ai.vectorstore.pgvector.*} 那些配置项 —— 那些属性是给
     * 自动装配用的（PgVectorStoreProperties）。既然我们自己造 Bean，就必须自己绑定，
     * 否则所有配置项都会被静默忽略，表现为：
     *
     * <pre>
     *   配置文件写了 table-name: hagent_knowledge
     *   启动日志却是：Using the vector table name: vector_store
     * </pre>
     *
     * 而由于 initializeSchema 默认为 true，它会<b>自动建一张叫 vector_store 的表</b>，
     * 于是"配置没生效"完全不报错 —— 数据写进了另一张表，排查时极易误判。
     * 这类"配置写了却不用"的静默失效，正是本项目要系统性避免的问题。
     */
    @Bean
    public PgVectorStore vectorStore(JdbcTemplate pgVectorJdbcTemplate,
                                     EmbeddingModel embeddingModel,
                                     VectorStoreProperties properties) {
        log.info("[向量库] 装配 PgVectorStore：table={} schema={} dimensions={} index={} distance={} initSchema={}",
                properties.getTableName(), properties.getSchemaName(), properties.getDimensions(),
                properties.getIndexType(), properties.getDistanceType(), properties.isInitializeSchema());

        return PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                .vectorTableName(properties.getTableName())
                .schemaName(properties.getSchemaName())
                .dimensions(properties.getDimensions())
                .indexType(properties.getIndexType())
                .distanceType(properties.getDistanceType())
                .initializeSchema(properties.isInitializeSchema())
                .vectorTableValidationsEnabled(properties.isVectorTableValidationsEnabled())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
    }

    /**
     * 向量库专用的 {@link JdbcTemplate} —— <b>与 PgVectorStore 共用同一个实例</b>。
     *
     * <p><b>为什么要把它抽成 Bean，而不是在需要时各自 {@code new}</b>：
     * 入库服务需要用原生 SQL 直查向量表的 metadata（框架的 {@code VectorStore}
     * 接口没有"按元数据条件查询"的方法）。如果它在自己方法里 new 一个，
     * 就会出现"同一个数据源上两个 JdbcTemplate 实例"—— 功能上不会出错，
     * 但连接池使用、异常转换配置、将来加拦截器时都要改两处。
     * <b>共用同一个 Bean，让"访问向量库"只有一个入口。</b>
     *
     * <p>注意 {@code vectorStore(...)} 是通过<b>方法参数</b>拿到它的，
     * 而不是在方法体内调用 {@code pgVectorJdbcTemplate(...)} ——
     * 后者依赖 {@code @Configuration} 的 CGLIB 代理才会返回容器中的单例，
     * 一旦有人改成 {@code proxyBeanMethods = false}，那个写法就会
     * 悄悄变成"每次都 new 一个新的"。参数注入不依赖这个隐式规则。
     */
    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }

    /**
     * 启动自检：核对向量表<b>是否真的存在</b>，并把关键列类型打出来。
     *
     * <p>为什么需要这一步：上面那个坑说明"我配置了什么"与"实际用了什么"可能不一致，
     * 而系统不会报错。这里直查 PostgreSQL 的系统表，用事实而不是配置来确认。
     *
     * <p><b>★ 阶段 6 起，这条自检从"仅告警"升级为"缺失即启动失败"</b>：
     * 阶段 0 时没有写入路径，表不存在只是"尚未用到"；而阶段 6 引入入库后，
     * 表不存在意味着<b>每一次入库都会失败</b>。若继续只打 WARN，
     * 应用会正常启动、健康检查全绿，直到真的去入库才发现问题 ——
     * 这正是本项目全篇在防的"看起来一切正常"。
     *
     * <p>所以规则改为：<b>表必须存在，否则启动失败并说明原因与修法</b>。
     * 同时打印列结构 —— 下一轮做检索过滤时要按 metadata 字段写条件，
     * 事先知道它是 {@code json} 还是 {@code jsonb} 是有用的（两者对索引与操作符支持不同）。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner vectorTableSelfCheck(
            DataSource pgVectorDataSource, VectorStoreProperties properties) {
        return args -> {
            String existenceSql = "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name = ?";
            boolean exists = false;
            try (java.sql.Connection conn = pgVectorDataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(existenceSql)) {
                ps.setString(1, properties.getSchemaName());
                ps.setString(2, properties.getTableName());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
                if (exists) {
                    log.info("[向量库] 表 '{}' 已存在（schema={}）",
                            properties.getTableName(), properties.getSchemaName());
                    logColumnTypes(conn, properties);
                }
            } catch (Exception e) {
                // 连不上库时不在这里报错 —— 该数据源本身已配置为"不阻断启动"（检索可降级）
                log.warn("[向量库] 表存在性检查未能完成（向量库不可用时不阻断启动）：{}", e.getMessage());
                return;
            }

            if (!exists) {
                throw new IllegalStateException(String.format(
                        "向量表 '%s'（schema=%s）不存在，入库功能依赖它，应用不应带着这个问题启动。"
                                + "【为什么会出现】PgVectorStore 只在 "
                                + "spring.ai.vectorstore.pgvector.initialize-schema=true 时才建表；"
                                + "为 false 时它既不建表也不校验（schema-validation 同为 false 时校验是空操作），"
                                + "于是表永远不会被创建，直到第一次写入才报错。"
                                + "【怎么修】把 initialize-schema 设为 true 后重启。"
                                + "【若设为 true 后仍不存在】检查名为 %s 的数据源配置是否正确。",
                        properties.getTableName(), properties.getSchemaName(),
                        PG_VECTOR_DATASOURCE_BEAN));
            }
        };
    }

    /** 打印向量表的关键列类型 —— metadata 是 json 还是 jsonb 会影响下一轮检索过滤的写法。 */
    private void logColumnTypes(java.sql.Connection conn, VectorStoreProperties properties) {
        String sql = "SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, properties.getSchemaName());
            ps.setString(2, properties.getTableName());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(rs.getString("column_name")).append(' ').append(rs.getString("data_type"));
                }
                log.info("[向量库] 表结构：{}", sb);
            }
        } catch (Exception e) {
            // 打印列类型只为可观测性，失败不影响启动
            log.debug("[向量库] 读取表结构失败（不影响启动）：{}", e.getMessage());
        }
    }
}