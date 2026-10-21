package com.hagent.agent;

import com.hagent.retrieval.RetrievedChunk;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

/**
 * 一次 pipeline 运行<b>对外吐出的东西</b> —— 本项目与调用方之间的唯一数据契约。
 *
 * <h2>★ 它解决什么问题（协议第 7 条三问）</h2>
 *
 * <table border="1">
 *   <caption>三问的答案</caption>
 *   <tr><th>问题</th><th>答案</th></tr>
 *   <tr><td>① 解决什么具体问题</td>
 *       <td>把"这次调用中途发生了什么"从<b>藏在切面 context 里的副作用</b>
 *           变成<b>可被调用方逐个消费的返回值</b></td></tr>
 *   <tr><td>② 产出谁消费</td>
 *       <td>{@link ChatAppService}（`/chat` 取 {@code Final}）
 *           与 {@code ChatController}（`/stream-sse` 逐事件转成 SSE 帧）</td></tr>
 *   <tr><td>③ 不做会怎样</td>
 *       <td>前端<b>永远拿不到</b>"参考了哪几篇文档"；模型逐片输出与思考过程
 *           没有通道可以外传；阶段 9 的工具调用<b>没有任何位置可放</b></td></tr>
 * </table>
 *
 * <h2>★★ 分层：为什么 {@code Retrieved} 与 {@code ModelOutput} 是平级而不是一个概念</h2>
 *
 * <pre>
 *   PipelineEvent                      ← 顶层：一次运行对外吐出的东西
 *   ├── Retrieved(chunks, query)       ← 【我们的】检索产物
 *   ├── StepStarted(step)              ← 【我们的】骨架：第 N 步开始（阶段 10）
 *   └── ModelOutput                    ← 【模型的】产出（sealed，可继续嵌套）
 *       ├── Reasoning(text)            ← 思考增量（DeepSeek 的 reasoning_content）
 *       ├── Token(text)                ← 回答增量
 *       ├── ToolCall / ToolResult      ← 阶段 9：工具活动的开始与结束
 *       └── Final(text, reasoning, finishReason, usage)
 * </pre>
 *
 * <p><b>★ 为什么 {@code StepStarted} 在顶层而不是 {@code ModelOutput} 里</b>：
 * 它不是模型的产出，而是<b>我们编排的骨架</b> —— "要调模型了"这件事由
 * {@code AgentPipeline} 决定，不由模型决定。放进 {@code ModelOutput} 会让
 * "模型输出了第 N 步"这种说法成立，而它是错的。
 *
 * <p>它与 {@code ToolCall} / {@code ToolResult} 的关系是<b>骨架与明细</b>：
 * <pre>
 *   StepStarted(2)                      ← 第 2 步开始
 *     ToolCall(id=A, retrieveKnowledge) ← 第 2 步里调了工具 A
 *     ToolResult(id=A, …)               ← A 回来了
 * </pre>
 * 前端据此把 N 个工具调用<b>归到同一步</b>下，而不是摊成一串平铺的事件
 * （平铺的话，"一步里连调两个工具"与"两步各调一个"看起来一模一样）。
 *
 * <p><b>为什么不把 {@code Retrieved} 放进 {@code ModelOutput}</b>：
 * 两者的<b>来源、生产者、生命周期完全不同</b> ——
 * `Retrieved` 是我们在数据库里查出来的，`ModelOutput` 是模型生成的。
 * 把它们塞进同一个概念名下，会让人以为"检索结果是模型说的话"。
 *
 * <p><b>但也正因为前端只有一条 SSE 连接</b>，{@code Retrieved} 必须走这条流 ——
 * 它"不能放在一起"，也"不能搬走"。正解是<b>分层</b>，不是拆开。
 * 这正是本接口用嵌套而非两个并列顶级类型的原因。
 *
 * <h2>★★ 三条来自实测的硬约束（改动本接口前必读）</h2>
 *
 * <ol>
 *   <li><b>流式每片 text 是【增量】，不是累计</b> ——
 *       实测 184 片中最后一片 text 长度为 0，而全片拼接为 159 字。
 *       所以 {@link ModelOutput.Final#text()} <b>必须由 pipeline 自己拼接</b>，
 *       绝不能"取最后一片"。</li>
 *   <li><b>空片必须过滤</b> —— 实测片 0 是 {@code text=<null>, reasoning=<空串>}。
 *       不过滤就会往 SSE 里发空事件。</li>
 *   <li><b>思考内容只能靠 {@code instanceof} 取</b> ——
 *       {@code reasoningContent} 是 {@code DeepSeekAssistantMessage} 的独有字段，
 *       Spring AI 2.0.1 <b>没有</b>为它提供任何通用 metadata key。
 *       换掉 DeepSeek 时思考流会静默变空，所以取值处<b>取不到时必须记 WARN</b>。</li>
 * </ol>
 *
 * <h2>命名约定（{@code WORKING-AGREEMENT} 第 12 条）</h2>
 *
 * <p><b>agent = harness + model</b>：本项目后端是 harness，DeepSeek 是 model，
 * 两者合起来才叫 agent。所以"模型的产出"叫 {@code ModelOutput}，
 * <b>不叫</b> {@code AgentOutput}（那会把模型产出说成 agent 产出）。
 * 同样，本项目<b>没有"agent 模式"</b> —— 模式只有 chat 与 workflow 两种。
 */
