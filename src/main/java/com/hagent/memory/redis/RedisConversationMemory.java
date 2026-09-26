package com.hagent.memory.redis;

import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryProperties;
import com.hagent.memory.MemoryTrimmer;
import com.hagent.memory.MessageCodec;
import com.hagent.memory.TokenBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 实现 —— 承担"活跃会话的加速"角色。
 *
 * <h2>★ 定位：它是缓存，不是权威存储</h2>
 *
 * <p>这是本类最重要的一条设计约束。本项目中：
 * <ul>
 *   <li><b>权威存储</b>是 MySQL（阶段 7 提供）：完整会话历史、可审计、不丢；</li>
 *   <li><b>Redis 是缓存</b>：带 TTL，只保留活跃会话，用来加速"刚聊完马上又聊"。</li>
 * </ul>
 *
 * <p><b>为什么这个区分重要</b>：Redis 会<b>过期</b>。
 * 如果把它当权威存储，一旦 TTL 到期，这段历史就<b>永久残缺</b>。
 * 因此当前阶段（Redis 作为唯一实现时）它的语义是"会话有生命周期"，
 * 阶段 7 接入 MySQL 后，加载路径应改为
 * <b>"先查 Redis → miss 则回源 MySQL → 回填 Redis"</b>（Cache-Aside）。
 *
 * <p>这个"语义变化"是<b>有意保留的待办</b>，写在这里是为了避免阶段 7 忘记 ——
 * 否则会出现"到底以谁为准"的混乱。
 *
 * <h2>★ 为什么这个实现<b>不需要</b>锁（与 file 实现的关键差别）</h2>
 *
 * <p>文件实现必须加锁，因为它的"读-改-写"不是原子的。
 * 而 Redis 的追加可以借助服务端的原子操作 —— 但要注意：
 * <b>把整个列表读出来、在内存里追加、再整体写回，同样不是原子的</b>。
 *
 * <p>所以这里用 {@code RIGHT_PUSH}（列表右推）实现追加：
 * 单条命令在服务端原子执行，<b>天然无竞态，无需锁</b>。
 * 这也避免了"给 Redis 加锁导致全部会话串行"这个参考实现的坑。
 */
@Slf4j
public class RedisConversationMemory implements ConversationMemory {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisConversationMemory(MemoryProperties properties,
                                   StringRedisTemplate redis,
                                   ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = properties.getRedisKeyPrefix();
        this.ttl = Duration.ofSeconds(properties.getRedisTtlSeconds());
        log.info("[记忆-redis] 已启用：keyPrefix={} TTL={}h（注意：它是缓存，不是权威存储）",
                keyPrefix, ttl.toHours());
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = keyOf(conversationId);
        for (Message m : messages) {
            // 每条消息作为列表的一个元素（存 JSON 字符串），用 RIGHT_PUSH 原子追加。
            // 不采用"整体读改写"：那需要锁，且会把整个会话串行化。
            String json = toJson(MessageCodec.toStored(m));
            redis.opsForList().rightPush(key, json);
        }
        // 每次追加都刷新 TTL：只要还在聊，会话就不该过期
        redis.expire(key, ttl);
    }

    @Override
    public List<Message> load(String conversationId, TokenBudget budget) {
        String key = keyOf(conversationId);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            return List.of();
        }

        // 只取尾部 N 条：预算的条数上限决定了最多可能用到多少条，
        // 没必要把整个列表读出来再截断（会话可能很长）。
        // 取 min(实际长度, 条数上限)，然后交给公共截断逻辑按 token 精确裁剪。
        long fetchCount = Math.min(size, budget.maxMessages());
        List<String> raw = redis.opsForList().range(key, size - fetchCount, size - 1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                messages.add(fromJson(json));
            } catch (Exception e) {
                log.warn("[记忆-redis] 跳过一条无法反序列化的消息：{}", e.getMessage());
            }
        }
        // 截断规则只有一处实现（MemoryTrimmer），三个实现共用
        return MemoryTrimmer.trim(messages, budget);
    }

    @Override
    public void clear(String conversationId) {
        redis.delete(keyOf(conversationId));
        log.debug("[记忆-redis] 已清空会话 {}", conversationId);
    }

    @Override
    public void delete(String conversationId) {
        clear(conversationId);
    }

    // ==================== 内部方法 ====================

    private String keyOf(String conversationId) {
        return keyPrefix + conversationId;
    }

    private String toJson(MessageCodec.StoredMessage stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败：" + stored.type(), e);
        }
    }

    private Message fromJson(String json) {
        MessageCodec.StoredMessage stored =
                objectMapper.readValue(json, MessageCodec.StoredMessage.class);
        return MessageCodec.fromStored(stored);
    }

    /** 供配置类打印诊断信息用，避免把 key 拼装逻辑散落出去。 */
    public String describe() {
        return "RedisConversationMemory{keyPrefix=" + keyPrefix + ", ttl=" + ttl + "}";
    }
}
