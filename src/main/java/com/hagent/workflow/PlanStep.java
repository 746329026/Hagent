package com.hagent.workflow;

/**
 * 计划里的<b>一步</b> —— 不可变值对象，<b>零框架依赖</b>。
 *
 * <p>为什么单独成型（而不是一个 String 列表）：执行阶段要按"第 k 步"定位
 * 事件、日志、失败报告，重规划时要指认"哪一步失败"。这些都需要<b>带编号的步骤</b>，
 * 而不是一句句排在一起的文字。
 *
 * @param index    从 <b>1</b> 开始的步骤号（事件里给前端、日志里定位、失败报告里指认）
 * @param goal     这一步要达成的目标。执行时它<b>原样</b>作为该步的指令，所以必须是祈使句
 * @param doneWhen "这一步算做完了"的判据。允许为空 —— 空表示"由模型自行判断"，
 *                 但<b>不表示可以跳过</b>：步与步的分界始终由我们控制，不由模型控制
 */
public record PlanStep(int index, String goal, String doneWhen) {

    public PlanStep {
        if (index < 1) {
            throw new IllegalArgumentException("步骤号必须从 1 开始：" + index);
        }
        goal = required(goal, "goal");
        doneWhen = optional(doneWhen);
    }

    private static String required(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "计划步骤缺少 " + field + "（它是执行时的输入，不能为空）");
        }
        return value;
    }

    private static String optional(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