public sealed interface PipelineEvent {

    // ========================================================================
    // 一、我们的产物：检索 + 编排骨架
    // ========================================================================
    /**
     * 检索完成（含权限过滤）—— <b>我们的产物，不是模型说的话</b>。
     *
     * <p>为什么值得外传：这是 RAG 项目对外最直观的证据 ——
     * 前端可以显示"本次参考了《员工手册》第二章"，用户据此判断答案是否可信。
     *
     * <p><b>★ 一条契约限制（前端阶段会踩到）</b>：
     * {@link RetrievedChunk} 是 record，其中 {@code level}（{@code AccessLevel}）与
     * {@code band}（{@code RelevanceBand}）经 JSON 序列化后是<b>枚举 name</b>
     * （{@code HIGH}/{@code MEDIUM}/{@code LOW}），
     * 而 {@code RelevanceBand.displayName()} 的中文"高/中/低"<b>不是属性</b>。
     * ⇒ 前端要显示中文档位需自己映射。
     * <b>本阶段刻意不加 {@code displayName} 字段</b>：还没有消费者，加了就是猜需求（协议第 7 条）。
     *
     * @param chunks 命中的片段；<b>可能为空</b>（跑了但没命中 —— 这是正常结果，不是错误）
     * @param query  实际用于检索的查询文本（= 本次用户消息）。
     *               外传它是为了让前端与排查都能确认"检索用的是什么问题"
     */
    record Retrieved(List<RetrievedChunk> chunks, String query) implements PipelineEvent {

