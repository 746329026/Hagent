package com.hagent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkflowPlan} / {@link PlanStep} 的校验规则。
 *
 * <p>这些规则的价值不在"能跑"，而在<b>拒绝</b>：一份不合规的计划若被接受，
 * 执行阶段会跑一条没人批准过的路线，且事后无法归因（"到底是哪一步错了"）。
 */
class WorkflowPlanTest {

    private static PlanStep step(int index, String goal) {
        return new PlanStep(index, goal, "");
    }

    @Test
    @DisplayName("正常计划：能按 1-based 取步、能判断步骤号是否存在")
    void validPlan() {
        WorkflowPlan plan = new WorkflowPlan("整理年假政策并回答", List.of(
                step(1, "查年假政策"),
                step(2, "对比员工司龄档位"),
                step(3, "给出结论")));

        assertThat(plan.size()).isEqualTo(3);
        assertThat(plan.step(2).goal()).isEqualTo("对比员工司龄档位");
        assertThat(plan.hasStep(3)).isTrue();
        assertThat(plan.hasStep(4)).isFalse();
        assertThat(plan.hasStep(0)).isFalse();
    }

    @Test
    @DisplayName("★ 步数超过上限 → 拒绝（不是截断成前 8 步）")
    void tooManyStepsIsRejected() {
        List<PlanStep> nine = List.of(
                step(1, "a"), step(2, "b"), step(3, "c"), step(4, "d"), step(5, "e"),
                step(6, "f"), step(7, "g"), step(8, "h"), step(9, "i"));

        assertThatThrownBy(() -> new WorkflowPlan("目标", nine))
                .as("超限必须拒绝：截断后的计划看起来安排得很好，实际漏掉了后半段任务")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过上限");
        assertThat(WorkflowPlan.MAX_STEPS).isEqualTo(8);
    }

    @Test
    @DisplayName("步数下限：0 步拒绝")
    void emptyPlanIsRejected() {
        assertThatThrownBy(() -> new WorkflowPlan("目标", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何步骤");
    }

    @Test
    @DisplayName("★ 编号必须从 1 连续：跳号 / 从 0 开始都拒绝")
    void indicesMustBeContiguousFromOne() {
        assertThatThrownBy(() -> new WorkflowPlan("目标", List.of(
                step(1, "a"), step(3, "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("连续递增");

        assertThatThrownBy(() -> new WorkflowPlan("目标", List.of(
                step(0, "a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("从 1 开始");
    }

    @Test
    @DisplayName("goal 不能为空（空白串也算空）—— 空目标的一步没法执行")
    void blankGoalIsRejected() {
        assertThatThrownBy(() -> new PlanStep(1, "   ", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goal");

        assertThatThrownBy(() -> new WorkflowPlan("   ", List.of(step(1, "a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goal");
    }

    @Test
    @DisplayName("doneWhen 允许为空 —— 表示『由模型自行判断』，不是漏填")
    void doneWhenIsOptional() {
        PlanStep s = new PlanStep(1, "查政策", null);
        assertThat(s.doneWhen()).isEmpty();
        assertThat(new PlanStep(1, "查政策", "  拿到条款原文  ").doneWhen()).isEqualTo("拿到条款原文");
    }
}
