package com.hagent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现 —— 最轻量，也是**兜底实现**。
 *
 * <p><b>它的定位</b>：
 * <ol>
 *   <li><b>兜底</b>：任何实现都没配置时，应用仍能启动并工作（历史只活在进程内）；</li>
 *   <li><b>测试</b>：单元测试用它，不依赖文件系统与外部服务。</li>
 * </ol>
 *
 * <p><b>它的局限（必须在类注释里写清，避免误用）</b>：
 * <ul>
 *   <li><b>重启即丢</b> —— 数据在 JVM 堆里；</li>
 *   <li><b>多实例不共享</b> —— 每个实例各存一份，负载均衡后同一用户可能落到不同实例上，
 *       表现为"历史时有时无"；</li>
 *   <li><b>无容量上限</b> —— 会话数多了会持续占内存（本实现不做淘汰）。</li>
 * </ul>
 * 所以它只适合<b>本地开发与测试</b>，不能用于多实例部署。
 *
 * <p><b>并发</b>：用 {@link ConcurrentHashMap} + 每次操作对<b>单个会话</b>的列表加同步，
 * 保证"读-改-写"不会丢消息。注意锁的粒度是<b>单会话</b>，不是全局 ——
 * 不同会话之间不互相阻塞（这正是参考实现踩过的坑：给所有实现都套上全局锁，
 * 导致全部会话串行）。
 */
public class InMemoryConversationMemory implements ConversationMemory {

    /** 会话 ID → 消息列表。外层并发安全，内层列表在 sync 块里操作。 */
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 按会话加锁：不同会话互不阻塞
        List<Message> list = store.computeIfAbsent(
                conversationId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.addAll(messages);
        }
    }

    @Override
    public List<Message> load(String conversationId, TokenBudget budget) {
        List<Message> list = store.get(conversationId);
        if (list == null) {
            return List.of();
        }
        List<Message> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }
        return MemoryTrimmer.trim(snapshot, budget);
    }

    @Override
    public void clear(String conversationId) {
        List<Message> list = store.get(conversationId);
        if (list != null) {
            synchronized (list) {
                list.clear();
            }
        }
    }

    @Override
    public void delete(String conversationId) {
        store.remove(conversationId);
    }
}
