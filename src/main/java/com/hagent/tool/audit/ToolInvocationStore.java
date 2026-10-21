package com.hagent.tool.audit;

import java.util.List;

/**
 * <b>工具调用审计</b> —— 记录"谁在什么时候调了什么工具、拿到了什么"。
 *
 * <h2>★ 它解决什么问题（协议第 7 条三问）</h2>
 *
 * <table border="1">
 *   <caption>三问的答案</caption>
 *   <tr><th>问</th><th>答</th></tr>
 *   <tr><td>① 解决什么具体问题</td>
 *       <td>工具调用是<b>模型自主发起、代码实际执行</b>的动作 ——
 *           它可能查了不该查的数据、可能被提示注入诱导去调某个工具。
 *           没有审计就无法回答"上周三这个会话到底调了什么"。</td></tr>
 *   <tr><td>② 产出谁消费</td>
 *       <td>① <b>人</b>：事后排查与合规审计（直接查库）；
 *           ② <b>将来的可观测性</b>：调用计数/成功率/耗时统计。</td></tr>
 *   <tr><td>③ 不做会怎样</td>
 *       <td>工具活动<b>只存在于日志里</b>。日志会轮转、会被清理，
 *           而且无法按会话或工具名做结构化查询 ——
 *           出问题时只能说"日志大概在那几天的文件里翻翻"。</td></tr>
 * </table>
 *
 * <h2>★★ 它与 {@code ConversationMemory} 是两件不同的事（不要合并）</h2>
 *
 * <pre>
 *   ConversationMemory   用户可见的对话记录 + 模型这次要用的上下文
 *                        —— 面向【对话本身】
 *   ToolInvocationStore  工具调用的审计流水
 *                        —— 面向【事后追溯】，与对话内容无关
 * </pre>
 *
 * <p>把它们合并会同时破坏两边的语义：对话记录会被审计数据污染
 * （见 {@code db/chat_tool_invocation.sql} 里的三条理由），
 * 而审计会继承对话记忆的"预算裁剪""可重建缓存"等**与它无关**的概念。
 *
 * <h2>★ 写入失败为什么不能影响本次对话</h2>
 *
 * <p>与 {@code AgentPipeline#writeBack} 同一条推理：<b>审计不在关键路径上</b>。
 * 模型回复已经产生、token 已经花掉 —— 为"审计写不进去"丢掉它才是双重损失。
 * 实现必须吞掉异常并记 ERROR（<b>不能静默</b>：静默丢审计等于没审计）。
 */
public interface ToolInvocationStore {

    /**
     * 记录一次工具调用。
     *
     * <p><b>为什么参数是一个 record 而不是五个参数</b>：五个同类型（多为 String）
     * 的位置参数极易传错顺序，而编译器不会报错 —— 那正好会产出"看起来正常、
     * 实际字段串位"的审计数据，比没有审计更危险。
     *
     * @param invocation 本次调用的完整快照
     */
    void record(ToolInvocation invocation);

    /**
     * 一条工具调用的审计快照。
     *
     * <h3>★ 阶段 14 为什么加了两列（{@code turnId} / {@code userId}）</h3>
     *
     * <p>审计的核心问题一直是「<b>谁</b>在什么时候对<b>什么</b>做了什么」，
     * 而本表在阶段 9 只回答了后两问：它有一条 {@code conversation_id}，但<b>没有主体</b>
     * （会话本身也不按用户隔离 —— 现在还没有真正的鉴权）。一张答不出主体的表只能叫"操作日志"。
     *
     * <p>而审计数据<b>只能从现在开始攒</b>：不加列，这段历史就永久缺失。所以：
     * <ul>
     *   <li>{@code turnId} —— 与 {@code chat_turn.turn_id} 同值，把工具活动<b>归到具体一轮</b>，
     *       从而能和 {@code chat_turn.tool_calls} 对账（不等即说明审计写入失败过）；</li>
     *   <li>{@code userId} —— 主体。★ <b>历史行一律是 {@code "unknown"}</b>：
     *       迁移脚本给了这个默认值，<b>不伪造</b>（假装知道比如实承认更糟）。</li>
     * </ul>
     *
     * @param conversationId 会话标识（可与 {@code chat_message} 关联）
     * @param turnId         本轮唯一标识（与 {@code chat_turn.turn_id} 同值；
     *                       阶段 14 之前的行是 {@code null}）
     * @param userId         调用方标识（阶段 14 之前的行是 {@code "unknown"}）
     * @param stepIndex      本轮第几步（从 1 开始；一次对话可能多步循环）
     * @param toolName       工具名
     * @param arguments      模型给出的参数原文（JSON 字符串；可能为 null）
     * @param result         工具返回的文本原文（模型看到的同一份；可能为 null）
     */
    record ToolInvocation(String conversationId,
                          String turnId,
                          String userId,
                          int stepIndex,
                          String toolName,
                          String arguments,
                          String result) {
    }

    /** 列出最近若干条（供排查与自检使用；实现按 id 倒序）。 */
    List<ToolInvocation> latest(String conversationId, int limit);
}
