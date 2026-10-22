package com.hagent.observability;

/**
 * <b>回合账的存储端口</b> —— 一轮对话一行，落进 {@code chat_turn}。
 *
 * <p>它回答四个问题：这轮花了多少 token / 为什么慢 / 成功率多少 / 上周三那次为什么失败。
 * 已有的三个出口都不够：日志（轮转、无法聚合）、{@code Final.usage}（只是最后一次调用的量，
 * 低估 3~7 倍）、工具审计（只有明细，没有"轮"）。消费者是人的 SQL 直查与前端那行 {@code stats}；
 * 不做的话成本与成功率永远只有日志抽样，而失败的轮次在库里<b>零痕迹</b>（成功率连分母都没有）。
 *
 * <p>端口上只有 {@code record} 与批量 {@code find}：排查用 SQL、测试用 {@code JdbcTemplate} 断言 ——
 * 测试不是往端口上加死方法的理由，没有消费者的方法就是死代码。
 *
 * <p><b>写入失败不能影响本次对话</b>：账本不在关键路径上（回复已产生、token 已花掉），
 * 为"账写不进去"丢掉它是双重损失。实现必须吞掉异常并记 ERROR —— 静默丢账会让成功率凭空失真。
 */
public interface TurnStore {

    /**
     * 记录一轮对话。
     *
     * <p>调用点<b>只有一个</b>：{@code AgentPipeline#run} 的 {@code doFinally}（成功 / 失败 / 取消都走它）。
     * 出现第二个调用点即说明"单一落账点"被破坏，成功率的分子分母迟早对不上。
     *
     * @param record 本轮的完整账（非 null；实现应容忍 null 为无操作，便于测试桩）
     */
    void record(TurnRecord record);

    /**
     * 批量按轮次标识取账 —— <b>历史回放的消费者</b>。没有它，"刷新页面后那一行统计消失"：
     * 历史里每条消息只知道自己属于哪个会话，不知道"是哪一轮产生的"。
     *
     * <p>必须是<b>批量</b>：一次回放要显示每轮的数字，逐个查会变成 N+1 次 SQL（长会话打开时最明显）；
     * 且与实时显示共用 {@code chat_turn} 里的同一行，所以"刷新前后数字不一样"结构上不可能发生。
     *
     * @param turnIds 轮次标识集合；<b>空集合或 null 返回空 Map</b>（调用方不用特判）
     * @return {@code turnId → 账}；查不到的 id 不会出现在结果里（旧数据、
     *         或账写入失败的轮次 —— 这两种情况都<b>不编造</b>）
     */
    java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds);
}
