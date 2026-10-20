package com.hagent.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型的<b>一段文本</b>解析成 {@link WorkflowPlan}（纯函数，不碰网络、不碰 Spring）。
 *
 * <h2>★ 为什么要这么"宽进严出"</h2>
 *
 * <p><b>宽进</b>：模型输出常常长这样 ——
 * <pre>
 *   好的，我先规划一下：
 *   ```json
 *   {"goal": "...", "steps": [...]}
 *   ```
 *   需要我开始吗？
 * </pre>
 * 直接 {@code readValue} 必然失败。所以先把第一个 {@code {} 到最后一个 {@code }} 之间的
 * 内容抠出来，**再**解析；未知字段一律忽略（模型爱加 {@code "notes"} 就加）。
 *
 * <p><b>严出</b>：解析完立刻交给 {@link WorkflowPlan} 校验（步数 1..8、编号连续、goal 非空），
 * 不合法就抛 {@link InvalidPlanException}，<b>绝不"尽量凑一份计划出来"</b>。
 * 凑出来的计划会让执行阶段跑一条谁都没批准过的路线。
 *
 * <p>★ 步骤号以<b>数组顺序</b>为准（1、2、3…），模型写下的 {@code index} 不采信 ——
 * 那是它可以随手写错的东西，而数组顺序是它无法含糊的东西。
 */
public final class WorkflowPlanParser {

    /**
     * ★ 自带一个 mapper，而不是从 Spring 注入。
     *
     * <p>理由：本包是<b>纯领域</b>（零 Spring 依赖），而这份 DTO 只有
     * {@code goal} / {@code steps} / {@code doneWhen} 三个字符串字段 ——
     * 用不到项目里任何自定义 Jackson 配置。注入一个 mapper 会把"谁能构造解析器"
     * 绑到容器上，测试与领域都跟着变重。
     *
     * <p>{@code ObjectMapper} 在配置完成后是线程安全的，所以静态共享没问题。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowPlanParser() {
    }

    /**
     * @param modelOutput 规划调用的原始文本
     * @throws InvalidPlanException 抠不出 JSON / JSON 不合法 / 计划不合规
     */
    public static WorkflowPlan parse(String modelOutput) {
        String json = extractJson(modelOutput);
        Draft draft;
        try {
            draft = MAPPER.readValue(json, Draft.class);
        } catch (RuntimeException e) {
            throw new InvalidPlanException("计划不是合法 JSON：" + e.getMessage(), e);
        }
        if (draft == null || draft.steps() == null || draft.steps().isEmpty()) {
            throw new InvalidPlanException("计划里没有 steps（模型给的是：" + brief(json) + "）");
        }
        List<PlanStep> steps = new ArrayList<>(draft.steps().size());
        int index = 0;
        for (DraftStep raw : draft.steps()) {
            index++;
            if (raw == null) {
                throw new InvalidPlanException("计划第 " + index + " 步是空的");
            }
            steps.add(toStep(index, raw));
        }
        try {
            return new WorkflowPlan(draft.goal(), steps);
        } catch (IllegalArgumentException e) {
            throw new InvalidPlanException(e.getMessage(), e);
        }
    }

    private static PlanStep toStep(int index, DraftStep raw) {
        try {
            return new PlanStep(index, raw.goal(), raw.doneWhen());
        } catch (IllegalArgumentException e) {
            throw new InvalidPlanException("计划第 " + index + " 步不合规：" + e.getMessage(), e);
        }
    }

    /**
     * 抠出第一个 {@code {} 到最后一个 {@code }} 之间的内容。
     *
     * <p>为什么用一个"最笨"的规则而不是去识别 Markdown 围栏：围栏有好几种写法
     * （{@code ```json} / {@code ```} / 无围栏），而"最外层的花括号"在所有写法里都成立。
     */
    static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidPlanException("模型输出里没有 JSON 对象（原文：" + brief(text) + "）");
        }
        return text.substring(start, end + 1);
    }

    /** 报错里带原文片段，但别把上千字全塞进异常消息。 */
    private static String brief(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }

    /** 解析用的中间形态 —— 只建模我们要的字段，其余忽略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(String goal, List<DraftStep> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftStep(String goal, String doneWhen) {
    }
}
