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
 * <p><b>★ 阶段 10 纠正了一段自相矛盾的注释</b>：本类早期版本写着
 * "工具调用不会作为一个事件名出现…未来大概率是 {@code STEP} 事件承载'正在做第 N 步'，
 * 工具调用体现在 step 事件的内容里，而不是独立成事件"。
 * 而阶段 9 实际把 {@code TOOL_CALL} / {@code TOOL_RESULT} 做成了独立事件
 * （{@link #TOOL_CALL} 的理由写得很清楚：调用与结果之间隔着一次真实的执行，
 * 先发"开始"前端才有反馈）。于是注释与代码直接对立。
 *
 * <p>正确的说法不是二选一，而是<b>分层</b>：
 * <pre>
 *   {@link #STEP}        骨架：第 N 步开始（没有它，工具调用只能摊成一串平铺的）
 *   {@link #TOOL_CALL}   明细：这一步里模型请求调用了什么
 *   {@link #TOOL_RESULT} 明细：这个调用返回了什么
 * </pre>
 * 一个 STEP 后面可以跟 0..N 组 CALL/RESULT。所以三者都要有，
 * 谁也不能取代谁 —— 只保留 step 会让"一步里调了哪几个工具"无处表达。
 */
public final class StreamEventType {

    /**
     * 模型逐片输出的文字（回答正文）。
     *
     * <p>数据是 JSON 字符串：{@code {"text":"…"}}。
     *
     * <p>★ <b>阶段 10 起载荷从裸字符串改成对象</b>，原因是一个实测出来的真实缺陷：
     * {@code SseEmitter.event().data(String)} <b>不转义换行</b>
     * （{@code javap -c} 读 {@code SseEmitter$SseEventBuilderImpl} 可证：只有
     * {@code append("data:")} + {@code append(数据)}）。于是 token 里的 {@code \n}
     * 会把 SSE 帧切断、把段落空行吃掉 —— 表现为"回答排版偶尔错乱"。
     * 包成 JSON 后换行被转义，帧边界不再依赖内容。
     */
    public static final String MESSAGE = "message";
    /**
     * 模型的<b>思考过程</b>逐片输出（阶段 8 新增，DeepSeek 的 {@code reasoningContent}）。
     *
     * <p>数据格式与 {@link #MESSAGE} 相同：{@code {"text":"…"}}（理由见上）。
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

    /**
     * 出错信号：{@code {"message":"…"}}。
     *
     * <p>数据是 JSON 对象而不是裸字符串 —— 与 {@link #MESSAGE} 同一个理由
     * （异常消息里带换行并不罕见，裸字符串会把 SSE 帧切断）。
     */
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
     * <b>第 N 步开始</b>（阶段 10 新增）—— 工具调用的"骨架"。
     *
     * <p>数据是 JSON 字符串：{@code {"step":2}}。
     *
     * <p><b>为什么需要它，而不是"看到 tool_call 就知道开始了"</b>：
     * <ol>
     *   <li>模型在<b>决定调工具之前要思考很久</b>（实测可达十几秒）。
     *       那段时间一个 {@code tool_call} 都还没有，前端面板是空的 ——
     *       用户分不清"在想"和"卡住了"。有了本事件，可以立刻显示"第 2 步 · 思考中"；</li>
     *   <li>一步里可能调多个工具。没有骨架，前端只能把调用摊成一串平铺的，
     *       "一步调两个"与"两步各调一个"看起来一模一样。</li>
     * </ol>
     *
     * <p>★ 它是<b>本回合内</b>的骨架，不跨回合：每次用户提交都从 {@code step:1} 重新开始。
     */
    public static final String STEP = "step";

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
    //   QUESTION  —— 阶段 10 之后：中断续跑（模型停下来问用户）
    //   TODO      —— 阶段 11：任务列表更新
    // ★ STEP 已在阶段 10 启用，见上方常量。
    // ==========================================================================

    private StreamEventType() {
        // 工具类，禁止实例化
    }
}
