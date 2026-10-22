package com.hagent.app;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * 测试用的 {@link ToolCallbackProvider} 简单实现。
 *
 * <p>管线构造器要求框架的 {@code ToolCallbackProvider} 形态，而框架现成实现都要求
 * "工具对象"或带 {@code @Tool} 的类；测试只需要"指定的一批 ToolCallback"：
 * {@link #none()} 一个工具都不给（验证退化成纯对话），
 * {@link #of(ToolCallback...)} 给脚本化的假工具，不必真写 {@code @Tool} 类。
 *
 * <p>用接口形态而不是直接塞 {@code ToolCallback[]}，是为了让测试的装配方式与生产一致 ——
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
