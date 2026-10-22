package com.hagent.workflow;

import java.util.List;

/**
 * 一次工作流运行的<b>计划</b> —— 执行的唯一依据，也是"这次到底要做什么"的书面记录。
 *
 * <h2>它与 chat 的差别</h2>
 *
 * <p>不是"多步"（chat 本来就在跑一个完整的多步工具循环，模型自己决定下一步做什么），
 * 而是本类补了 chat <b>没有</b>的三件事：
 * <ol>
 *   <li><b>先拆解</b>：执行之前就有一份步骤清单，而不是走一步看一步；</li>
 *   <li><b>步与目标的对应</b>：每一步有 goal 与完成判据，而不只是"第 N 次模型调用"；</li>
 *   <li><b>可被指认</b>：失败时能说"第 2 步没做成"，而不是"模型这次没答好"。</li>
 * </ol>
 *
 * <h2>为什么要校验得这么死（宁可拒绝，不要截断）</h2>
 *
 * <p>计划超限时<b>直接拒绝</b>，不截断成前 N 步：截断后的计划看起来"安排得很好"，
 * 实际漏掉了后半段任务，而用户与模型都不会知道漏了什么 —— 那正是本项目反复在防的
 * "看起来成功"。拒绝会让调用方明确降级（退回 chat 循环并告知），是可观测的。
 *
 * @param goal  这次要解决的问题（一句话）
 * @param steps 有序的步骤，编号必须从 1 连续递增
 */
public record WorkflowPlan(String goal, List<PlanStep> steps) {

    /**
     * 计划最多几步。
     *
     * <p><b>为什么是 8</b>：workflow 的模型调用上限是 20 步
     * （{@code hagent.agent.workflow-max-steps}），而一步平均要 2 次调用
     * （请求工具 + 汇总）。8 步 × 2 ≈ 16，留 4 步给重试与最终汇总。
     * 步数超过这个数的计划<b>在预算内根本跑不完</b> —— 让它"看起来安排得很好"
     * 才是最坏的结果。
     */
    public static final int MAX_STEPS = 8;

    public WorkflowPlan {
        goal = goal == null ? "" : goal.trim();
        if (goal.isEmpty()) {
            throw new IllegalArgumentException("计划缺少 goal（这次要解决的问题）");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("计划没有任何步骤");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "计划步数 " + steps.size() + " 超过上限 " + MAX_STEPS
                            + "（预算内跑不完，会变成『看起来做完了』）");
        }
        for (int i = 0; i < steps.size(); i++) {
            int expected = i + 1;
            if (steps.get(i).index() != expected) {
                throw new IllegalArgumentException(
                        "步骤号必须从 1 连续递增：第 " + expected + " 个位置上的编号是 "
                                + steps.get(i).index());
            }
        }
    }

    public int size() {
        return steps.size();
    }

    /** 按 <b>1-based</b> 的步骤号取步骤。 */
    public PlanStep step(int index) {
        if (!hasStep(index)) {
            throw new IllegalArgumentException("没有第 " + index + " 步（本计划共 " + size() + " 步）");
        }
        return steps.get(index - 1);
    }

    /** 这个步骤号是否存在（1-based）。 */
    public boolean hasStep(int index) {
        return index >= 1 && index <= steps.size();
    }
}
