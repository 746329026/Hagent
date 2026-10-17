package com.hagent.memory;

/**
 * 记忆的<b>使用模式</b> —— 决定"本次调用愿意为历史付出多少预算"。
 *
 * <h2>★ 只有两种模式，且"agent"不是其中一种（命名约定）</h2>
 *
 * <pre>
 *   CHAT      一次问答：交互频繁、用户在意响应速度、历史通常很短
 *   WORKFLOW  多步循环：每一步的工具返回（可能是一整篇文档）都会成为下一轮输入
 * </pre>
 *
 * <p>★ <b>"agent"不是一个模式</b>：agent = harness + model ——
 * 本项目后端是 harness，DeepSeek 是 model，两者合起来才叫 agent。
 * 所以模式只有 chat 与 workflow 两种，没有"agent 模式"
 * （见 {@code WORKING-AGREEMENT} 第 12 条的术语表）。
 *
 * <p>⚠️ <b>待办</b>：枚举值 {@code CONVERSATION} 与配置键里的 {@code chat} 用词不一致
 * （配置前缀已是 {@code hagent.memory.chat.*}，枚举值却叫 {@code CONVERSATION}）。
 * 本人已同意<b>单独做这笔改名</b>，不混进阶段 8 的结构重构 ——
 * 因为阶段 8 本身已有大量变更，再混入配置键改名，出问题时
 * <b>分不清是新结构还是改名引起的</b>。改的时候必须同步四处：
 * 枚举值 + {@code MemoryProperties} 的 {@code conversationMax*} 字段 +
 * {@code application.yml} 的 {@code conversation-max-*} 两个键 +
 * {@code AgentPipeline#budgetOf} 的穷尽 switch。
 *
 * <h2>★ 为什么需要这个枚举（一段设计修正的记录）</h2>
 *
 * <p>最初 {@code MemoryProperties} 里有两套预算（会话 5000 / 工作流 50000），
 * 但当时那个记忆切面（{@code ConversationMemoryAdvisor}，<b>已在阶段 6 后半拆成
 * 读 / 写两个切面，又在阶段 8 整体并入 {@code AgentPipeline}</b>）里的预算选择方法是这么写的：
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
 * 而是<b>调用方显式声明、经方法参数传递</b>的一个有类型的概念。
 *
 * <h2>为什么模式属于"记忆"这个包</h2>
 *
 * <p>因为它的唯一作用是<b>选择记忆预算</b>。放在 app 包会让 app 层依赖记忆的细节；
 * 放在别的领域包会让调用方绕远路。放在 memory 包 ——
 * 它和 {@link TokenBudget}、{@link ConversationMemory} 在一起，
 * 是"记忆"这件事的一部分，谁都能引用而不产生循环依赖。
 *
 * <h2>当前实现状态（诚实声明）</h2>
 *
 * <p>阶段 8 仍然只有 {@link #CONVERSATION} 被真正使用（{@code ChatController} 显式传它）——
 * {@link #WORKFLOW} 的常量与整条传参通道都已就位，
 * 但<b>工作流引擎尚未实现</b>（阶段 9），所以暂时没有任何调用方会传它。
 *
 * <p>这与"配了没人读"有本质区别，但<b>也还没到"已验证"</b>：
 * <pre>
 *   修正前：workflowBudget 字段被赋值 → 无任何代码读它    → 配置无效（真 bug）
 *   修正后：传参通道打通、有默认值、有编译期穷尽检查      → 通道可用，但【尚无真实调用方】
 *   待办  ：阶段 9 工作流引擎落地后，用一个真实调用把它跑通
 * </pre>
 * 也就是说，{@link #WORKFLOW} 目前是一个<b>已接好线、但还没通电的开关</b>。
 * 刻意现在不加"假调用方"去凑验证 —— 那只会产生"看起来已验证"的假象。
 *
 * <h2>阶段 9 要做的标记（留给后续接手）</h2>
 *
 * <p>届时需要改的地方<b>只有 Controller 两处</b>：
 * <pre>
 *   chatAppService.stream(..., MemoryMode.WORKFLOW, ...)
 *   chatAppService.chat(..., MemoryMode.WORKFLOW, ...)   ← 或新增的工作流入口
 * </pre>
 * <b>{@code AgentPipeline}、记忆三层、三个存储实现都不需要改</b> ——
 * 它们只认 {@code MemoryMode} 与预算表，不关心谁在调用。
 *
 * <p>另外阶段 9 要一并确认：工作流模式下<b>预算取 50000 token 是否够</b>
 * （多步循环里每一轮的工具返回都可能是一整篇文档）；
 * 若不够，改的是 {@code application.yml} 里的 {@code workflow-max-tokens}，<b>不是代码</b>。
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
     * 工作流模式 —— 多步循环。
     *
     * <p>特征：每一步的工具返回（可能是一整篇文档）都会成为下一轮输入，
     * 所以预算要比会话模式大一个数量级。
     * 预算取值见 {@code hagent.memory.chat.workflow-max-*}。
     *
     * <p>⚠️ 阶段 8 尚未有调用方传这个值（工作流引擎在阶段 9）。
     */
    WORKFLOW;

    /**
     * 取不到模式标记时使用的默认值。
     *
     * <p>为什么默认会话模式而不是工作流：两者预算差 10 倍，
     * 猜错的方向必须是"更省"的那一边 —— 猜大了会白烧 token，猜小了只是历史短一点。
     *
     * <p>阶段 8 起它的使用者是 {@code AgentPipeline#run}（参数为 null 时兜底）。
     */
    public static final MemoryMode DEFAULT = CONVERSATION;

    // ========================================================================
    // 线上名称 —— 阶段 10 新增（HTTP 请求体与数据库里存的都是它）
    // ========================================================================

    /**
     * 本模式的<b>线上名称</b>：{@code chat} / {@code workflow}。
     *
     * <h3>★ 为什么需要这么一层映射，而不是直接用 {@code name()}</h3>
     *
     * <p>枚举值叫 {@code CONVERSATION}，但<b>产品与配置里这个词一直是 {@code chat}</b>
     * （配置前缀 {@code hagent.memory.chat.*}，本文件顶部的"待办"也写着
     * 这笔改名迟早要做）。如果 HTTP 与数据库直接用 {@code name()}，
     * 那么这笔改名会从"改 4 处代码"升级成"还要迁移历史数据" —— 一个纯粹由
     * <b>命名不一致</b> 引发的数据迁移，没有任何收益。
     *
     * <p>所以把映射<b>收在这一个方法里</b>：改名时只改枚举常量与下面这个 switch，
     * 请求体、响应体、数据库三者一个字都不用动。
     *
     * <p>★ 附带的结构好处：{@code switch} 是<b>穷尽</b>的且刻意不写 {@code default}
     * —— 将来新增第三种模式会<b>编译失败</b>，而不是在运行期悄悄漏掉一个映射
     * （漏掉的症状是"新模式存进库变成了 null"）。
     */
    public String wireName() {
        return switch (this) {
            case CONVERSATION -> "chat";
            case WORKFLOW -> "workflow";
        };
    }

    /**
     * 把线上名称（或枚举名）解析成模式。
     *
     * <p>两种写法都接受：{@code chat} / {@code WORKFLOW} / {@code conversation} 都行 ——
     * 大小写不敏感，因为这是<b>人输入的参数</b>，不该因大小写不同而失败。
     *
     * <p><b>空值 → {@link #DEFAULT}</b>（调用方没声明模式是合法状态：老客户端、
     * 手工 curl）。<b>无法识别 → 抛异常</b>，而不是退回默认值 ——
     * 后者会让"客户端把 {@code wrokflow} 打错"表现为"静默用了 chat 的 1/10 预算"，
     * 而且没有人会发现，直到模型开始忘事。
     *
     * @throws IllegalArgumentException 取值无法识别
     */
    public static MemoryMode fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return DEFAULT;
        }
        String normalized = wire.trim().toLowerCase(java.util.Locale.ROOT);
        for (MemoryMode mode : values()) {
            if (mode.wireName().equals(normalized) || mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "mode 取值非法：" + wire + "（合法取值：" + "chat / workflow）");
    }

    // ========================================================================
    // ⚠️ 阶段 8 删除了 CONTEXT_KEY（原值 "hagent_memory_mode"）
    //
    //   它曾是"模式经 ChatClientRequest.context 传递"这个机制的 key。
    //   pipeline 没有 context，模式改为 run(..., mode, ...) 的【方法参数】——
    //   于是这个 key 失去了传递通道，且"拼错 key 不报错"这个风险也一起消失。
    //
    //   留下的教训（值得记住）：它当时模仿框架常量 ChatMemory.CONVERSATION_ID
    //   的命名风格（全小写 + 下划线 + hagent_ 前缀），理由是"避免与框架 key 撞名"。
    //   那条理由只在"必须往一个扁平 Map 里塞东西"时成立 ——
    //   换成方法参数后，"撞名"这个问题根本不存在。
    //   ⇒ 为绕开某个机制的限制而做的命名/结构妥协，会随那个机制一起消失，
    //     所以别把它当成永久约定传下去。
    // ========================================================================
}
