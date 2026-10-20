package com.hagent.memory;

import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.record.MessageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 会话记忆的<b>门面</b> —— {@link ConversationMemory} 的唯一实现，把"记录层"与"上下文层"组合起来。
 *
 * <h2>★ 为什么要有这一层</h2>
 *
 * <p>{@code AgentPipeline} 依赖 {@link ConversationMemory} 这一个抽象，而它背后是<b>两个角色</b>：
 * <ul>
 *   <li>{@link MessageStore} —— <b>对话记录</b>：权威、完整、永久（用户刷新页面要看）；</li>
 *   <li>{@link ConversationContextCache} —— <b>模型上下文</b>：有损、易失、可重建（模型这次要用的）。</li>
 * </ul>
 *
 * <p>两者的需求正好相反，所以<b>不能</b>做成"靠配置二选一的两种实现"——
 * 那是阶段 5 的抽象，装不下这两层。本类把"谁先谁后、失败怎么办"收在<b>一处</b>，
 * 于是管线零改动。
 *
 * <h2>★ 三条本类负责守住的性质</h2>
 *
 * <ol>
 *   <li><b>先记录层、后上下文层</b> ⟹ 不变量「<b>上下文永不比记录新</b>」。
 *       破坏它的后果是"模型知道、用户刷新却看不到"—— 最难排查的一类不一致；</li>
 *   <li><b>读先上下文、miss 才回源记录层</b>。反过来的话上下文层就白做了；</li>
 *   <li><b>写失败分级</b>：记录层失败 = ERROR（但<b>不影响本次响应</b> ——
 *       模型回复已经产生、token 已经花了，为写库失败丢掉它是双重损失）；
 *       上下文层失败 = WARN（可从记录层重建）。</li>
 * </ol>
 *
 * <h2>★ 熔断：每层各一个</h2>
 *
 * <p>某层挂掉时，若不熔断，<b>每个请求都会白等一次连接超时</b>
 * （Redis 3s / MySQL 5s）—— 一次对话从 1 秒变成 4–8 秒，<b>故障期体验反而更差</b>。
 * 所以每层各带一个 {@link FailureCircuit}：失败后静默 N 秒，期间直接走降级路径。
 *
 * <p>注意 <b>clear / delete 不受熔断影响</b>：那是用户明确要求"把数据删掉"，
 * 不该因为"这一层刚才失败过"就跳过 —— 跳过会让用户继续看到已被要求删除的内容。
 *
 * @see MessageStore 记录层的定位说明（含"两层为什么不是两种存法"）
 */
@Slf4j
public class LayeredConversationMemory implements ConversationMemory {

    private final MessageStore recordStore;
    private final ConversationContextCache contextCache;
    private final FailureCircuit recordCircuit;
    private final FailureCircuit contextCircuit;
    private final long circuitBreakSeconds;

    public LayeredConversationMemory(MessageStore recordStore,
                                     ConversationContextCache contextCache,
                                     long circuitBreakSeconds) {
        this.recordStore = recordStore;
        this.contextCache = contextCache;
        this.circuitBreakSeconds = circuitBreakSeconds;
        this.recordCircuit = new FailureCircuit(circuitBreakSeconds);
        this.contextCircuit = new FailureCircuit(circuitBreakSeconds);
        log.info("[记忆-门面] 已就绪：记录层（权威完整）+ 上下文层（有损可重建），每层熔断窗口={}s",
                circuitBreakSeconds);
    }

