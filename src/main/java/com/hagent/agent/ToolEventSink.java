package com.hagent.agent;

/**
 * 工具向外<b>同步发出事件</b>的通道。
 *
 * <p>为什么不让工具在返回值里带事件：返回值是<b>给模型看的字符串</b>（模型只能读文本），
 * 而"本次参考了哪几篇文档"是<b>给前端看的结构化数据</b> —— 结构化数据走结构化通道，
 * 别让它经过文本再被解析回来。
 *
 * <p>为什么是同步回调而不是 {@code Flux}：工具执行路径是<b>阻塞</b>的
 * （{@code ToolCallingManager.executeToolCalls} 内部反射调用工具并等结果），
 * 在那里返回 {@code Flux} 没有人会订阅 —— 所以是"发出即忘记"，由管线接到自己的事件流上。
 *
 * <p>实现方必须线程安全：模型可能一次请求多个工具；框架当前顺序执行
 * （{@code DefaultToolCallingManager} 逐个调用），但不应依赖"只会有一个线程"。
 */
@FunctionalInterface
public interface ToolEventSink {

    /**
     * 发出一个事件。
     *
     * <p><b>实现方不应抛出异常</b>：工具执行路径上抛出的异常会被框架转成
     * "工具失败"的文本，从而把一次正常的检索变成用户看到的失败 ——
     * 发出事件失败不该影响工具本身的成败。
     */
    void emit(PipelineEvent event);
}
