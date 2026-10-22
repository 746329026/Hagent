package com.hagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * <b>业务主库</b>（MySQL）数据源的显式装配。
 *
 * <p>★ 为什么不能指望自动装配：{@code DataSourceAutoConfiguration} 带
 * {@code @ConditionalOnMissingBean(DataSource.class)}，而 {@link VectorStoreConfig}
 * 已声明 {@code pgVectorDataSource}（用户配置先于自动配置注册）→ 自动配置<b>退避</b>。
 * 于是<b>"默认的那个"数据源消失了，而且不报错</b>，直到第一次真正访问业务库才暴露，
 * 而症状（"表不存在"）会把排查引向完全错误的方向。
 *
 * <p>两个刻意的决定：
 * <ol>
 *   <li><b>{@code @Primary}</b>：业务主库是主数据源，也让要求
 *       {@code @ConditionalOnSingleCandidate(DataSource.class)} 的自动配置（事务管理器等）能工作；</li>
 *   <li><b>fail-fast</b>：业务数据不可缺失，连不上就该启动失败 —— 与向量库刻意相反
 *       （向量库不可用时检索降级即可），见 {@link VectorStoreConfig}。</li>
 * </ol>
 *
 * <p>Bean 名固定为 {@code dataSource}：其它组件按名字取它，避开"按类型注入可能拿到
 * pgVectorDataSource"的歧义。名字由常量 {@link #BUSINESS_DATASOURCE_BEAN} 提供。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
public class BusinessDataSourceConfig {

    /** 业务主库数据源的 Bean 名。调用方用 {@code @Qualifier} 引用这个常量。 */
    public static final String BUSINESS_DATASOURCE_BEAN = "dataSource";

    /**
     * 业务主库数据源（MySQL）。
     *
     * <p>不复用 {@link VectorStoreConfig} 的任何东西：不同的库、不同的失败策略、不同的池名，
     * 共用一个配置前缀只会带来混乱。
     */
    @Bean(name = BUSINESS_DATASOURCE_BEAN, destroyMethod = "close")
    @Primary
    public DataSource dataSource(BusinessDataSourceProperties props) {
        BusinessDataSourceProperties.Hikari hikari = props.getHikari();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getUrl());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        config.setDriverClassName(props.getDriverClassName());
        config.setPoolName(hikari.getPoolName());
        config.setMaximumPoolSize(hikari.getMaximumPoolSize());
        config.setMinimumIdle(hikari.getMinimumIdle());
        config.setConnectionTimeout(hikari.getConnectionTimeout());
        // fail-fast：业务主库连不上就该启动失败（与向量库相反，那里刻意放开）
        config.setInitializationFailTimeout(1);

        // ★ 只打印 url 与池名，绝不打印账号密码
        log.info("[数据源] 业务主库（MySQL）已显式装配：url={} pool={}（★ 不是自动装配 —— 见类注释）",
                props.getUrl(), hikari.getPoolName());

        try {
            return new HikariDataSource(config);
        } catch (Exception e) {
            // ★ 把"连接失败"翻译成"你该做什么"：Hikari 的原始报错
            //   （Failed to initialize pool: Communications link failure）只说了现象，
            //   没说原因、更没说修法，而"容器没在跑"是最常见的那个原因。
            throw new IllegalStateException(String.format(
                    "业务主库（MySQL）连接失败：%s%n"
                            + "  url = %s%n"
                            + "  ★ 最常见的原因：容器没在跑。先执行：%n"
                            + "      docker start pgvector hagent-mysql hagent-redis%n"
                            + "    （本项目依赖三个容器；宿主机的休眠或重启会把它们杀掉，"
                            + "而 Docker 报的退出时间可能不准）%n"
                            + "  本数据源**刻意 fail-fast**：业务数据不可缺失，连不上就该启动失败 ——%n"
                            + "  而不是让应用带着一个连不上的库跑起来。",
                    e.getMessage(), props.getUrl()), e);
        }
    }
}
