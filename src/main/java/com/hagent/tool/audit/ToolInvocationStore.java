package com.hagent.tool.audit;

import java.util.List;

/**
 * <b>工具调用审计</b> —— 记录"谁在什么时候调了什么工具、拿到了什么"。
 *
 * <p>工具调用是<b>模型自主发起、代码实际执行</b>的动作（可能查了不该查的数据、可能被提示注入
 * 诱导），没有审计就无法回答"上周三这个会话到底调了什么"；而只留在日志里既会被轮转，
 * 也无法按会话或工具名做结构化查询。
 *
 * <p><b>它与 {@code ConversationMemory} 是两件不同的事，不要合并</b>：前者面向"对话本身"
 * （用户可见记录 + 模型上下文），本接口面向<b>事后追溯</b>。合并会同时破坏两边语义 ——
 * 对话记录被审计数据污染，而审计会继承"预算裁剪""可重建缓存"这些与它无关的概念。
 *
 * <p><b>写入失败不能影响本次对话</b>：审计不在关键路径上，模型回复已经产生、token 已经花掉，
 * 为"审计写不进去"丢掉它才是双重损失。实现必须吞掉异常并记 ERROR
 * （<b>不能静默</b>：静默丢审计等于没审计）。
 */
public interface ToolInvocationStore {

    /**
     * 记录一次工具调用。
     *
     * <p><b>参数是一个 record 而不是五个参数</b>：五个同类型（多为 String）的位置参数极易传错顺序，
     * 而编译器不会报错 —— 那正好产出"看起来正常、实际字段串位"的审计数据，比没有审计更危险。
     *
     * @param invocation 本次调用的完整快照
     */
    void record(ToolInvocation invocation);

    /**
     * 一条工具调用的审计快照 —— 审计的核心问题一直是「<b>谁</b>在什么时候对<b>什么</b>做了什么」。
     *
     * <p>{@code conversationId} 只答得了"什么时候、对什么"，缺了主体就只是一张"操作日志"。
     * 而审计数据<b>只能从现在开始攒</b>，所以这两列一并留出：
     * {@code turnId} 与 {@code chat_turn.turn_id} 同值，把工具活动归到具体一轮，
     * 从而能和 {@code chat_turn.tool_calls} 对账（不等即说明审计写入失败过）；
     * {@code userId} 是主体，<b>历史行不伪造</b>（假装知道比如实承认更糟）。
     *
     * @param conversationId 会话标识（可与 {@code chat_message} 关联）
     * @param turnId         本轮唯一标识（与 {@code chat_turn.turn_id} 同值；早期行为 {@code null}）
     * @param userId         调用方标识（早期行为 {@code "unknown"}）
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
