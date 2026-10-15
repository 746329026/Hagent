package com.hagent.app;

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
 *   └── ModelOutput                    ← 【模型的】产出（sealed，可继续嵌套）
 *       ├── Reasoning(text)            ← 思考增量（DeepSeek 的 reasoning_content）
 *       ├── Token(text)                ← 回答增量
 *       └── Final(text, reasoning, finishReason, usage)
 *
 *   阶段 9 新增：ModelOutput.ToolCall / ModelOutput.ToolResult
 * </pre>
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
    // 一、我们的产物：检索
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
         * @param usage        token 用量。<b>流式路径可能取不到</b>（实测未观察到），
         *                     取不到时为 {@code EmptyUsage} 而非 null —— 由框架保证。
         *                     ★ 这条限制直接影响阶段 9 的可观测性，见 HANDOFF
         */
        record Final(String text,
                     String reasoning,
                     String finishReason,
                     Usage usage) implements ModelOutput {
        }
    }
}
