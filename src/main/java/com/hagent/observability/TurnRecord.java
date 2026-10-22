package com.hagent.observability;

/**
 * 一轮对话的<b>完整账</b> —— 一行 {@code chat_turn}，或者一个 {@code stats} 事件。
 *
 * <p>同时服务审计（谁、成没成）与可观测（为什么慢、花了多少）：两者共用<b>同一批原始事实</b>，
 * 拆成两张表会让"这轮花了多少 / 成没成"要 join 才能回答。
 *
 * <p>字段口径（与 {@code db/chat_turn.sql}、{@link TurnStats} 必须一致）：{@code elapsedMs} 不含写回与落库；
 * {@code firstTokenMs} 为 null 表示一个字都没吐出来（workflow 里它含规划耗时）；
 * {@code toolMs} 只有整段粒度：{@code ToolCallingManager.executeToolCalls} 没有单工具钩子；
 * {@code *Tokens} 是<b>整轮累加</b>而非"最后一次调用"（{@code Final.usage} 只是最后一片，实测低估 3~7 倍）；
 * {@code usageMissingCalls} 不可省（{@code EmptyUsage.getPromptTokens()} 返回 0，
 * 不区分就会把"没数据"记成"这轮只花了 0 token"）。
 *
 * @param turnId            本轮唯一标识（轮次开始时生成；工具审计按它归轮）
 * @param conversationId    会话标识
 * @param userId            调用方标识（匿名时 {@code "anonymous"}）
 * @param role              角色名（{@code Role} 的枚举名）
 * @param mode              模式的<b>线上名</b>（{@code chat} / {@code workflow}），不是枚举名
 * @param status            结局（见 {@link TurnStatus} 的判定矩阵）
 * @param finishReason      模型/流程的停止原因（{@code stop} / {@code maxSteps} / {@code stepFailed} / null）
 * @param elapsedMs         整轮耗时
 * @param firstTokenMs      首个 token 耗时（null = 没有）
 * @param modelCalls        本轮模型调用次数
 * @param toolCalls         本轮工具调用次数（与审计表可对账）
 * @param toolMs            工具段总耗时
 * @param promptTokens      整轮输入 token（null = 一次都没拿到）
 * @param completionTokens  整轮输出 token
 * @param totalTokens       整轮合计 token
 * @param usageMissingCalls 没拿到 usage 的调用次数
 * @param planSteps         工作流计划步数（chat 为 null）
 * @param errorMessage      失败/取消的原因（只存 message，不存堆栈）
 */
public record TurnRecord(String turnId,
                         String conversationId,
                         String userId,
                         String role,
                         String mode,
                         TurnStatus status,
                         String finishReason,
                         long elapsedMs,
                         Long firstTokenMs,
                         int modelCalls,
                         int toolCalls,
                         long toolMs,
                         Integer promptTokens,
                         Integer completionTokens,
                         Integer totalTokens,
                         int usageMissingCalls,
                         Integer planSteps,
                         String errorMessage) {
}
