package com.hagent.memory.context;

import com.hagent.memory.LayerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 上下文层的"不启用"实现 —— {@code context-cache: none} 时生效。
 *
 * <h2>★ 它不是"空实现"，是一个有意义的配置选择</h2>
 *
 * <p>{@link #get} 永远返回 {@code null}（未命中），于是每次 {@code load} 都直接回源记录层。
 * 结果是：<b>功能完全正确，只是少了缓存</b>。
 *
 * <p>它的价值在三个阶段：
 * <ul>
 *   <li><b>契约测试的对照组</b>：同一套断言跑 "redis + mysql" 与 "none + mysql"，
 *       证明"缓存层的有无不影响对外语义"——这是"上下文层真的是可丢的派生层"的<b>直接证据</b>；</li>
 *   <li><b>排查问题</b>：怀疑缓存导致的不一致时，切成 {@code none} 就能隔离变量；</li>
 *   <li><b>不想起 Redis 的环境</b>：与记录层的 {@code file} 组成"零基础设施"组合。</li>
 * </ul>
 *
 * <p><b>为什么 {@code probe()} 报"可用"而不是"降级"</b>：
 * 未启用是<b>配置选择</b>，不是故障。若报成不可用，健康检查会在一个完全正常的部署上亮红灯 ——
 * 那会让真正的告警失去意义。
 */
@Slf4j
public class NoOpContextCache implements ConversationContextCache {

    public NoOpContextCache() {
        log.info("[记忆-上下文] 未启用（context-cache=none）—— 每次读取都会回源记录层，功能正确但少了缓存");
    }

    @Override
    public List<Message> get(String conversationId, int maxMessages) {
        // ★ 返回 null（未命中），而不是空列表 —— 两者语义不同，见接口注释
        return null;
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        // 不启用缓存，写入即为空操作
    }

    @Override
    public void replace(String conversationId, List<Message> messages) {
        // 同上
    }

    @Override
    public void evict(String conversationId) {
        // 同上
    }

    @Override
    public LayerStatus probe() {
        return LayerStatus.up("context", "none", "未启用（配置选择，非故障）");
    }
}
