package com.hagent.memory;

/**
 * 记忆的<b>使用模式</b> —— 决定"本次调用愿意为历史付出多少预算"。
 *
 * <pre>
 *   CHAT      一次问答：交互频繁、用户在意响应速度、历史通常很短
 *   WORKFLOW  多步循环：每一步的工具返回（可能是一整篇文档）都会成为下一轮输入
 * </pre>
 *
 * <p><b>为什么它必须存在且是显式参数</b>：预算若靠"猜"或埋在实现里，
 * "工作流预算配了、却没有任何代码读它"这种事不会报错 —— 配置看起来生效、实际无效。
 * 模式成为一个有类型的值、由调用方声明、经方法参数传递，这个漏洞才堵住。
 *
 * <p><b>"agent"不是一种模式</b>：agent = harness + model（本项目后端是 harness，
 * DeepSeek 是 model），所以模式只有 chat 与 workflow。
 *
 * <p><b>两个词不要混</b>：{@code chat} 是<b>工作模式</b>之一（本枚举的 {@link #CHAT}），
 * {@code conversation} 是<b>会话这个数据实体</b>。所以预算字段叫 {@code chatMaxTokens}、
 * 不叫 {@code conversationMaxTokens} —— 它约束的是 chat 模式的历史长度，与"会话"无关。
 *
 * <p>它属于 memory 包：唯一作用是选择记忆预算，与 {@link TokenBudget}、{@link ConversationMemory}
 * 放在一起；放 app 包会让 app 层依赖记忆细节，放别的领域包会让调用方绕远路。
 */
public enum MemoryMode {

    /**
     * 会话模式 —— 一次问答。
     *
     * <p>特征：交互频繁、用户在意响应速度、历史通常很短。
     * 预算取值见 {@code hagent.memory.chat.chat-max-*}。
     */
    CHAT,

    /**
     * 工作流模式 —— 多步循环。
     *
     * <p>特征：每一步的工具返回（可能是一整篇文档）都会成为下一轮输入，
     * 所以预算要比会话模式大一个数量级。
     * 预算取值见 {@code hagent.memory.chat.workflow-max-*}。
     */
    WORKFLOW;

    /**
     * 取不到模式标记时使用的默认值。
     *
     * <p>默认 CHAT 而不是 WORKFLOW：两者预算差 10 倍，猜错的方向必须是"更省"的那一边 ——
     * 猜大了白烧 token，猜小了只是历史短一点。
     */
    public static final MemoryMode DEFAULT = CHAT;

    /**
     * 本模式的<b>线上名称</b>：{@code chat} / {@code workflow}。
     *
     * <p>为什么不用 {@code name()}：那是全大写 {@code CHAT}，而请求体与数据库列
     * （{@code chat_conversation.mode}）里一直是小写，且<b>已落库的值不会跟着代码改</b> ——
     * 这不是风格问题，是<b>数据契约</b>。映射收在这一个方法里，将来枚举改名时
     * 请求体、响应体、历史数据一个字都不用动。
     *
     * <p>{@code switch} 是<b>穷尽</b>的且刻意不写 {@code default}：新增第三种模式会<b>编译失败</b>，
     * 而不是在运行期悄悄漏掉一个映射（症状是"新模式存进库变成了 null"）。
     */
    public String wireName() {
        return switch (this) {
            case CHAT -> "chat";
            case WORKFLOW -> "workflow";
        };
    }

    /**
     * 把线上名称（或枚举名）解析成模式。
     *
     * <p>两种写法都接受且大小写不敏感 —— 这是<b>人输入的参数</b>，不该因大小写不同而失败。
     *
     * <p><b>空值 → {@link #DEFAULT}</b>（调用方没声明模式是合法状态：老客户端、手工 curl）；
     * <b>无法识别 → 抛异常</b>，而不是退回默认值 —— 后者会让"把 {@code wrokflow} 打错"
     * 表现为"静默用了 chat 的 1/10 预算"，而且没人会发现，直到模型开始忘事。
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
}
