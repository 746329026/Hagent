package com.hagent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkflowPlanParser} 的"宽进严出"。
 *
 * <p>宽进三件事必须实测、不能靠"应该没问题"：① Markdown 围栏与前后寒暄；② 多出未知字段；
 * ③ 漏填可选字段。严出三件事同理：不是 JSON、没有 steps、步数超限。
 */
class WorkflowPlanParserTest {

    @Test
    @DisplayName("★ 围栏 + 寒暄 + 未知字段：都能解析（模型输出真实长这样）")
    void parsesFencedJsonWithSmallTalk() {
        String raw = """
                好的，我先规划一下：

                ```json
                {
                  "goal": "回答年假政策问题",
                  "notes": "这个问题需要查两次",
                  "steps": [
                    {"index": 7, "goal": "检索年假条款", "doneWhen": "拿到条款原文"},
                    {"index": 8, "goal": "按司龄给出结论", "extra": 123}
                  ]
                }
                ```

                需要我开始吗？
                """;

        WorkflowPlan plan = WorkflowPlanParser.parse(raw);

        assertThat(plan.goal()).isEqualTo("回答年假政策问题");
        assertThat(plan.size()).isEqualTo(2);
        assertThat(plan.step(1).goal()).isEqualTo("检索年假条款");
        assertThat(plan.step(1).doneWhen()).isEqualTo("拿到条款原文");
        assertThat(plan.step(2).doneWhen())
                .as("模型没给 doneWhen 时是空串（合法的『自行判断』），不是解析失败")
                .isEmpty();
        assertThat(plan.step(2).index())
                .as("★ 步骤号以数组顺序为准 —— 模型写的 7/8 不采信")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("不是 JSON → InvalidPlanException，消息里带原文片段（便于定位是哪次调用）")
    void nonJsonIsRejected() {
        assertThatThrownBy(() -> WorkflowPlanParser.parse("我觉得这个问题很难，先不规划了。"))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("没有 JSON 对象")
                .hasMessageContaining("先不规划了");
    }

    @Test
    @DisplayName("JSON 合法但没有 steps → 拒绝")
    void missingStepsIsRejected() {
        assertThatThrownBy(() -> WorkflowPlanParser.parse("{\"goal\":\"x\"}"))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("没有 steps");
    }

    @Test
    @DisplayName("★ 9 步 → 拒绝（超限的判定在 WorkflowPlan，解析器只负责归一异常类型）")
    void tooManyStepsIsRejected() {
        String raw = """
                {"goal":"g","steps":[
                  {"goal":"1"},{"goal":"2"},{"goal":"3"},{"goal":"4"},{"goal":"5"},
                  {"goal":"6"},{"goal":"7"},{"goal":"8"},{"goal":"9"}]}
                """;

        assertThatThrownBy(() -> WorkflowPlanParser.parse(raw))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    @DisplayName("某一步缺 goal → 拒绝，并说清是第几步")
    void stepWithoutGoalIsRejected() {
        String raw = "{\"goal\":\"g\",\"steps\":[{\"goal\":\"a\"},{\"doneWhen\":\"x\"}]}";

        assertThatThrownBy(() -> WorkflowPlanParser.parse(raw))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("第 2 步");
    }

    @Test
    @DisplayName("JSON 结构错（steps 不是数组）→ InvalidPlanException，不抛原始 Jackson 异常")
    void malformedJsonIsNormalized() {
        assertThatThrownBy(() -> WorkflowPlanParser.parse("{\"goal\":\"g\",\"steps\":\"不是数组\"}"))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("不是合法 JSON");
    }
}
