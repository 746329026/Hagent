package com.hagent.memory;

import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 记忆的<b>使用模式</b> —— 决定"本次调用愿意为历史付出多少预算"。
 *
 * <h2>★ 为什么需要这个枚举（一段设计修正的记录）</h2>
 *
 * <p>最初 {@code MemoryProperties} 里有两套预算（会话 5000 / 工作流 50000），
 * 但当时那个记忆切面（{@code ConversationMemoryAdvisor}，<b>已在阶段 6 后半拆成
 * {@code MemoryReadAdvisor} + {@code MemoryWritebackAdvisor}</b>）里的预算选择方法是这么写的：
 * <pre>
 *   private TokenBudget budgetFor(Prompt prompt) {
 *       return conversationBudget;   // ← 永远返回会话预算
 *   }
 * </pre>
 * 也就是说：<b>工作流预算配置了、却没有任何代码读它</b>。
 * 更糟的是它的注释写着"阶段 9 会在 context 里放 MODE 标记"——
 * 一个<b>不存在的方法参数</b>（{@code prompt}）＋一个<b>尚未存在的机制</b>，
 * 等于把"我描述了它"当成了"我做了它"。这正是本项目反复在防的那类问题。
 *
 * <p><b>本枚举就是那个缺失的机制</b>：模式不再是"靠猜"或"埋在实现里"，
 * 而是<b>调用方显式声明、经 context 传递</b>的一个有类型的概念。
 *
 * <h2>为什么模式属于"记忆"这个包，而不是"app"或"advisor"包</h2>
 *
 * <p>因为它的唯一作用是<b>选择记忆预算</b>。放在 app 包会让 app 层依赖 advisor 的细节；
 * 放在 advisor 包会让 app 层依赖切面。放在 memory 包 ——
 * 它和 {@link TokenBudget}、{@link ConversationMemory} 在一起，
 * 是"记忆"这件事的一部分，谁都能引用而不产生循环依赖。
 *
 * <h2>当前实现状态（诚实声明）</h2>
 *
 * <p>阶段 6 只有 {@link #CONVERSATION} 被真正使用 ——
 * {@link #WORKFLOW} 的常量、默认值与整条传参通道都已就位，
 * 但<b>工作流引擎尚未实现</b>（阶段 9），所以暂时没有任何调用方会传它。
 *
 * <p>这与"配了没人读"有本质区别，但<b>也还没到"已验证"</b>：
 * <pre>
 *   修正前：workflowBudget 字段被赋值 → 无任何代码读它    → 配置无效（真 bug）
 *   修正后：传参通道打通、有默认值、有类型校验与告警      → 通道可用，但【尚无真实调用方】
 *   待办  ：阶段 9 工作流引擎落地后，用一个真实调用把它跑通
 * </pre>
 * 也就是说，{@link #WORKFLOW} 目前是一个<b>已接好线、但还没通电的开关</b>。
 * 刻意现在不加"假调用方"去凑验证 —— 那只会产生"看起来已验证"的假象。
 *
 * <h2>阶段 9 要做的标记（留给后续接手）</h2>
 *
 * <p>届时需要改的地方<b>只有 app 层一处</b>：
 * <pre>
 *   // ChatAppService（同步路径）与 streamWithServerSentEvent（主力流式路径）
 *   .advisors(a -&gt; a.param(MemoryMode.CONTEXT_KEY, MemoryMode.WORKFLOW))
 *                                         ^^^^^^^^^^^^^^^^^^^^^^^ 改这里
 * </pre>
 * <b>切面、适配器、三个存储实现都不需要改</b> —— 它们只认
 * {@link #CONTEXT_KEY} 与预算表，不关心谁在调用。
 *
 * <p>另外阶段 9 要一并确认：工作流模式下<b>预算取 50000 token 是否够</b>
 * （多步循环里每一轮的工具返回都可能是一整篇文档）；
 * 若不够，改的是 {@code application.yml} 里的 {@code workflow-max-tokens}，
 * <b>不是代码</b>。
 *
 * <h2>★ 上下文 key 的命名</h2>
 *
 * <p>{@link #CONTEXT_KEY} 的取值刻意模仿框架常量
 * {@link ChatMemory#CONVERSATION_ID}（{@code "chat_memory_conversation_id"}）的风格：
 * 全小写 + 下划线 + 带 {@code hagent_} 前缀。
 * 加前缀是为了<b>避免与框架将来新增的 key 撞名</b>——
 * context 是一个扁平的 {@code Map<String,Object>}，撞名会导致<b>静默覆盖</b>。
 */
public enum MemoryMode {

    /**
     * 会话模式 —— 一次问答。
     *
     * <p>特征：交互频繁、用户在意响应速度、历史通常很短。
     * 预算取值见 {@code hagent.memory.chat.conversation-max-*}。
     */
    CONVERSATION,

    /**
     * 工作流 / Agent 模式 —— 多步循环。
     *
     * <p>特征：每一步的工具返回（可能是一整篇文档）都会成为下一轮输入，
     * 所以预算要比会话模式大一个数量级。
     * 预算取值见 {@code hagent.memory.chat.workflow-max-*}。
     *
     * <p>⚠️ 阶段 6 尚未有调用方传这个值（工作流引擎在阶段 9）。
     */
    WORKFLOW;

    /**
     * 放进 {@code ChatClientRequest.context()} 的 key。
     *
     * <p>用法：{@code .advisors(a -> a.param(MemoryMode.CONTEXT_KEY, MemoryMode.WORKFLOW))}
     *
     * <p>取值不带默认值语义 —— 取不到时由 {@code MemoryReadAdvisor}
     * 落到 {@link #CONVERSATION}（更保守、更省成本）。
     */
    public static final String CONTEXT_KEY = "hagent_memory_mode";

    /**
     * 取不到模式标记时使用的默认值。
     *
     * <p>为什么默认会话模式而不是工作流：两者预算差 10 倍，
     * 猜错的方向必须是"更省"的那一边 —— 猜大了会白烧 token，猜小了只是历史短一点。
     */
    public static final MemoryMode DEFAULT = CONVERSATION;
}
