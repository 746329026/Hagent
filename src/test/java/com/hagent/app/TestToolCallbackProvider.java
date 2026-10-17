package com.hagent.app;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * 测试用的 {@link ToolCallbackProvider} 简单实现。
 *
 * <h2>★ 为什么需要一个测试工具类</h2>
 *
 * <p>阶段 9 起 {@code AgentPipeline} 的构造器要求一个 {@code ToolCallbackProvider}
 * （框架的接口形态，与"可插拔注册"对齐），而框架提供的现成实现
 * （{@code MethodToolCallbackProvider} / {@code StaticToolCallbackProvider}）
 * 都要求"工具对象"或"带 {@code @Tool} 的类"。
 *
 * <p>但有两类测试只需要"**指定的一批 ToolCallback**"：
 * <ul>
 *   <li>预算测试、循环测试用 {@link #none()} —— 一个工具都不给，验证"退化成纯对话"；</li>
 *   <li>将来要验证工具循环的测试用 {@link #of(ToolCallback...)} —— 给一个脚本化的假工具，
 *       不必真写一个 {@code @Tool} 类。</li>
 * </ul>
 *
 * <p>★ <b>它取代了原先"直接往构造器塞 {@code ToolCallback[]}"的写法</b>：
 * 那种写法在"检索被关掉"时恰好也能跑（空数组），但把"注册结果"与"注册机制"混成了一个类型。
 * 用框架的接口形态之后，测试与生产的装配方式一致 ——
 * <b>测试里走的路径越接近生产，它抓到的偏差才越可信</b>。
 */
final class TestToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallback[] toolCallbacks;

    private TestToolCallbackProvider(ToolCallback[] toolCallbacks) {
        this.toolCallbacks = toolCallbacks;
    }

    /** 一个工具都不注册 —— 等价于"检索被关掉"的装配结果。 */
    static TestToolCallbackProvider none() {
        return new TestToolCallbackProvider(new ToolCallback[0]);
    }

    /** 注册指定的几个工具回调（用假实现脚本化模型将要调用的工具）。 */
    static TestToolCallbackProvider of(ToolCallback... toolCallbacks) {
        return new TestToolCallbackProvider(toolCallbacks);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.clone();
    }
}
