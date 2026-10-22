package com.hagent.stream;


/**
 * SSE 事件名的<b>唯一出处</b>。
 *
 * <p>事件名是<b>前后端契约</b>（前端按事件名分支渲染）：散落成多个字符串就会"靠人工对齐"，
 * 改一个漏三个、拼错还不报错，前端表现为"某个事件静默不渲染"。规则是业务代码永远写
 * {@code StreamEventType.MESSAGE}、不出现裸字符串，改名只改这一处。
 *
 * <p>只定义当前真的会发出的事件（"备而不用"的债本项目清过两次），新增成本只有两行。
 */
public final class StreamEventType {

    /**
     * 模型逐片输出的文字（回答正文），数据是 JSON 字符串：{@code {"text":"…"}}。
     *
     * <p>必须是对象而非裸字符串：{@code SseEmitter.event().data(String)} <b>不转义换行</b>，
     * token 里的 {@code \n} 会切断 SSE 帧、吃掉段落空行；包成 JSON 后帧边界不再依赖内容。
     */
    public static final String MESSAGE = "message";
    /**
     * 模型的<b>思考过程</b>逐片输出（DeepSeek 的 {@code reasoningContent}），格式同 {@link #MESSAGE}。
     *
     * <p>独立事件名：前端要把思考区与回答区渲染成两块，语义也不同（过程 vs 结果），
     * 混进 {@code message} 只能靠内容猜边界。模型输出是两段互斥的（思考段正文恒为 null，
     * 反之亦然），所以收到第一个 {@code message} 即可收起思考区。
     *
     * <p>⚠️ 与 {@code message} 不对称：思考内容<b>可能整体缺失</b>（换成非 DeepSeek 模型就没有），
     * 前端不应假定一定会收到。
     */
    public static final String REASONING = "reasoning";

    /** 正常结束信号。 */
    public static final String DONE = "done";

    /** 出错信号：{@code {"message":"…"}}（同样是 JSON 对象，理由见 {@link #MESSAGE}）。 */
    public static final String ERROR = "error";

    /**
     * 模型<b>请求调用工具</b>，数据：{@code {"id":"…","name":"retrieveKnowledge","arguments":"{…}"}}。
     *
     * <p>单独一个事件名：这是 agent 最直观的可视化点，且它与 {@link #TOOL_RESULT} 之间有真实的
     * 执行耗时，必须<b>先发这个</b>，否则用户在那段时间里看不到任何反馈。
     */
    public static final String TOOL_CALL = "tool_call";

    /** 工具执行完成：{@code {"id":"…","name":"…","result":"…"}}。 */
    public static final String TOOL_RESULT = "tool_result";

    /**
     * <b>第 N 步开始</b> —— 工具调用的"骨架"，数据：{@code {"step":2}}。
     *
     * <p>不能靠"看到 tool_call 就知道开始了"：模型在决定调工具前要<b>思考很久</b>（实测可达十几秒），
     * 那段时间前端面板是空的、用户分不清"在想"和"卡住了"；且一步可能调多个工具，
     * 没有骨架时"一步调两个"与"两步各调一个"看起来一模一样。
     *
     * <p>它是<b>本回合内</b>的骨架：每次提交都从 {@code step:1} 重新开始。
     */
    public static final String STEP = "step";

    /**
     * 知识库检索命中的片段 —— 数据是列表，前端据此显示"参考了哪几篇"。
     *
     * <p>它是<b>结构化数据</b>（含来源、相关度），刻意不从工具返回的文本里解析（那段文本是给模型看的）。
     */
    public static final String RETRIEVED = "retrieved";

    /**
     * <b>工作流编排事件</b> —— 计划与步骤的骨架，<b>与模型输出无关</b>。数据是 JSON 对象，
     * {@code type} 区分三种：
     * <pre>
     *   {"type":"plan",          "goal":"…", "steps":[{"index":1,"goal":"…","doneWhen":"…"}]}
     *   {"type":"plan_skipped",  "reason":"…"}      ← 规划失败，已退回普通循环
     *   {"type":"step_finished", "step":2,"ok":true,"summary":"…"}
     * </pre>
     *
     * <p>三种共用一个事件名：它们同属"本次运行的编排骨架"，前端要么整块渲染、要么整块忽略；
     * 拆成三个只让"忽略"变成"忽略三个"。{@code type} 是本事件内的<b>次级判别</b>。
     */
    public static final String WORKFLOW = "workflow";

    /**
     * <b>本轮统计</b> —— 耗时 / 次数 / token / 结局，在 {@code done} 之前发一次。数据是 JSON 对象：
     * <pre>
     *   {"status":"SUCCESS","elapsedMs":7270,"firstTokenMs":1203,"modelCalls":2,
     *    "toolCalls":2,"totalTokens":3032}
     * </pre>
     * {@code firstTokenMs} 与 {@code totalTokens} <b>可能缺失</b>（模型一个字都没吐 / 没拿到 usage）——
     * 缺失表示"没有数据"，<b>不要当成 0</b>。
     *
     * <p>单独一个事件名而不塞进 {@code done}：{@code done} 的载荷是字面量 {@code [DONE]}，
     * 改它属于"改已有契约"；不认识新事件的消费者本来就走 default 分支 —— 纯增量、风险更低。
     * 刷新页面后这行数字会消失（历史回放不 join {@code chat_turn}），是刻意的取舍。
     */
    public static final String STATS = "stats";

    private StreamEventType() {
        // 工具类，禁止实例化
    }
}
