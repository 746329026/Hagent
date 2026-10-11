package com.hagent.advisor;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;

/**
 * 本项目全部切面 <b>order 的具名常量</b> —— 顺序的唯一出处。
 *
 * <h2>★ 为什么必须具名，不能直接写数字</h2>
 *
 * <p>两个理由，第二个更硬：
 *
 * <ol>
 *   <li><b>可读性。</b>{@code MIN_VALUE + 200} 这个数字本身不表达任何语义，
 *       而 {@link #MEMORY_READ} 表达了"这是记忆读取那一层"。</li>
 *   <li><b>order 会进入可观测性标签。</b>这一点是从框架字节码里读出来的
 *       （{@code DefaultAroundAdvisorChain.nextCall}）：
 *       <pre>
 *         AdvisorObservationContext.builder()
 *             .advisorName(advisor.getName())
 *             .chatClientRequest(request)
 *             .order(advisor.getOrder())      // ★ order 进了 observation 上下文
 *             .build();
 *         AdvisorObservationDocumentation.AI_ADVISOR.observation(...).observe(() -> advisor.adviseCall(...));
 *       </pre>
 *       也就是说，每个切面在指标/追踪里会带一个 {@code order} 标签。
 *       若写成裸数字，监控面板上出现的就是一堆 {@code -2147483448} ——
 *       没人能看出那是"记忆读取"。<b>裸数字会污染可观测性。</b></li>
 * </ol>
 *
 * <h2>★ 为什么全部落在 {@code Integer.MIN_VALUE} 附近（这是本轮关键决策）</h2>
 *
 * <p>框架的 {@link ToolCallingAdvisor} 默认 order 是
 * {@code DEFAULT_ORDER = -2147483348}，即 {@code Integer.MIN_VALUE + 300}（实测）。
 * 它自带<b>工具调用循环</b>：模型每要求调一次工具，它就把整条内层链<b>再跑一遍</b>。
 *
 * <pre>
 *   ToolCallingAdvisor（MIN+300，自带循环）
 *     └─ 内层链：本项目的切面会被【重复执行 N 次】，N = 工具调用轮数
 * </pre>
 *
 * <p>所以"把切面放在 {@code MIN+300} 之前"等价于说：
 * <b>本项目所有自定义切面都在工具循环之外，一次请求只会被调用一次。</b>
 * 这条结构性质带来三个后果，都是本轮明确要的：
 *
 * <table border="1">
 *   <caption>放在循环之外的收益</caption>
 *   <tr><th>#</th><th>收益</th><th>不这么做会怎样</th></tr>
 *   <tr><td>1</td><td>记忆写回天然"一次请求一次"</td>
 *       <td>循环内会被写 N 次，把"我要调工具"这类中间态污染进会话历史</td></tr>
 *   <tr><td>2</td><td>检索只发生一次（成本不乘 N）</td>
 *       <td>N 次 embedding + N 次向量查询，用户的问题根本没变</td></tr>
 *   <tr><td>3</td><td>拼装是唯一的"重建消息列表"者，且只重建一次</td>
 *       <td>若在循环内重建，会丢掉框架自己累积的
 *           {@code tool_call}/{@code tool_result} 消息（已被
 *           {@code AdvisorChainMessageSemanticsTest} 实验 2 用断言证明）</td></tr>
 * </table>
 *
 * <p><b>⚠️ 这个结构依赖"框架循环在我们的外侧"。</b>
 * 万一将来框架改了 {@code DEFAULT_ORDER}，或者有人用
 * {@code ToolCallingAdvisor.builder().advisorOrder(...)} 把循环挪到内侧，
 * 上表三条全部失效 —— 而且<b>现在测不出来</b>（没有工具时 N 恒等于 1）。
 * 所以 {@code AdvisorOrderTest} 里有一条断言直接对着
 * {@link ToolCallingAdvisor#DEFAULT_ORDER} 校验，而不是写死 {@code -2147483348}：
 * 框架一变，测试立刻失败。
 *
 * <h2>★ 相邻切面为什么这样排</h2>
 *
 * <pre>
 *   MIN+100  PromptTraceAdvisor         最外层：after 最后执行 → 记到全链耗时
 *   MIN+150  MemoryWritebackAdvisor     after 早于日志 → 写回成功与否能被日志记录
 *   MIN+200  MemoryReadAdvisor          只读历史，必须早于拼装
 *   MIN+220  RagRetrievalAdvisor        只做检索，必须早于拼装
 *   MIN+250  PromptAssemblyAdvisor      ★ 唯一重建消息列表者，必须最靠近模型
 *   ────────────────────────────────── 以上全在工具循环之外
 *   MIN+300  ToolCallingAdvisor（框架）  工具循环
 *   MAX      ChatModelCallAdvisor（框架）模型
 * </pre>
 *
 * <p><b>200 与 220 的先后不能反。</b>它不是在排"谁先跑"这么随意 ——
 * 多轮对话里用户会说"那它的报销标准呢？"，检索的 query 需要用历史来消解指代。
 * 当前实现里检索只用本次用户消息做 query（历史注入与 query 构造尚未打通），
 * 但把这个方向固定下来，将来接的时候不需要再动顺序。
 *
 * <p><b>为什么 150 在 200 之前（写回在读取之前）。</b>
 * 这是"after 是 before 的镜像"的直接结果：order 小的在外层，
 * 而 <b>after 的执行顺序与 before 相反</b>（内层先返回）。
 * 所以 {@code MIN+150} 的 after 落在 {@code MIN+200} 的 after 之后、
 * 但落在 {@code MIN+100} 的 after 之前 —— 正好是
 * "先写回、再记日志"。详见 {@link AdvisorOrderTest} 的镜像断言。
 */
