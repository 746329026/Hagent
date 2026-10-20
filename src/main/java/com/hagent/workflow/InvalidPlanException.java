package com.hagent.workflow;

/**
 * 计划无法产生或无法解析 —— 把"模型输出不合规"归一到<b>一种</b>异常。
 *
 * <p>为什么要有这个类型：规划阶段会踩到很多种失败（不是 JSON、字段缺失、
 * 步数超限、编号不连续……）。如果让调用方逐个 {@code catch}，
 * 早晚会漏掉一种，然后表现为 {@code 500}。归一到一种之后，
 * 调用方只有一条分支：<b>降级并告知</b>。
 *
 * <p>它<b>不是</b>"程序错误"：模型输出不可控，这是可预期的失败，
 * 所以它是 {@link RuntimeException} 但不该被当成 bug 上报。
 */
public class InvalidPlanException extends RuntimeException {

    public InvalidPlanException(String message) {
        super(message);
    }

    public InvalidPlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