        /** 紧凑构造器：列表防御性拷贝，避免下游改动影响上游。 */
        public Retrieved {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    /**
     * <b>第 N 步开始</b>（阶段 10 新增）—— 一个回合里"模型被第几次调用"的骨架。
     *
     * <h3>★ 它解决什么问题</h3>
     *
     * <p>一个回合里模型可能被调用多次（每次工具调用后重新决策一次）。
     * 没有这个事件，前端只能看到一串<b>平铺</b>的 {@code tool_call} / {@code tool_result}：
     * <pre>
     *   "一步里连调两个工具"  与  "两步各调一个"  →  看起来完全一样
     * </pre>
     * 而且模型<b>思考很久却还没决定调工具</b>的那段时间，前端<b>什么事件都收不到</b>
     * —— 用户盯着一个空面板，不知道是在思考还是卡住了。
     *
     * <p>有了它，前端能把这段时间显示成"第 2 步 · 思考中"，并在收到
     * {@code ToolCall} 时把它变成"第 2 步 · retrieveKnowledge · 调用中"。
     * 三种状态（思考中 / 调用中 / 已返回）分别由
     * {@code StepStarted} / {@code ToolCall} / {@code ToolResult} 推动，
     * 不需要额外事件。
     *
     * <h3>★ 为什么必须在调模型【之前】发出</h3>
     *
     * <p>顺序决定它能表达什么：先发它，才代表"这一步已经开始"。
     * 若在拿到第一片响应之后再发，那"等待模型的首字"这段时间就没有任何事件
     * —— 而那恰恰是最需要反馈的时间段。
     *
     * @param step 第几步，<b>从 1 开始</b>（与 {@code chat_tool_invocation.step_index} 同一口径）
     */
    record StepStarted(int step) implements PipelineEvent {
    }

    // ========================================================================
    // 一·五、工作流的编排骨架（阶段 13）
    // ========================================================================
    /**
     * 计划已产出 —— "这次打算分几步做"的<b>唯一可见渠道</b>（阶段 13）。
     *
     * <p><b>为什么它必须是事件，而不是只写进日志</b>：决策是"计划可见、但不停下来等确认"。
     * 既然不停，用户只能在<b>执行之前</b>看到计划 —— 否则"可见"就退化成"事后才知道"。
     *
     * <p>它与 {@link StepStarted} 的关系：本事件是<b>整份计划</b>，
     * {@code StepStarted} 是"正在做第 k 步"。前者出现一次或两次（重规划会再发一次），
     * 后者按步出现。
     */
    record PlanCreated(com.hagent.workflow.WorkflowPlan plan) implements PipelineEvent {
    }

    /**
     * 规划失败，已<b>退回普通循环</b>（降级路径，阶段 13）。
     *
     * <p>为什么降级而不是让请求失败：模型输出写好 JSON 不是我们能保证的事，
     * 而"拿不到任何回答"对用户是更坏的结果。但<b>降级必须可见</b> ——
     * 悄悄退回会让用户以为"工作流跑过了"，实际只跑了一次普通问答。
     */
    record PlanSkipped(String reason) implements PipelineEvent {
    }

    /**
     * 一步结束（阶段 13）：{@code ok=false} 时 {@code summary} 是失败原因。
     *
     * <p>失败也发事件，而不是只在日志里记一笔：否则前端会把"失败后重试"看成
     * "同一步忽然重来一次"，而不知道为什么。
     */
    record StepFinished(int step, boolean ok, String summary) implements PipelineEvent {
    }

    // ========================================================================
    // 一·六、一轮的账（阶段 14）
    // ========================================================================
    /**
     * <b>本轮收尾时的账</b>（阶段 14）—— 耗时 / 次数 / token / 结局，全链路只此一份。
     *
     * <h3>★ 为什么它是<b>顶层</b>事件，而不是 {@link ModelOutput.Final} 上的几个字段</h3>
     *
     * <p>{@code ModelOutput} 的语义是<b>模型的产出</b>（思考、正文、工具请求、结束原因）。
     * 而 {@code elapsedMs} / {@code modelCalls} / {@code toolCalls} <b>不是模型说的话</b> ——
     * 它们是我们自己数出来的，和 {@link Retrieved} / {@link StepStarted} 同类：
     * <b>我们的产物</b>。
     *
     * <p>把它们塞进 {@code Final} 会让 {@code ModelOutput} 这个分层当场失效
     * （"模型输出了本轮耗时"这种说法是错的），而这正是本文件开头那张分层图要防的事。
     *
     * <h3>★ 为什么前端需要它（而不是去查库）</h3>
     *
     * <p>用户想看到的"这次花了 7.2 秒"必须在<b>回答旁边</b>。
     * 让前端为了一个数字再发一次 HTTP 请求，等于把"观测"变成"额外的往返"。
     *
     * <h3>★ 它只出现在<b>正常收尾</b>的路径上</h3>
     *
     * <p>失败（{@code onError}）与取消（客户端断开）时流已经断了，发不出这个事件 ——
     * 但那两种轮次<b>照样会落库</b>（{@code AgentPipeline#run} 的 {@code doFinally}）。
     * 所以"库里比前端看到的多"是<b>预期的</b>：账是给事后查的，不是给前端补发的。
     *
     * @param record 本轮的完整账；前端只消费其中五项（见 {@code ChatAppService#statsData}）
     */
    record TurnFinished(com.hagent.observability.TurnRecord record) implements PipelineEvent {
    }

    // ========================================================================
    // 二、模型的产物
    // ========================================================================
    /**
     * 模型的产出 —— sealed，阶段 9 在此<b>新增变体</b>而不改动已有代码。
     *
     * <p>三种变体对应实测出来的<b>严格三段式</b>（184 片的真实分布）：
     * <pre>
     *   片 0        text=null   reasoning=空    ← 首片是空的，必须过滤
     *   片 1–89     text=null   reasoning=有    ← 思考段
     *   片 90–182   text=有     reasoning=null  ← 回答段
     *   片 183      text=空     reasoning=null  ← 收尾片（finishReason=STOP）
     * </pre>
     * 两段<b>完全互斥</b>（思考段 text 恒为 null，回答段 reasoning 恒为 null），
     * 所以前端按<b>两条独立流</b>渲染，不需要额外的"阶段切换"事件。
     */
    sealed interface ModelOutput extends PipelineEvent {

        /**
         * 思考过程的增量片（DeepSeek 的 {@code reasoning_content}）。
         *
         * <p><b>为什么单独成一种事件而不是塞进 {@link Token}</b>：
         * ① 用户要看到的是"思考区"与"回答区"两块内容，语义不同；
         * ② 模型提供方对思考的处理不一致 —— 它是一个<b>可缺失</b>的能力
         *    （换掉 DeepSeek 就取不到），做成独立变体后"没有思考"表现为
         *    <b>这种事件一个都不出现</b>，而不是"回答里混进了奇怪的东西"。
         */
        record Reasoning(String text) implements ModelOutput {
        }

        /**
         * 回答正文的增量片。
         *
         * <p>★ <b>是增量，不是累计</b>（实测）。消费方若要完整回答，
         * 必须自己拼接，或直接取 {@link Final#text()}。
         */
        record Token(String text) implements ModelOutput {
        }

        /**
         * 一次模型调用的<b>收尾</b> —— 全链路唯一携带"完整结果"的事件。
         *
         * <p><b>为什么它必须由 pipeline 构造而不是"取最后一片响应"</b>：
         * 实测最后一片 {@code text} 长度为 <b>0</b> 且 {@code reasoning} 为 null，
         * 整段内容分散在 183 片里。所以"完整文本"是<b>累积</b>出来的，
         * 累积逻辑在 pipeline 里<b>只有一个出处</b>，`/chat` 与 `/stream-sse` 共用。
         *
         * @param text         拼接后的完整回答。<b>可能为空</b>（模型只思考没作答）
         * @param reasoning    拼接后的完整思考。<b>可能为空</b>（模型未返回思考内容）。
         *                     它让 `/chat` 也能回看思考 —— 否则刷新页面后思考永久丢失
         * @param finishReason 结束原因（如 {@code STOP}）；取不到时为 null
         * @param usage        <b>整轮</b>的 token 用量（阶段 14 起）。
         *                     ★ 语义变更：此前它是<b>最后一片响应</b>的量 —— 实测一次 3 步工作流
         *                     调了 7 次模型、只记了最后一次的 3419。现在由 {@code TurnStats}
         *                     逐次累加（流式路径上 usage 只出现在每次调用的收尾片）。
         *                     一次都没拿到时是 {@code EmptyUsage} 而<b>不是 null</b> ——
         *                     与框架的约定一致，日志里一眼能看出"没数据"。
         *                     缺口计数在 {@code TurnRecord.usageMissingCalls}（不在本字段里）。
         */
        record Final(String text,
                     String reasoning,
                     String finishReason,
                     Usage usage) implements ModelOutput {
        }

        /**
         * 模型<b>请求调用某个工具</b>（阶段 9 新增）。
         *
         * <p><b>为什么要外发它</b>：这是 agent 与"纯对话"最直观的差别，也是前端
         * "让用户看到模型在做什么"的关键一步 —— 不展示它，用户只能盯着屏幕等几十秒，
         * 不知道模型是在查资料还是在卡住。
         *
         * <p><b>为什么不在 {@code ToolResult} 里带上调用信息就够了</b>：
         * 调用与结果之间隔着一次真实的工具执行（可能很慢）。
         * 先发 {@code ToolCall} 前端才能立刻显示"正在查询知识库…"，
         * 否则"开始"与"结束"之间没有任何反馈。
         *
         * <p>★ <b>字段与框架 {@code AssistantMessage.ToolCall} 一一对应</b>
         * （{@code id} / {@code name} / {@code arguments}）：
         * {@code id} 是模型给的调用标识，把请求与结果配成一对；
         * {@code type} 恒为 {@code "function"}，没有信息量，刻意不透出。
         *
         * @param id        模型给出的调用标识（用于与 {@link ToolResult} 配对）
         * @param name      工具名
         * @param arguments 参数（<b>JSON 字符串</b>，模型生成的原文）
         */
        record ToolCall(String id, String name, String arguments) implements ModelOutput {
        }

        /**
         * <b>工具执行完成</b>（阶段 9 新增）。
         *
         * <h3>★ 为什么没有 {@code error} 字段（一个刻意的"不画蛇添足"）</h3>
         *
         * <p>直觉上应该有个"成功/失败"标志。但实测：框架的
         * {@code DefaultToolExecutionExceptionProcessor} 把工具异常
         * <b>转成一段纯文本</b>塞进结果里，而 {@code ToolResponseMessage}
         * <b>没有任何结构化的"是否出错"标记</b>。
         *
         * <p>所以要填这个字段，只能靠"匹配某段魔法字符串"—— 而那正是本项目
         * 反复在防的脆弱做法（改一处提示、另一处静默失效）。
         *
         * <p>⇒ <b>先不加</b>（协议第 7 条：为没有可靠来源的字段编造取值，比没有字段更糟）。
         * 失败信息作为 {@link #result} 的文本照常展示给前端与用户。
         * 若将来真需要精确判定，正解是让<b>工具自己</b>返回结构化结果，
         * 而不是在这一层猜。
         *
         * @param id     对应的 {@link ToolCall#id()}（用于配对；取不到时为 null）
         * @param name   工具名
         * @param result 工具返回的文本（<b>模型看到的就是它</b>；失败时是框架生成的错误描述）
         */
        record ToolResult(String id, String name, String result) implements ModelOutput {
        }
    }
}
