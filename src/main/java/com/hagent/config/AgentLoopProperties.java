package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环上限配置，绑定 {@code hagent.agent.*}。
 *
 * <p>★ {@code ignoreUnknownFields = false}：这一段只有两个合法键，写错（如少一个 s、
 * 或残留已删除的键）时<b>启动直接失败</b>，而不是被 Spring 默认的"忽略未知字段"吞掉 ——
 * 后者正是"配置写了却没人读、且毫无提示"的来源。
 *
 * <p>★ <b>按模式分档</b>：chat 与 workflow 的"合理步数"不是一个数量级
 * （chat 一次问答 ~10 步；workflow 每轮工具返回都可能是整篇文档 ~20 步），
 * 所以是两个字段而非一个全局上限，取值差异本身是设计意图。
 * 它们由 {@code AgentPipeline#maxStepsOf(MemoryMode)} 用<b>穷尽 switch</b> 读取：
 * 新增模式时编译期报错，而不是运行期悄悄套默认值。
 *
 * <p>字段名里的 {@code chat} 指<b>工作模式</b>（{@link com.hagent.memory.MemoryMode#CHAT}），
 * 与会话数据的 {@code conversation} 不是一回事。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.agent", ignoreUnknownFields = false)
public class AgentLoopProperties implements InitializingBean {

    /**
     * chat 模式：一次对话里<b>最多允许的模型调用步数</b>。
     *
     * <p>为什么必须有：工具循环的正常出口是"模型不再请求工具"，那是一个<b>由模型控制</b>的
     * 出口；模型若绕圈，循环不会自己结束，而每次循环都是一次计费的模型调用。
     * 本值是我们能控制的强制出口（框架的 {@code maxCallsPerTool} / {@code maxTotalToolCalls}
     * 是另一层：防同一工具被调太多次）。
     *
     * <p>取 10：足够覆盖"查两次资料 + 追问一次 + 汇总"，再多通常意味着绕圈。
     * 超限时<b>不抛异常</b>，用已累积内容收尾 —— 用户已经等到一些结果，丢掉没有好处。
     */
    private int chatMaxSteps = 10;

    /**
     * workflow 模式：一次对话里最多允许的模型调用步数。
     *
     * <p>为什么比 chat 大一倍：工作流里每一轮工具返回（可能是一整篇制度文档）都会成为下一轮的
     * 输入，"查资料 → 发现还缺一块 → 再查 → 汇总"是常态而非例外。
     */
    private int workflowMaxSteps = 20;

    /**
     * 启动期校验 —— <b>配错就让应用起不来</b>（不静默降级）。
     *
     * <p>步数上限填 0 或负数时工具循环一次都不会跑，表现是"模型明明要调工具，
     * 却直接收到一个空回答"—— 这种症状没人会往配置填了个 0 上想。
     */
    @Override
    public void afterPropertiesSet() {
        if (chatMaxSteps <= 0) {
            throw new IllegalStateException(
                    "hagent.agent.chat-max-steps 必须为正数：" + chatMaxSteps
                            + "（它是一次对话里模型调用的步数上限，0 或负数会让工具循环无法进行）");
        }
        if (workflowMaxSteps <= 0) {
            throw new IllegalStateException(
                    "hagent.agent.workflow-max-steps 必须为正数：" + workflowMaxSteps
                            + "（它是一次对话里模型调用的步数上限，0 或负数会让工具循环无法进行）");
        }
    }
}
