package com.hagent.dto;

import java.util.List;

/**
 * 任务意图 —— 办公场景下对用户请求的结构化理解结果。
 *
 * <p><b>为什么需要它</b>：办公 Agent 的第一步不是"直接调模型自由发挥"，而是
 * <b>结构化地理解用户要做什么</b>。拿到结构化结果后，下游才能做确定性的判断：
 * 该不该调工具、要不要先问用户确认、需要产出什么文件类型。
 *
 * <p><b>设计要点：schema 管格式，提示词管语义</b>
 * <ul>
 *   <li>本 record 的结构（有哪些字段、什么类型）会由框架生成 schema 交给模型，
 *       用来约束<b>输出形状</b>；</li>
 *   <li>但每个字段<b>表示什么含义</b>、合法取值有哪些、边界如何判断 ——
 *       schema 完全表达不了，必须写在提示词里
 *       （见 {@code resources/prompts/task-intent-prompt.md}）。</li>
 * </ul>
 * 典型例子：schema 能说"deliverables 是字符串数组"，
 * 但"EMAIL 代表对外发送、ANSWER 代表只需口头回答"只能由提示词定义。
 *
 * @param intent           一句话概括用户想完成的事（≤20 字）
 * @param deliverables     期望的产出物，见 {@link Deliverable}
 * @param constraints      用户提出的硬性要求（模板、收件人、范围等），可为空数组
 * @param needConfirmation 执行前是否需要向用户二次确认
 */
public record TaskIntent(
        String intent,
        List<Deliverable> deliverables,
        List<String> constraints,
        boolean needConfirmation
) {

    /**
     * 产出物类型 —— 就是我们定义的<b>业务字典</b>。
     *
     * <p><b>★ 关于枚举与提示词的"双字典"问题（一个真实的维护陷阱）</b>：
     * 这个枚举和提示词里那段"取值范围：PPT / EXCEL / ..."描述的是同一件事，
     * 但两者之间<b>没有任何编译期联系</b>。失步时：
     * <ul>
     *   <li>枚举加了值、提示词没加 → 模型<b>永远选不到</b>这个值（静默失效）；</li>
     *   <li>提示词加了值、枚举没加 → 模型返回该值时 Jackson 反序列化失败（明确报错）。</li>
     * </ul>
     * 这与本项目已经踩过的"工具名散落""SSE 事件名散落"是同一类病。
     *
     * <p><b>处理方式</b>：不追求"零代码改动的可扩展"（那要把业务含义一起外部化，
     * 会变成更难维护的配置地狱），而是承认"加一个值要改两处"，
     * 同时用{@link com.hagent.app.TaskIntentAppService 解析后的校验}把
     * "改了没同步"变成<b>明确失败</b>而不是静默失效。
     *
     * <p>换句话说，<b>"可扩展"的正确含义是"加值只改定义它的地方，且忘同步会 fail-fast"</b>，
     * 而不是"运行时随便加值"。
     */
    public enum Deliverable {
        /** 生成演示文稿 */
        PPT,
        /** 生成表格 / 数据表 */
        EXCEL,
        /** 生成文档（Word / Markdown 等） */
        DOCUMENT,
        /** 生成数据报表 */
        REPORT,
        /**
         * 对外发送类产出（起草/发送邮件等）。
         *
         * <p>★ 保留它不只是"以后可能做邮件"，更重要的原因是：
         * {@code needConfirmation} 的判断标准里包含"对外发送"这类动作 ——
         * 如果字典里没有任何代表"发送"的产出物，那个字段就<b>永远没有触发场景</b>，
         * 会退化成一个备而不用的字段。
         */
        EMAIL,
        /** 只需口头回答，不产出文件 */
        ANSWER,
        /**
         * 无法判断。
         *
         * <p><b>★ 为什么需要这个"显式未知"，而不是用空数组兜底</b>：
         * 空数组会把两种语义压成一种 ——
         * ①"确实判断出只需回答"与 ②"根本没解析出来"。
         * 而这两种情况下游要做的事<b>恰好相反</b>：
         * 前者正常走问答，后者应当追问用户。
         * 空值无法区分，下游只能靠猜 —— 正是本项目一直在避免的"靠约定推测"。
         */
        UNKNOWN
    }
}
