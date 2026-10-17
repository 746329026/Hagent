package com.hagent.stream;

/**
 * SSE 事件名的**唯一出处**。
 *
 * <p><b>为什么要集中成常量，而不是散落字符串</b>（对应参考实现的一个坑）：
 * 事件名是<b>前后端契约</b>——前端根据事件名分支渲染。如果后端多个类里各写一份
 * {@code "message"}、{@code "done"}，就会"靠人工对齐"，改一个漏三个，拼错还不报错，
 * 前端表现为"某个事件静默不渲染"。
 *
 * <p>集中到这里的规则：<b>业务代码永远写 {@code StreamEventType.MESSAGE}，
 * 不出现裸字符串 {@code "message"}</b>。将来前端改名，只改这一处。
 *
 * <p><b>★ 扩展位（本阶段只放三个，不是遗漏，是克制）</b>：
 * 后续阶段会新增事件，但"现在不发出的事件，现在就不定义"——
 * 提前定义一堆没人发的事件，就是"备而不用"（本项目的血泪：清过两次这类债）。
 * 将来要加，只需：
 * <ol>
 *   <li>这里加一行常量（如阶段 9 的 {@code STEP}、阶段 10 的 {@code QUESTION}）；</li>
 *   <li>前端加一个分支。</li>
 * </ol>
 * 成本 ≈ 0，所以不需要现在预支。
 *
 * <p><b>注意"工具调用"不会作为一个事件名出现</b>：它是模型<b>内部</b>的决策，
 * 用户关心的是"做到第几步了"（阶段 9 的"步骤轨迹"），而不是"模型请求调工具"这个信号。
 * 所以未来大概率是 {@code STEP} 事件承载"正在做第 N 步"，
 * 工具调用体现在 step 事件的内容里，而不是独立成事件。
 */
public final class StreamEventType {

    /** 模型逐片输出的文字（回答正文）。 */
    public static final String MESSAGE = "message";
    /**
     * 模型的<b>思考过程</b>逐片输出（阶段 8 新增，DeepSeek 的 {@code reasoningContent}）。
     *
     * <p><b>为什么它是独立的事件名而不是混进 {@code message}</b>：
     * 前端要把"思考区"与"回答区"渲染成两块不同的内容，语义也不同 ——
     * 思考是过程，回答是结果。混在一个事件里，前端只能靠内容猜边界。
     *
     * <p>实测模型的输出是<b>严格两段式</b>：先一串只有思考的片，再一串只有回答的片，
     * 两段<b>完全互斥</b>（思考段正文恒为 null，回答段思考恒为 null）。
     * 所以前端只要在收到第一个 {@code message} 时收起思考区即可。
     *
     * <p>⚠️ 它与 {@code message} 有一处<b>不对称</b>：思考内容<b>可能整体缺失</b>
     * （它是模型提供方的独有字段，换掉 DeepSeek 就取不到），
     * 而回答正文不会。前端不应假定一定会收到它。
     */
    public static final String REASONING = "reasoning";

    /** 正常结束信号。 */
    public static final String DONE = "done";

    /** 出错信号。 */
    public static final String ERROR = "error";

    /**
     * 模型<b>请求调用工具</b>（阶段 9 新增）。
     *
     * <p>数据是 JSON 字符串：{@code {"id":"…","name":"retrieveKnowledge","arguments":"{…}"}}。
     *
     * <p><b>为什么要单独一个事件名</b>：这是 agent 最直观的可视化点 ——
     * 前端据此显示"正在查询知识库…"。它与 {@link #TOOL_RESULT} 之间有真实的执行耗时，
     * 所以必须<b>先发这个</b>，否则用户在那段时间里看不到任何反馈。
     */
    public static final String TOOL_CALL = "tool_call";

    /** 工具执行完成（阶段 9 新增）：{@code {"id":"…","name":"…","result":"…"}}。 */
    public static final String TOOL_RESULT = "tool_result";

    /**
     * 知识库检索命中的片段（阶段 9 新增，由检索工具发出）。
     *
     * <p>数据是片段列表 —— 前端据此显示"参考了哪几篇"。
     * ★ 它是<b>结构化数据</b>（含来源、相关度），刻意不从工具返回的文本里解析
     * （那段文本是给模型看的，见 {@code ToolEventSink} 的注释）。
     */
    public static final String RETRIEVED = "retrieved";

    // ==========================================================================
    // 扩展位（未来阶段启用，当前不定义，见类注释）：
    //   STEP      —— 阶段 9：步骤轨迹（"正在做第 N 步"）
    //   QUESTION  —— 阶段 10：中断续跑（模型停下来问用户）
    //   TODO      —— 阶段 11：任务列表更新
    // ==========================================================================

    private StreamEventType() {
        // 工具类，禁止实例化
    }
}
