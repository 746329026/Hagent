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
 * <h2>★ 为什么必须显式声明，而不是指望自动装配</h2>
 *
 * <p>Spring Boot 的 {@code DataSourceAutoConfiguration} 带
 * {@code @ConditionalOnMissingBean(DataSource.class)}；而 {@link VectorStoreConfig}
 * 声明了 {@code pgVectorDataSource}，用户配置<b>先于</b>自动配置注册 ——
 * 于是自动配置<b>退避</b>，业务主库的数据源从来就不存在。
 * 完整证据与影响见 {@link BusinessDataSourceProperties} 的类注释。
 *
 * <p>换句话说：<b>当应用里出现第二个数据源时，"默认的那个"就消失了，而且不报错。</b>
 * 这是一个非常安静的失败模式 —— 直到有人第一次真正访问业务库才会暴露，
 * 而那时的症状（"表不存在"）会把人引向完全错误的方向。
 *
 * <h2>两个刻意的决定</h2>
 *
 * <ol>
 *   <li><b>{@code @Primary}</b>：业务主库是<b>主</b>数据源。
 *       这既符合语义，也让 Spring Boot 那些要求
 *       {@code @ConditionalOnSingleCandidate(DataSource.class)} 的自动配置
 *       （事务管理器等）能正常工作，而不用改它们的代码。</li>
 *   <li><b>保持 fail-fast</b>：业务数据不可缺失，连不上就<b>该启动失败</b>。
 *       这与向量库的策略相反（向量库不可用时检索降级即可，不该阻断启动）——
 *       两个数据源的失败策略不同是<b>有意</b>的，见 {@link VectorStoreConfig}。</li>
 * </ol>
 *
 * <p>Bean 名字固定为 {@code dataSource}：其它组件（如记忆的记录层）按名字取它，
 * 以避开"按类型注入可能拿到 pgVectorDataSource"的歧义。
 * 名字由常量 {@link #BUSINESS_DATASOURCE_BEAN} 提供，避免各处硬编码字符串。
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
     * <p>注意它<b>不</b>复用 {@code VectorStoreConfig} 的任何东西：
     * 两者是不同的库、不同的失败策略、不同的池名，共用一个配置前缀只会带来混乱。
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
            // ★ 把"连接失败"翻译成"你该做什么"。
            //
            //   这一段是**机制化**的产物：本项目已经两次遇到"容器没在跑 → 一片测试莫名全红"，
            //   而 Hikari 的原始报错是 "Failed to initialize pool: Communications link failure" ——
            //   它说了现象，没说原因，更没说修法。
            //   （工作协议第 6 条：同一个操作性错误重复 ≥2 次，就必须机制化，不能靠"记得"。）
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
