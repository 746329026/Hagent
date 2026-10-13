package com.hagent.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 短熔断窗口 —— 某层失败后，在一段时间内<b>直接跳过它</b>，不再尝试连接。
 *
 * <h2>★ 它解决的具体问题（不是"优雅"，是"故障期可用性"）</h2>
 *
 * <p>Redis / MySQL 挂掉时，<b>每个请求都会白等一次连接超时</b>
 * （Redis {@code timeout: 3s}、MySQL 连接池 {@code connection-timeout: 5000}）。
 * 一次对话本来 1 秒，故障期变成 4–8 秒 —— <b>故障时体验反而比正常时差得多，而且每次都白等</b>。
 *
 * <p>有了熔断窗口：第一次失败后 30 秒内不再尝试，请求立刻走降级路径。
 * 窗口结束后自动放一次请求去试探（<b>不需要额外的"半开"状态</b> ——
 * 成功即清零、失败即续期，这个语义已经够了）。
 *
 * <h2>为什么自己写而不是引入 Resilience4j</h2>
 *
 * <p>本项目需要的只是"失败后静默 N 秒"这一个行为，是 <b>一个 AtomicLong</b> 的事。
 * 引入一个熔断框架会带来：新的依赖、新的配置面、以及一套需要学习的语义
 * （半开、滑动窗口、阈值）—— 对一个"只想要 N 秒静默"的需求属于过度设计。
 *
 * <h2>线程安全</h2>
 *
 * <p>用 {@link AtomicLong} 存"解除熔断的时刻"。并发下可能有两个线程同时试探，
 * 这<b>可以接受</b>：最坏情况是多打一次失败请求，不会造成正确性问题。
 * 刻意不加锁 —— 熔断器本身不该成为串行点。
 */
public class FailureCircuit {

    /** 0 表示"未熔断"。 */
    private final AtomicLong openUntilMillis = new AtomicLong(0L);

    /** 熔断窗口长度（毫秒）。 */
    private final long breakMillis;

    /**
     * @param breakSeconds 熔断窗口秒数；{@code <= 0} 表示<b>关闭熔断</b>
     *                     （永远允许尝试 —— 便于测试与排查时对照）
     */
    public FailureCircuit(long breakSeconds) {
        this.breakMillis = Math.max(0L, breakSeconds) * 1000L;
    }

    /** 此刻是否允许尝试。 */
    public boolean allowAttempt() {
        if (breakMillis == 0L) {
            return true;
        }
        return System.currentTimeMillis() >= openUntilMillis.get();
    }

    /** 当前是否处于熔断窗口内（供日志与健康检查读取）。 */
    public boolean isOpen() {
        return !allowAttempt();
    }

    /** 记录一次失败：从此刻起静默 {@code breakSeconds} 秒。 */
    public void recordFailure() {
        if (breakMillis == 0L) {
            return;
        }
        openUntilMillis.set(System.currentTimeMillis() + breakMillis);
    }

    /** 记录一次成功：立即解除熔断。 */
    public void recordSuccess() {
        openUntilMillis.set(0L);
    }
}
