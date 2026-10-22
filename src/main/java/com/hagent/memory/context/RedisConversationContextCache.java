package com.hagent.memory.context;

import com.hagent.memory.LayerStatus;
import com.hagent.memory.MessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文层的 Redis 实现 —— 承载"<b>模型这一次要用的上下文</b>"。
 *
 * <h2>它与记录层的关系：派生，不是副本</h2>
 *
 * <p>本层<b>可以丢</b>：TTL 到期、换实例、显式清空，都只是"下次读取要回源重建"。权威永远在记录层
 * —— 所以本层的任何失败都<b>不该</b>让对话失败（门面按 WARN 处理）。也正因为"可丢"，
 * 这里的 TTL 不再是"数据寿命"而是"<b>缓存寿命</b>"：24 小时未活跃的会话从本层消失，
 * 但历史仍在记录层。
 *
 * <h2>为什么不需要锁（与文件实现的关键差别）</h2>
 *
 * <p>文件实现必须加锁，因为它的"读-改-写"不是原子的。这里用 {@code RIGHT_PUSH}（列表右推）实现追加：
 * 单条命令在服务端原子执行，<b>天然无竞态、无需锁</b>。
 * <p>反例：<b>把整个列表读出来、在内存里追加、再整体写回，同样不是原子的</b> —— 那是把 file 版的
 * 做法照抄到 redis 上，会丢消息。
 *
 * <h2>为什么追加后要 LTRIM</h2>
 *
 * <p>本层<b>只服务"最近 N 条"</b>。不裁剪的话长会话会在 Redis 里无限增长（只受 TTL 约束），
 * 而超出的部分<b>永远不会被读到</b>。LTRIM 让本层的容量上界是<b>显式的</b>，
 * 而不是"取决于 TTL 与聊天频率的巧合"。
 */
@Slf4j
public class RedisConversationContextCache implements ConversationContextCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * 本层保留的最大条数 = 两种模式条数上限的较大者。
     *
     * <p>取较大者而不是各存各的：本层<b>不知道</b>调用方这次用的是哪个模式（预算在门面手里），
     * 存少了会让工作流模式读到残缺的上下文。
     */
    private final int maxRetained;

    public RedisConversationContextCache(ContextCacheSettings settings,
                                         StringRedisTemplate redis,
                                         ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = settings.getRedisKeyPrefix();
        this.ttl = Duration.ofSeconds(settings.getRedisTtlSeconds());
        this.maxRetained = Math.max(settings.getChatMaxMessages(),
                settings.getWorkflowMaxMessages());
        log.info("[记忆-上下文-redis] 已启用：keyPrefix={} TTL={}h 最多保留={}条"
                        + "（★ 它是【模型上下文的缓存】，不是权威存储；权威在记录层）",
                keyPrefix, ttl.toHours(), maxRetained);
    }

    @Override
    public List<Message> get(String conversationId, int maxMessages) {
        if (maxMessages <= 0) {
            return null;
        }
        String key = keyOf(conversationId);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            // key 不存在 → 未命中。Redis 会自动删除空列表，所以 size==0 等价于"键不在"
            return null;
        }
        long fetchCount = Math.min(size, maxMessages);
        List<String> raw = redis.opsForList().range(key, size - fetchCount, size - 1);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<Message> messages = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                messages.add(fromJson(json));
            } catch (Exception e) {
                log.warn("[记忆-上下文-redis] 跳过一条无法反序列化的消息：{}", e.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = keyOf(conversationId);
        for (Message message : messages) {
            // 每条消息作为列表的一个元素（存 JSON 字符串），RIGHT_PUSH 原子追加
            redis.opsForList().rightPush(key, toJson(MessageCodec.toStored(message)));
        }
        // 只保留最近 maxRetained 条（更旧的永远不会被读到，见类注释）
        redis.opsForList().trim(key, -maxRetained, -1);
        // 每次追加都刷新 TTL：只要还在聊，会话就不该从缓存里消失
        redis.expire(key, ttl);
    }

    @Override
    public void replace(String conversationId, List<Message> messages) {
        String key = keyOf(conversationId);
        redis.delete(key);
        if (messages == null || messages.isEmpty()) {
            // 会话确实为空：不留下空列表（Redis 也会自动删），下次读仍会 miss 并回源 —— 语义一致
            return;
        }
        append(conversationId, messages);
    }

    @Override
    public void evict(String conversationId) {
        redis.delete(keyOf(conversationId));
        log.debug("[记忆-上下文-redis] 已丢弃会话 {} 的上下文缓存", conversationId);
    }

    @Override
    public LayerStatus probe() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return LayerStatus.up("context", "redis",
                    "PING=" + pong + "，keyPrefix=" + keyPrefix + "，TTL=" + ttl.toHours() + "h");
        } catch (Exception e) {
            // 探测绝不抛异常：失败本身就是一种结果
            return LayerStatus.down("context", "redis", "Redis 不可达：" + e.getMessage());
        }
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
}
