package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环上限配置，绑定 {@code hagent.agent.*}。
 *
 * <p>★ {@code ignoreUnknownFields = false}：这一段配置<b>只有两个合法键</b>，
 * 写错（例如 {@code chat-max-step} 少一个 s、或残留已删除的 {@code executor.*}）时
 * <b>启动直接失败</b>，而不是被 Spring 默认的"忽略未知字段"吞掉。
 * 这正是本次搬家的病因 —— {@code hagent.agent.max-steps} 曾经配着却没人读，
 * 而<b>没有任何提示</b>（协议第 12 条规则 1、踩坑 #39 的同一类）。
 *
 * <h2>★ 为什么它<b>不</b>在 {@code hagent.memory.chat} 下（阶段 12 搬家）</h2>
 *
 * <p>阶段 9 把"一次对话最多允许几步"写进了 {@code MemoryProperties.maxSteps}（已删除），
 * 于是配置形如：
 * <pre>
 *   hagent.memory.chat.max-steps: 10     ← 键说"记忆"，值管"循环"
 * </pre>
 *
 * <p>它错在两处：
 * <ol>
 *   <li><b>名字指向别处</b>：步数上限是 <b>agent 循环的终止条件</b>，与"记忆预算"无关
 *       （记忆预算管的是"历史能有多长"，见 {@link MemoryProperties#getChatMaxTokens()}）；</li>
 *   <li><b>没有模式维度</b>：{@code application.yml} 里曾有一个
 *       {@code hagent.agent.max-steps: 20}，<b>写对了数值（它是工作流那档）、写错了位置、也没人读</b> ——
 *       配置看起来是 20，真正生效的是另一个键里的 10（协议第 12 条规则 1：配了没人读）。</li>
 * </ol>
 *
 * <h2>★ 字段名里的 {@code chat}</h2>
 *
 * <p>它指<b>工作模式</b>（枚举 {@link com.hagent.memory.MemoryMode#CHAT}），不是"会话数据"
 * —— 后者的词是 {@code conversation}。字段名与配置键 {@code chat-max-steps}、
 * 枚举值 {@code CHAT} 现在指向同一个概念（阶段 12 统一），
 * 三者之间的翻译只发生在 {@code AgentPipeline#maxStepsOf} 的那一个穷尽 switch 里。
 *
 * <h2>★★ 本类的核心语义：<b>按模式分档</b></h2>
 *
 * <p>chat 与 workflow 的"合理步数"不是一个数量级的东西：
 * <pre>
 *   chat      一次问答：查两次资料 + 追问一次 + 汇总 → 10 步足够
 *   workflow  多步循环：每一步的工具返回都可能是整篇文档 → 给到 20 步
 * </pre>
 * 所以是两个字段，不是一个"全局上限"。**两档的取值差异本身就是设计意图**，不是遗漏。
 *
 * <p>★ 由 {@code AgentPipeline#maxStepsOf(MemoryMode)} 用<b>穷尽 switch</b> 读这里：
 * 新增 {@link com.hagent.memory.MemoryMode} 时编译器会报错，
 * 而不是运行期悄悄用某个默认值（与 {@code budgetOf} 同一道保证）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.agent", ignoreUnknownFields = false)
public class AgentLoopProperties implements InitializingBean {

    /**
     * chat 模式：一次对话里<b>最多允许的模型调用步数</b>。
     *
     * <p><b>为什么必须有这个上限</b>：工具循环的正常出口是"模型不再请求工具"
     * （参考实现与主流 harness 都这么做）。但那是一个<b>由模型控制的</b>出口 ——
     * 模型如果绕圈（反复请求同一个工具、或永远不停），循环就不会自己结束，
     * 而<b>每一次循环都是一次计费的模型调用</b>。
     *
     * <p>所以这是"由我们控制的强制出口"，它与下面几条一起构成完整的终止条件：
     * <pre>
     *   ① 模型不再请求工具          ← 正常出口（模型决定）
     *   ② 工具声明 returnDirect      ← 工具决定结束（框架翻译成 finishReason=returnDirect）
     *   ③ 本上限 + 框架的工具调用次数上限  ← 防御出口（我们决定）
     *   ④ 上游异常                   ← 错误出口
     * </pre>
     *
     * <p><b>取值依据</b>：10 步足够覆盖"查两次资料 + 追问一次 + 汇总"这类常见形态；
     * 再多通常意味着模型在绕圈。超限时<b>不抛异常</b>，而是用已累积的内容收尾
     * （见 {@code AgentPipeline}）—— 用户已经等到了一些结果，丢掉它没有好处。
     *
     * <p>★ 与框架的 {@code maxCallsPerTool} / {@code maxTotalToolCalls} 是<b>两层</b>：
     * 框架那层防"同一个工具被调太多次"，本层防"整轮步数太多"。
     */
    private int chatMaxSteps = 10;

    /**
     * workflow 模式：一次对话里最多允许的模型调用步数。
     *
     * <p><b>为什么比 chat 大一倍</b>：工作流的每一轮工具返回（可能是一整篇制度文档）
     * 都会成为下一轮的输入，所以"查资料 → 发现还缺一块 → 再查 → 汇总"这种形态
     * 在 chat 里是例外、在工作流里是常态。
     *
     * <p>★ 这个值曾经以 {@code hagent.agent.max-steps: 20} 的形式存在，但没被任何代码读过
     * —— 也就是说<b>工作流实际一直在用 chat 的 10 步</b>。阶段 12 把它认领到正确的名字下。
     *
     * <p>⚠️ 阶段 12 起它<b>真的生效</b>：前端可以选"工作流"（{@code Composer.vue}），
     * 选中的会话上限从 10 变 20。这是本次搬家<b>唯一</b>的行为变化，已明确接受。
     */
    private int workflowMaxSteps = 20;

    /**
     * 启动期校验 —— <b>配错就让应用起不来</b>（决策 8：不静默降级）。
     *
     * <p>为什么这条必须存在：步数上限填 0 或负数时，工具循环<b>一次都不会跑</b>，
     * 表现是"模型明明要调工具，却直接收到一个空回答" ——
     * 这种症状没人会往"配置填了个 0"上想。
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
