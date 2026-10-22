package com.hagent.config;

import com.hagent.memory.ConversationMemory;
import com.hagent.memory.LayeredConversationMemory;

import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.context.NoOpContextCache;
import com.hagent.memory.context.RedisConversationContextCache;
import com.hagent.memory.record.MessageStore;
import com.hagent.memory.record.FileMessageStore;
import com.hagent.memory.record.MysqlMessageStore;
import com.hagent.memory.catalog.SessionStore;
import com.hagent.memory.catalog.FileSessionStore;
import com.hagent.memory.catalog.MysqlSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * 会话记忆的装配 —— "**换实现只改配置**"的落点。
 *
 * <p>结构固定：{@code ConversationMemory} ← {@code LayeredConversationMemory}（门面：
 * 组合 + 时序 + 失败分级 + 熔断），门面的两个构造参数就是两层能力：
 * <pre>
 *   MessageStore               ← 记录层：mysql | file（权威、完整、永久）
 *   ConversationContextCache   ← 上下文层：redis | none（有损、TTL、可重建）
 * </pre>
 *
 * <p>另有一个<b>不进这条链</b>的协作者 {@code SessionStore}（会话目录：标题 / 模式 / 时间，
 * 一会话一行；mysql | file，<b>与记录层共用 {@code record-store} 这一个开关</b>）：
 * 它不参与"给模型看什么"（模型不需要知道会话叫什么名字），消费者是前端侧栏
 * 以及"这个会话是什么模式"这条需要被记住的事实。
 *
 * <p>★ 装配机制用 {@code @ConditionalOnProperty}，不是 {@code @ConditionalOnMissingBean}：
 * 后者是"没有别的实现时才创建"的兜底语义，而这里要的是<b>按配置精确选择</b> ——
 * 用错了会出现"配了 file 却仍创建了 mysql 实现"。
 *
 * <p>★ 配错了会怎样：某个开关取值不在合法集合内时，两组条件注解都不匹配 → 容器里没有
 * {@code ConversationMemory} → Spring 抛出难以理解的"找不到 Bean"。所以有两道拦截
 * （都不是本类的职责）：① {@link MemoryConfigValidator}（在任何 Bean 创建之前校验）；
 * ② {@link MemoryProperties#afterPropertiesSet()}（第二道防线）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    // ========================================================================
    // 记录层（权威·完整·永久）
    // ========================================================================

    /**
     * 记录层 MySQL 实现：{@code record-store=mysql} 时创建（默认）。
     *
     * <p>★★ <b>这里刻意按名字取数据源，不能按类型注入 {@code JdbcTemplate}</b>：
     * {@code JdbcTemplateAutoConfiguration} 带 {@code @ConditionalOnMissingBean(JdbcOperations.class)}，
     * 而 {@code pgVectorJdbcTemplate} 是用户配置、先注册 → 自动配置退避 → 容器里可能<b>不存在</b>
     * 指向 MySQL 的 JdbcTemplate，按类型注入拿到的是指向 PostgreSQL 的那个。
     * 后果是"你以为在写 MySQL，其实写进了向量库"，症状只有"表 chat_message 不存在"。
     *
     * <p>数据源与事务管理器必须来自<b>同一个</b>（都用业务主库），否则事务不会生效。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store",
            havingValue = "mysql", matchIfMissing = true)
    public MessageStore mysqlMessageStore(
            @Qualifier("dataSource") DataSource dataSource,
            PlatformTransactionManager transactionManager,
            MemoryProperties properties) {
        log.info("[记忆] 记录层 = mysql（对话记录的权威存储：完整、永久、可审计）");
        return new MysqlMessageStore(dataSource, transactionManager, properties);
    }

    /** 记录层 file 实现：{@code record-store=file} 时创建（零基础设施兜底）。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store", havingValue = "file")
    public MessageStore fileMessageStore(MemoryProperties properties,
                                                               ObjectMapper objectMapper) {
        log.info("[记忆] 记录层 = file（零基础设施兜底；★ 它是【记录层】，不是缓存）");
        return new FileMessageStore(properties, objectMapper);
    }

    // ========================================================================
    // 会话目录 —— 一会话一行：标题 / 模式 / 时间
    // ========================================================================

    /**
     * 会话目录的 MySQL 实现。
     *
     * <p>★ 与记录层<b>共用同一个开关</b>（{@code record-store}），刻意不给它单独一个：
     * "消息在 MySQL、目录在文件里"没有任何意义，而两个开关能配出那种组合，
     * 且<b>不会有任何报错</b> —— 只表现为"侧栏永远是空的"。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store",
            havingValue = "mysql", matchIfMissing = true)
    public SessionStore mysqlSessionStore(
            @Qualifier("dataSource") DataSource dataSource,
            MemoryProperties properties) {
        return new MysqlSessionStore(dataSource, properties);
    }

    /** 会话目录的 file 实现（与记录层同为 {@code record-store=file}）。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store", havingValue = "file")
    public SessionStore fileSessionStore(MemoryProperties properties,
                                                       ObjectMapper objectMapper) {
        return new FileSessionStore(properties, objectMapper);
    }

    // ========================================================================
    // 上下文层（有损·TTL·可重建）
    // ========================================================================

    /** 上下文层 Redis 实现：{@code context-cache=redis} 时创建（默认）。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.context-cache",
            havingValue = "redis", matchIfMissing = true)
    public ConversationContextCache redisConversationContextCache(MemoryProperties properties,
                                                                 StringRedisTemplate redis,
                                                                 ObjectMapper objectMapper) {
        return new RedisConversationContextCache(properties, redis, objectMapper);
    }

    /** 上下文层不启用：{@code context-cache=none} 时创建 —— 每次读取都回源记录层。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.context-cache", havingValue = "none")
    public ConversationContextCache noOpConversationContextCache() {
        return new NoOpContextCache();
    }

    // ========================================================================
    // 门面
    // ========================================================================

    /**
     * 门面 —— {@code AgentPipeline} 唯一依赖的记忆抽象（{@link ConversationMemory}）。
     *
     * <p>它的构造参数就是两类能力，因此 Spring 会先创建两条链路再创建它 ——
     * "缺一层"这件事在启动期就会暴露，不会等到第一次对话。
     */
    @Bean
    public ConversationMemory conversationMemory(MessageStore messageStore,
                                                 ConversationContextCache contextCache,
                                                 MemoryProperties properties) {
        return new LayeredConversationMemory(messageStore, contextCache,
                properties.getCircuitBreakSeconds());
    }
}
