package com.hagent.agent;

import com.hagent.retrieval.RetrievedChunk;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

/**
 * 一次 pipeline 运行<b>对外吐出的事件</b> —— 本项目与调用方之间的唯一数据契约。
 *
 * <p>消费方：{@code ChatAppService}（{@code /chat} 只取 {@link ModelOutput.Final}）
 * 与 {@code ChatController}（{@code /stream-sse} 逐事件转成 SSE 帧）。
 *
 * <p>分层按"谁产生的"分：{@link ModelOutput} 装模型的产出（思考 / 正文 / 工具请求 / 收尾），
 * {@link Retrieved}、{@link StepStarted}、{@link TurnFinished} 等是<b>我们的产物</b> ——
 * 混在一起会让"模型输出了检索结果"这种说法成立。但前端只有一条 SSE 连接，两者又都必须
 * 走这条流，所以正解是嵌套，不是拆成两个并列的顶级类型。
 *
 * <p>命名：模型产出叫 {@code ModelOutput}，<b>不叫</b> {@code AgentOutput}
 * （agent = harness + model）；模式只有 chat 与 workflow 两种，没有"agent 模式"。
 *
 * <h2>改动本接口前必读的三条实测约束</h2>
 *
 * <ol>
 *   <li><b>流式每片 text 是【增量】，不是累计</b> —— 实测 184 片中最后一片长度为 0，
 *       全片拼接才是全文。所以 {@link ModelOutput.Final#text()} <b>必须由 pipeline 自己拼接</b>，
 *       绝不能"取最后一片"。</li>
 *   <li><b>空片必须过滤</b> —— 片 0 是 {@code text=null, reasoning=空串}，不过滤会往 SSE 里发空事件。</li>
 *   <li><b>思考内容只能靠 {@code instanceof} 取</b> —— {@code reasoningContent} 是
 *       {@code DeepSeekAssistantMessage} 的独有字段，Spring AI 2.0.1 没有为它提供任何通用
 *       metadata key；换掉 DeepSeek 时思考流会静默变空，所以取值处<b>取不到时必须记 WARN</b>。</li>
 * </ol>
 */
public sealed interface PipelineEvent {

    // 一、我们的产物：检索 + 编排骨架
    /**
     * 检索完成（含权限过滤）—— <b>我们的产物，不是模型说的话</b>。
     *
     * <p><b>契约限制（前端会踩到）</b>：{@link RetrievedChunk} 的 {@code level} 与 {@code band}
     * 经 JSON 序列化后是<b>枚举 name</b>（{@code HIGH}/{@code MEDIUM}/{@code LOW}），
     * {@code RelevanceBand.displayName()} 的中文"高/中/低"<b>不是属性</b> —— 前端要显示中文需自己映射。
     *
     * @param chunks 命中的片段；<b>可能为空</b>（跑了但没命中 —— 这是正常结果，不是错误）
     * @param query  实际用于检索的查询文本（= 本次用户消息），外传以便确认"检索用的是什么问题"
     */
    record Retrieved(List<RetrievedChunk> chunks, String query) implements PipelineEvent {