public final class AdvisorOrders {

    private AdvisorOrders() {
        // 常量类，禁止实例化
    }

    /**
     * 调用链日志（响应 + 全链耗时）。
     *
     * <p>为什么在最外层：<b>after 的执行顺序与 before 相反</b>，
     * 最外层的 after 最后执行，因此它测到的耗时包含链上其它所有切面的开销 ——
     * 这才是"这次请求花了多久"。
     *
     * <p>⚠️ 它在 before 侧记到的是<b>注入前</b>的 prompt。
     * 想要"模型真正收到的 prompt"，看 {@link #PROMPT_ASSEMBLY} 那一层打的日志。
     * 两条日志的分工是刻意拆开的（详见 {@code PromptAssemblyAdvisor}）。
     */
    public static final int OUTER_TRACE = Integer.MIN_VALUE + 100;

    /**
     * 记忆写回（把本轮的"问 + 答"落库）。
     *
     * <p>拆成独立切面的理由：它与"记忆读取"的<b>位置要求相反</b>。
     * 读取必须在拼装之前（它的产出要被拼装消费），而写回必须在整条链<b>收尾</b>时做
     * （它需要最终的完整回复）。挤在同一个切面里时，两条要求会互相拉扯 ——
     * 这正是本轮把原 {@code ConversationMemoryAdvisor} 一分为二的原因。
     */
    public static final int MEMORY_WRITEBACK = Integer.MIN_VALUE + 150;

    /** 记忆读取：加载历史 → 写 context。<b>只读</b>，不改 prompt。 */
    public static final int MEMORY_READ = Integer.MIN_VALUE + 200;

    /** 知识库检索（含权限过滤）→ 片段写 context。<b>只读</b>，不改 prompt。 */
    public static final int RAG_RETRIEVAL = Integer.MIN_VALUE + 220;

    /**
     * 提示词拼装 —— <b>全链路唯一重建消息列表的切面</b>。
     *
     * <p>"唯一"是硬要求，不是风格：{@code AdvisorChainMessageSemanticsTest} 实验 2
     * 已经用断言证明，两个切面各自重建列表时，先跑的那个注入的内容
     * <b>会被后跑的静默丢弃</b>。所以前面两层只往 context 里放片段，不碰 prompt。
     *
     * <p>为什么紧挨着循环（{@code MIN+250}）：它必须是<b>最后一个</b>改 prompt 的人，
     * 否则后面的人会把它拼好的内容覆盖掉。
     */
    public static final int PROMPT_ASSEMBLY = Integer.MIN_VALUE + 250;

    /**
     * 工具循环的位置（框架默认值）—— <b>只用于校验，不用于注册</b>。
     *
     * <p>引用框架常量而不是写死数字：框架若改了默认 order，
     * {@code AdvisorOrderTest} 会立刻失败并指出差异，而不是等到阶段 8
     * 工具循环把我们的切面跑 N 次时才发现。
     */
    public static final int TOOL_CALLING = ToolCallingAdvisor.DEFAULT_ORDER;

    /**
     * 链尾调模型的位置（框架固定值）—— 同上，只用于校验。
     *
     * <p>实测 {@code ChatModelCallAdvisor.getOrder()} 返回 {@code Integer.MAX_VALUE}。
     */
    public static final int MODEL_CALL = Integer.MAX_VALUE;
}
