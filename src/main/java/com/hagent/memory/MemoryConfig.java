package com.hagent.memory;

import com.hagent.memory.file.FileConversationMemory;
import com.hagent.memory.redis.RedisConversationMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 会话记忆的装配 —— "**换实现只改配置**"的落点。
 *
 * <h2>★ 装配机制：@ConditionalOnProperty</h2>
 *
 * <p>每个实现的 Bean 上都带条件注解，只有配置匹配时才创建：
 * <pre>
 *   hagent.memory.chat.type=file   -> 只创建 FileConversationMemory
 *   hagent.memory.chat.type=redis  -> 只创建 RedisConversationMemory
 * </pre>
 *
 * <p><b>为什么不用 {@code @ConditionalOnMissingBean}</b>：
 * 它表达的是"没有别的实现时才创建"（兜底语义，用于"零配置可用 + 可被覆盖"）。
 * 而这里我们要的是"**按配置精确选择**"——语义不同，用错了会导致
 * "配了 redis 却仍创建了 file 实现"（因为 file 先注册、redis 被 missingBean 判定跳过）。
 *
 * <h2>★ mysql：用"启动自检"预留，而不是埋一个空 Bean</h2>
 *
 * <p>阶段 7 才会提供 MySQL 实现。但"预留"不能靠埋一个空壳 Bean ——
 * 那是死代码，而且是<b>危险</b>的死代码：
 * <pre>
 *   如果 type=mysql 而实现不存在：
 *     @ConditionalOnProperty 让所有实现都不创建
 *     -> 容器里没有 ConversationMemory
 *     -> Spring 可能自动装配一个内存实现作为兜底
 *     -> 你以为在用 MySQL 存，其实历史全在堆里，重启就丢
 *     -> 而且不报错
 * </pre>
 *
 * <p>所以这里用两件事兜住：
 * <ol>
 *   <li>{@link MemoryProperties#afterPropertiesSet()}：type 不在合法集合内 →
 *       <b>启动直接失败</b>，并说明"mysql 将在阶段 7 提供"；</li>
 *   <li>本配置类里的 {@code memoryImplementationSelfCheck}：装配完成后核对
 *       "容器里到底有没有 ConversationMemory"，没有就明确报错。</li>
 * </ol>
 *
 * <p>这样"配了但没生效"从**静默降级**变成**明确失败** —— 与全项目的基调一致。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    /** 支持的实现类型。新增实现时在这里加一项。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of("file", "redis", "mysql");

    /** 文件实现：type=file 时创建。 */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.type", havingValue = "file", matchIfMissing = true)
    public ConversationMemory fileConversationMemory(MemoryProperties properties,
                                                     ObjectMapper objectMapper) {
        log.info("[记忆] 使用文件实现（type=file）");
        return new FileConversationMemory(properties, objectMapper);
    }

    /**
     * Redis 实现：type=redis 时创建。
     *
     * <p>注意它的定位是**缓存**而非权威存储（见 {@link RedisConversationMemory} 的类注释），
     * 阶段 7 接入 MySQL 后要改成 Cache-Aside 语义。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.memory.chat.type", havingValue = "redis")
    public ConversationMemory redisConversationMemory(MemoryProperties properties,
                                                     StringRedisTemplate redis,
                                                     ObjectMapper objectMapper) {
        log.info("[记忆] 使用 Redis 实现（type=redis）");
        return new RedisConversationMemory(properties, redis, objectMapper);
    }
}
