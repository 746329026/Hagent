package com.hagent.observability;

/**
 * 一轮对话的<b>完整账</b> —— 一行 {@code chat_turn}，或者一个 {@code stats} 事件。
 *
 * <h2>★ 它同时服务两件事（这是刻意的，不是混淆）</h2>
 *
 * <pre>
 *   审计    "上周三这个人做了什么、成没成"     ← 主体（user_id/role）+ 结局（status/error）
 *   可观测  "这一轮为什么慢、花了多少钱"        ← 耗时（elapsed/first_token/tool_ms）+ 次数 + token
 * </pre>
 *
 * <p>两者共用<b>同一批原始事实</b>：一轮对话既是审计主体，也是指标的采样点。
 * 拆成两张表会让"这一轮花了多少 / 成没成"这两个问题要 join 才能回答，
 * 而它们本来就是同一件事的两面。
 *
 * <h2>★ 字段口径（与 {@code db/chat_turn.sql}、{@link TurnStats} 必须一致）</h2>
 *
 * <ul>
 *   <li>{@code elapsedMs} —— 订阅开始 → {@code Final} 产出（<b>不含</b>写回与落库）；</li>
 *   <li>{@code firstTokenMs} —— 第一片 Token/Reasoning 到达；<b>null = 一个字都没吐出来</b>
 *       （workflow 里它包含规划耗时，因为那段时间用户看到的就是空白）；</li>
 *   <li>{@code toolMs} —— 工具段总耗时。<b>没有单工具耗时</b>：框架的
 *       {@code ToolCallingManager.executeToolCalls} 是一次整体调用，没有单工具钩子；</li>
 *   <li>{@code *Tokens} —— <b>整轮累加</b>，不是"最后一次调用"。
 *       阶段 14 之前的 {@code Final.usage} 是最后一片的量，实测会低估 3~7 倍；</li>
 *   <li>{@code usageMissingCalls} —— 其中几次没拿到 usage。
 *       <b>必须有它</b>：{@code EmptyUsage.getPromptTokens()} 返回 0（javap 实证），
 *       不区分就会把"没数据"记成"这轮只花了 0 token"。</li>
 * </ul>
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