    @Override
    public List<Message> load(String conversationId, TokenBudget budget) {
        int limit = budget.maxMessages();

        // ① 上下文层 —— 它是可丢的派生层：失败或熔断都【不是】异常，继续回源即可
        if (contextCircuit.allowAttempt()) {
            try {
                List<Message> cached = contextCache.get(conversationId, limit);
                contextCircuit.recordSuccess();
                if (cached != null) {
                    // ★ 本层存"原始消息"，裁剪在这里统一做（裁剪规则只有 MemoryTrimmer 一处）
                    return MemoryTrimmer.trim(cached, budget);
                }
            } catch (Exception e) {
                contextCircuit.recordFailure();
                log.warn("[记忆] 上下文层读取失败（熔断 {}s，其间直接回源记录层）：会话={} 原因={}",
                        circuitBreakSeconds, conversationId, e.getMessage());
            }
        } else {
            log.debug("[记忆] 上下文层处于熔断窗口，跳过并回源记录层：会话={}", conversationId);
        }

        // ② 记录层 —— 读不到就降级为"无历史"（与"检索失败降级"一致：读不到记忆不该让对话失败）
        if (!recordCircuit.allowAttempt()) {
            log.error("[记忆] ★ 记录层处于熔断窗口，本次按【无历史】降级 —— 模型将看不到既往对话：会话={}",
                    conversationId);
            return List.of();
        }
        List<Message> fromRecord;
        try {
            fromRecord = recordStore.readLatest(conversationId, limit);
            recordCircuit.recordSuccess();
        } catch (Exception e) {
            recordCircuit.recordFailure();
            log.error("[记忆] ★ 记录层读取失败，本次按【无历史】降级 —— 模型将看不到既往对话：会话={}",
                    conversationId, e);
            return List.of();
        }

        // ③ 回填上下文层 —— 用 replace 而不是 append：语义是"本层现在应当等于记录层说的内容"
        if (contextCircuit.allowAttempt()) {
            try {
                contextCache.replace(conversationId, fromRecord);
                contextCircuit.recordSuccess();
            } catch (Exception e) {
                contextCircuit.recordFailure();
                log.warn("[记忆] 上下文层回填失败（不影响本次结果，下次仍可从记录层重建）：会话={} 原因={}",
                        conversationId, e.getMessage());
            }
        }
        return MemoryTrimmer.trim(fromRecord, budget);
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // ① 记录层（权威·完整）—— 必须先写它，否则"上下文永不比记录新"这条不变量就没了
        if (!recordCircuit.allowAttempt()) {
            log.error("[记忆] ★ 记录层处于熔断窗口，本轮【未落库】—— 用户将永久看不到这一轮：会话={}",
                    conversationId);
            return;
        }
        try {
            recordStore.append(conversationId, messages);
            recordCircuit.recordSuccess();
        } catch (Exception e) {
            recordCircuit.recordFailure();
            log.error("[记忆] ★ 记录层写入失败，本轮【未落库】—— 用户将永久看不到这一轮：会话={}",
                    conversationId, e);
            // ★ 刻意【不写】上下文层：否则会出现"模型知道、用户刷新却看不到"的分歧。
            //   宁可一致地少，也不要"用户与模型看到的不一样"。
            return;
        }

        // ② 上下文层（可丢）—— 失败只记 WARN
        if (!contextCircuit.allowAttempt()) {
            log.debug("[记忆] 上下文层处于熔断窗口，跳过追加：会话={}", conversationId);
            return;
        }
        try {
            contextCache.append(conversationId, messages);
            contextCircuit.recordSuccess();
        } catch (Exception e) {
            contextCircuit.recordFailure();
            log.warn("[记忆] 上下文层追加失败（不影响正确性，下次读取会从记录层重建）：会话={} 原因={}",
                    conversationId, e.getMessage());
        }
    }

    @Override
    public void clear(String conversationId) {
        removeFromBoth(conversationId, "清空", recordStore::clear);
    }

    @Override
    public void delete(String conversationId) {
        removeFromBoth(conversationId, "删除", recordStore::delete);
    }

    /**
     * clear / delete 的公共实现。
     *
     * <p>★ <b>刻意不受熔断影响</b>：这是用户明确要求"把数据删掉"。
     * "这一层刚才失败过"不构成跳过的理由 —— 跳过会让用户继续看到已被要求删除的内容。
     */
    private void removeFromBoth(String conversationId, String action, Consumer<String> recordAction) {
        try {
            recordAction.accept(conversationId);
        } catch (Exception e) {
            recordCircuit.recordFailure();
            log.error("[记忆] ★ 记录层{}会话失败：{}", action, conversationId, e);
            // 继续往下清上下文层：记录层失败不该让"用户仍能在缓存里看到"成为结果
        }
        try {
            contextCache.evict(conversationId);
        } catch (Exception e) {
            contextCircuit.recordFailure();
            log.warn("[记忆] 上下文层{}会话失败：{} 原因={}", action, conversationId, e.getMessage());
        }
    }
}