        /** 紧凑构造器：列表防御性拷贝，避免下游改动影响上游。 */
        public Retrieved {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    /**
     * <b>第 N 步开始</b> —— 一个回合里"模型被第几次调用"的骨架。
     *
     * <p>没有它，前端只能看到<b>平铺</b>的 {@code ToolCall} / {@code ToolResult}：
     * 「一步里连调两个工具」与「两步各调一个」看起来完全一样；且模型思考很久还没决定调工具时，
     * 前端<b>什么事件都收不到</b>。有了它，思考中 / 调用中 / 已返回分别由
     * {@code StepStarted} / {@code ToolCall} / {@code ToolResult} 推动，
     * 前端可把工具调用<b>归到同一步</b>下。
     *
     * <p><b>★ 必须在调模型【之前】发出</b>：先发才代表"这一步已经开始"；
     * 若等第一片响应再发，"等待模型首字"这段最需要反馈的时间就没有任何事件。
     *
     * @param step 第几步，<b>从 1 开始</b>（与 {@code chat_tool_invocation.step_index} 同一口径）
     */
    record StepStarted(int step) implements PipelineEvent {
    }

    // 一·五、工作流的编排骨架
    /**
     * 计划已产出 —— "这次打算分几步做"的<b>唯一可见渠道</b>。
     *
     * <p>决策是"计划可见、但不停下来等确认"；既然不停，用户只能在<b>执行之前</b>看到计划。
     * 与 {@link StepStarted} 的关系：本事件是<b>整份计划</b>（重规划会再发一次），后者按步出现。
     */
    record PlanCreated(com.hagent.workflow.WorkflowPlan plan) implements PipelineEvent {
    }

    /**
     * 规划失败，已<b>退回普通循环</b>（降级路径）。
     *
     * <p>降级而不是让请求失败：拿不到任何回答对用户更糟。但<b>降级必须可见</b> ——
     * 悄悄退回会让用户以为"工作流跑过了"，实际只跑了一次普通问答。
     */
    record PlanSkipped(String reason) implements PipelineEvent {
    }

    /**
     * 一步结束；{@code ok=false} 时 {@code summary} 是失败原因。
     *
     * <p>失败也发事件，否则前端会把"失败后重试"看成"同一步忽然重来一次"。
     */
    record StepFinished(int step, boolean ok, String summary) implements PipelineEvent {
    }

    // 一·六、一轮的账
    /**
     * <b>本轮收尾时的账</b> —— 耗时 / 次数 / token / 结局，全链路只此一份。
     *
     * <p>为什么是顶层事件而不是 {@link ModelOutput.Final} 上的几个字段：这些数字是我们自己
     * 数出来的，不是模型说的话，塞进 {@code Final} 会让 {@code ModelOutput} 的分层当场失效。
     *
     * <p><b>它只出现在正常收尾的路径上</b>：失败与取消（客户端断开）时流已断，发不出它 ——
     * 但那两种轮次<b>照样会落库</b>（{@code AgentPipeline#run} 的 {@code doFinally}），
     * 所以"库里比前端看到的多"是<b>预期的</b>。
     *
     * @param record 本轮的完整账；前端只消费其中五项（见 {@code ChatAppService#statsData}）
     */
    record TurnFinished(com.hagent.observability.TurnRecord record) implements PipelineEvent {
    }

    // 二、模型的产物
    /**
     * 模型的产出 —— sealed，新增变体不改动已有代码。
     *
     * <p>变体对应实测的<b>严格三段式</b>（184 片）：片 0 为空（必须过滤）、
     * 片 1–89 只有 reasoning、片 90–182 只有 text、片 183 收尾。
     * 两段<b>完全互斥</b>，所以前端按<b>两条独立流</b>渲染，不需要"阶段切换"事件。
     */
    sealed interface ModelOutput extends PipelineEvent {

        /**
         * 思考过程的增量片（DeepSeek 的 {@code reasoning_content}）。
         *
         * <p>独立变体：思考与回答是前端的两块区域、语义不同；且思考是<b>可缺失</b>的能力
         * （换掉 DeepSeek 就取不到），"没有思考"表现为<b>这种事件一个都不出现</b>，
         * 而不是"回答里混进了奇怪的东西"。
         */
        record Reasoning(String text) implements ModelOutput {
        }

        /**
         * 回答正文的增量片。★ <b>是增量，不是累计</b> —— 要完整回答必须自己拼接，
         * 或直接取 {@link Final#text()}。
         */
        record Token(String text) implements ModelOutput {
        }

        /**
         * 一次模型调用的<b>收尾</b> —— 全链路唯一携带"完整结果"的事件。
         *
         * <p>必须由 pipeline 构造而不是"取最后一片响应"：最后一片 {@code text} 长度为 <b>0</b>，
         * 内容分散在 183 片里 —— 累积逻辑只能有一个出处（{@code /chat} 与 {@code /stream-sse} 共用）。
         *
         * @param text         拼接后的完整回答。<b>可能为空</b>（模型只思考没作答）
         * @param reasoning    拼接后的完整思考（<b>可能为空</b>）；让 {@code /chat} 也能回看思考，
         *                     否则刷新页面后思考永久丢失
         * @param finishReason 结束原因（如 {@code STOP}）；取不到时为 null
         * @param usage        <b>整轮</b>的 token 用量（{@code TurnStats} 逐次累加，不是最后一次
         *                     调用的量）。一次都没拿到时是 {@code EmptyUsage} 而<b>不是 null</b>；
         *                     缺口计数在 {@code TurnRecord.usageMissingCalls}
         */
        record Final(String text,
                     String reasoning,
                     String finishReason,
                     Usage usage) implements ModelOutput {
        }

        /**
         * 模型<b>请求调用某个工具</b>。
         *
         * <p>先发它前端才能显示"正在查询知识库…"：调用与结果之间隔着一次真实的工具执行，
         * 只在 {@code ToolResult} 里带调用信息的话，那段时间没有任何反馈。
         * 字段与框架 {@code AssistantMessage.ToolCall} 一一对应；{@code type} 恒为
         * {@code "function"}，没有信息量，刻意不透出。
         *
         * @param id        模型给出的调用标识（用于与 {@link ToolResult} 配对）
         * @param name      工具名
         * @param arguments 参数（<b>JSON 字符串</b>，模型生成的原文）
         */
        record ToolCall(String id, String name, String arguments) implements ModelOutput {
        }

        /**
         * <b>工具执行完成</b>。
         *
         * <p><b>★ 刻意没有 {@code error} 字段</b>：框架把工具异常<b>转成一段纯文本</b>塞进结果，
         * {@code ToolResponseMessage} 没有任何结构化的"是否出错"标记；要填它只能匹配魔法字符串，
         * 那正是本项目反复在防的脆弱做法。失败信息作为 {@link #result} 的文本照常展示；
         * 将来真要精确判定，正解是让<b>工具自己</b>返回结构化结果。
         *
         * @param id     对应的 {@link ToolCall#id()}（取不到时为 null）
         * @param name   工具名
         * @param result 工具返回的文本（<b>模型看到的就是它</b>；失败时是框架生成的错误描述）
         */
        record ToolResult(String id, String name, String result) implements ModelOutput {
        }
    }
}
