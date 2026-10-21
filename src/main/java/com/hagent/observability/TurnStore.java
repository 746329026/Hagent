package com.hagent.observability;

/**
 * <b>回合账的存储端口</b> —— 一轮对话一行，落进 {@code chat_turn}。
 *
 * <h2>★ 它解决什么问题（协议第 7 条三问）</h2>
 *
 * <table border="1">
 *   <caption>三问的答案</caption>
 *   <tr><th>问</th><th>答</th></tr>
 *   <tr><td>① 解决什么具体问题</td>
 *       <td>在这个阶段之前，系统答不出四个问题：<b>这轮花了多少 token / 为什么慢 /
 *           成功率多少 / 上周三那次为什么失败</b>。
 *           已有的三个出口都不够：日志（会轮转、无法聚合）、
 *           {@code Final.usage}（只是<b>最后一次</b>调用的量，实测低估 3~7 倍）、
 *           工具审计（只有调用明细，没有"轮"这个概念）。</td></tr>
 *   <tr><td>② 产出谁消费</td>
 *       <td>① <b>人</b>：SQL 直查"成功率 / P95 耗时 / 单轮成本"；
 *           ② <b>前端</b>：回答下方那一行"7.2s · 2 步 · 3032 tokens"（{@code stats} 事件）。</td></tr>
 *   <tr><td>③ 不做会怎样</td>
 *       <td>成本与成功率<b>永远只有一个日志里的抽样</b>：问"上周三那批为什么慢"时，
 *           只能翻已轮转的 {@code .gz} 日志做肉眼统计；
 *           而"失败的轮次"在库里<b>零痕迹</b>（失败不写回记忆），成功率连分母都没有。</td></tr>
 * </table>
 *
 * <h2>★ 为什么只有 {@code record} 一个方法</h2>
 *
 * <p>{@code ToolInvocationStore} 里有一个 {@code latest(...)}，全项目<b>零调用</b>
 * （阶段 14 复核时核实）。这里刻意<b>不再造第二个</b>：排查用 SQL 直查，
 * 集成测试用 {@code JdbcTemplate} 断言 —— 测试不是往端口上加死方法的理由
 * （协议第 7 条：没有消费者的产出就是死代码）。
 *
 * <h2>★ 写入失败不能影响本次对话</h2>
 *
 * <p>与 {@code ToolInvocationStore} / {@code ConversationMemory#append} 同一立场：
 * <b>账本不在关键路径上</b>。模型回复已经产生、token 已经花掉 ——
 * 为"账写不进去"丢掉它是双重损失。实现必须吞掉异常并记 ERROR
 * （<b>不能静默</b>：静默丢账就等于这一轮的统计凭空少了，成功率会失真而没人知道）。
 */
public interface TurnStore {

    /**
     * 记录一轮对话。
     *
     * <p>调用点<b>只有一个</b>：{@code AgentPipeline#run} 的 {@code doFinally} ——
     * 成功 / 失败 / 取消三种收尾都走它。若将来出现第二个调用点，
     * 说明"单一收尾"这条结构被破坏了，成功率的分子分母迟早对不上。
     *
     * @param record 本轮的完整账（非 null；实现应容忍 null 为无操作，便于测试桩）
     */
    void record(TurnRecord record);

    /**
     * 批量按轮次标识取账（阶段 14 后补）—— <b>历史回放的消费者</b>。
     *
     * <p>没有它，"刷新页面后那一行统计消失"：前端拿到的历史里每条消息只知道自己属于
     * 哪个会话，不知道"是哪一轮产生的"，而账在 {@code chat_turn} 里。
     *
     * <p>★ 为什么是<b>批量</b>而不是 {@code find(String)}：一次历史回放要显示
     * 整段对话里每一轮的数字。逐个查会变成 N+1 次 SQL —— 会话越长越慢，
     * 而它恰恰是"打开一个长会话"时最容易感觉到的卡顿。
     *
     * <p>★ <b>数字只有一个来源</b>：回放与实时显示的都是 {@code chat_turn} 里的同一行，
     * 所以"刷新前后数字不一样"在结构上不可能发生（协议第 13 条）。
     *
     * @param turnIds 轮次标识集合；<b>空集合或 null 返回空 Map</b>（调用方不用特判）
     * @return {@code turnId → 账}；查不到的 id 不会出现在结果里（旧数据、
     *         或账写入失败的轮次 —— 这两种情况都<b>不编造</b>）
     */
    java.util.Map<String, TurnRecord> find(java.util.Collection<String> turnIds);
}
