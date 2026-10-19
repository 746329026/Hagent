package com.hagent.config;

import com.hagent.memory.ConversationMemory;
import com.hagent.memory.LayeredConversationMemory;

import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.context.NoOpContextCache;
import com.hagent.memory.context.RedisConversationContextCache;
import com.hagent.memory.record.ConversationRecordStore;
import com.hagent.memory.record.FileConversationRecordStore;
import com.hagent.memory.record.MysqlConversationRecordStore;
import com.hagent.memory.session.ConversationCatalog;
import com.hagent.memory.session.FileConversationCatalog;
import com.hagent.memory.session.MysqlConversationCatalog;
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
 * <h2>★ 阶段 7：装配的不再是"一个实现"，而是"两层 + 一个门面"</h2>
 *
 * <p>结构是固定的三层：
 * <pre>
 *   ConversationMemory（{@code AgentPipeline} 依赖的抽象）
 *        └── LayeredConversationMemory（门面：组合 + 时序 + 失败分级 + 熔断）
 *                ├── ConversationRecordStore   ← 记录层：mysql | file
 *                └── ConversationContextCache  ← 上下文层：redis | none
 * </pre>
 *
 * <p><b>★ 阶段 10 多了一个不进这条链的协作者</b>：
 * <pre>
 *   ConversationCatalog（会话目录：标题 / 模式 / 时间，一会话一行）
 *        └── mysql | file   ← 与记录层【共用】record-store 这一个开关
 * </pre>
 * 它不进 {@code LayeredConversationMemory} 是因为它<b>不参与"给模型看什么"</b> ——
 * 模型不需要知道自己的会话叫什么名字。它的消费者是<b>前端侧栏</b>，
 * 以及"这个会话是什么模式"这条<b>需要被记住的事实</b>（见 {@code ConversationCatalog} 类注释）。
 *
 * <p>所以本类里有<b>两组</b>条件装配，各由自己的开关决定 ——
 * 而不是阶段 5 那种"三者选其一"。见 {@link MemoryProperties} 的类注释。
 *
 * <h2>★ 装配机制：{@code @ConditionalOnProperty}，不是 {@code @ConditionalOnMissingBean}</h2>
 *
 * <p>后者表达的是"没有别的实现时才创建"（兜底语义，用于"零配置可用 + 可被覆盖"）。
 * 而这里要的是"<b>按配置精确选择</b>"—— 语义不同，用错了会导致
 * "配了 file 却仍创建了 mysql 实现"（因为先注册的那个会让另一个被 missingBean 判定跳过）。
 *
 * <h2>★ 配错了会怎样：启动失败，不会静默降级</h2>
 *
 * <p>如果某个开关的取值不在合法集合内（打错字），两组条件注解<b>都不匹配</b> →
 * 容器里没有 {@code ConversationMemory} → Spring 抛出难以理解的"找不到 Bean"，
 * 或者更糟：自动装配一个别的实现顶上。
 *
 * <p>所以有两道拦截（都不是这里的职责，但都在本项目里）：
 * <ol>
 *   <li>{@link MemoryConfigValidator} —— {@code EnvironmentPostProcessor}，
 *       在任何 Bean 创建之前就校验两个开关，报错直接说清"合法取值是什么、为什么失败"；</li>
 *   <li>{@link MemoryProperties#afterPropertiesSet()} —— 第二道防线，
 *       防止有人绕过上面的路径。</li>
 * </ol>
 *
 * <p>★ <b>一道不存在的防线不要写在注释里</b>：本类的早期版本在注释中描述了一个
 * 名为 {@code memoryImplementationSelfCheck} 的方法，而它<b>根本不存在</b>
 * （自检实际搬到了 {@code MemoryConfigValidator}）——
 * 这类"注释承诺了代码没有的东西"比没有注释更糟，阶段 7 一并纠正。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    /** 记录层支持的实现。新增时同步 {@link MemoryProperties#VALID_RECORD_STORES}。 */
    public static final java.util.Set<String> SUPPORTED_RECORD_STORES = java.util.Set.of("mysql", "file");

    /** 上下文层支持的实现。新增时同步 {@link MemoryProperties#VALID_CONTEXT_CACHES}。 */
    public static final java.util.Set<String> SUPPORTED_CONTEXT_CACHES = java.util.Set.of("redis", "none");

    // ========================================================================
    // 记录层（权威·完整·永久）
    // ========================================================================

    /**
     * 记录层 MySQL 实现：{@code record-store=mysql} 时创建（默认）。
     *
     * <p>★★ <b>这里刻意按名字取数据源，不能按类型注入 {@code JdbcTemplate}</b>：
     * <pre>
     *   Spring Boot 的 JdbcTemplateAutoConfiguration 带 @ConditionalOnMissingBean(JdbcOperations.class)
     *   而 VectorStoreConfig 的 pgVectorJdbcTemplate 是【用户配置】、先于【自动配置】注册
     *   → 条件不满足 → 自动配置退避
     *   → 容器里可能【不存在】指向 MySQL 的 JdbcTemplate
     *   → 此时按类型注入 JdbcTemplate，拿到的是 pgVectorJdbcTemplate（指向 PostgreSQL）
     * </pre>
     * 后果是"你以为在写 MySQL，其实写进了向量库"，而症状只是"表 chat_message 不存在"。
     * 按 bean 名取 {@code dataSource} 就没有这个歧义 ——
     * 与本项目 {@code StartupProbe} 按 bean 名取 pgVector 数据源的做法一致。
     *
     * <p>数据源与事务管理器必须来自<b>同一个</b>（都用业务主库），否则事务不会生效。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store",
            havingValue = "mysql", matchIfMissing = true)
    public ConversationRecordStore mysqlConversationRecordStore(
            @Qualifier("dataSource") DataSource dataSource,
            PlatformTransactionManager transactionManager,
            MemoryProperties properties) {
        log.info("[记忆] 记录层 = mysql（对话记录的权威存储：完整、永久、可审计）");
        return new MysqlConversationRecordStore(dataSource, transactionManager, properties);
    }

    /** 记录层 file 实现：{@code record-store=file} 时创建（零基础设施兜底）。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store", havingValue = "file")
    public ConversationRecordStore fileConversationRecordStore(MemoryProperties properties,
                                                               ObjectMapper objectMapper) {
        log.info("[记忆] 记录层 = file（零基础设施兜底；★ 它是【记录层】，不是缓存）");
        return new FileConversationRecordStore(properties, objectMapper);
    }

    // ========================================================================
    // 会话目录（阶段 10）—— 一会话一行：标题 / 模式 / 时间
    // ========================================================================

    /**
     * 会话目录的 MySQL 实现。
     *
     * <p>★ 它与记录层<b>共用同一个开关</b>（{@code record-store}），刻意不给它单独一个：
     * "消息在 MySQL、目录在文件里"这个组合没有任何意义，而两个开关可以配出那种组合，
     * 且<b>不会有任何报错</b> —— 只表现为"侧栏永远是空的"。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store",
            havingValue = "mysql", matchIfMissing = true)
    public ConversationCatalog mysqlConversationCatalog(
            @Qualifier("dataSource") DataSource dataSource,
            MemoryProperties properties) {
        return new MysqlConversationCatalog(dataSource, properties);
    }

    /** 会话目录的 file 实现（与记录层同为 {@code record-store=file}）。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.record-store", havingValue = "file")
    public ConversationCatalog fileConversationCatalog(MemoryProperties properties,
                                                       ObjectMapper objectMapper) {
        return new FileConversationCatalog(properties, objectMapper);
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
    public ConversationMemory conversationMemory(ConversationRecordStore recordStore,
                                                 ConversationContextCache contextCache,
                                                 MemoryProperties properties) {
        return new LayeredConversationMemory(recordStore, contextCache,
                properties.getCircuitBreakSeconds());
    }
}
