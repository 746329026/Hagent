package com.hagent.tool;

import com.hagent.app.KnowledgeRetrievalService;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * <b>知识库检索工具</b> —— 把原本"每轮无条件执行"的 RAG 检索，改成<b>模型自己决定要不要查</b>。
 *
 * <h2>★★ 它解决什么问题（协议第 7 条三问）</h2>
 *
 * <table border="1">
 *   <caption>三问的答案</caption>
 *   <tr><th>问</th><th>答</th></tr>
 *   <tr><td>① 解决什么具体问题</td>
 *       <td>阶段 8 之前，检索是<b>无条件</b>的：不管用户问"今天几号"还是"年假几天"，
 *           都会先跑一次 embedding + 向量查询，再把片段塞进 system。
 *           <b>模型没有拒绝检索的机会，我们也没有</b>。<br>
 *           做成工具之后，<b>要不要查、查什么</b>由模型决定 —— 这才是 agent 与"固定 RAG 流程"的实质差别。</td></tr>
 *   <tr><td>② 产出谁消费</td>
 *       <td>① <b>模型</b>：工具返回的字符串会作为 tool 消息回到模型手里，它据此作答；<br>
 *           ② <b>前端</b>：伴随返回发出的 {@code PipelineEvent.Retrieved}（阶段 9.2 接线），
 *              让用户看到"本次参考了哪几篇"。</td></tr>
 *   <tr><td>③ 不做会怎样</td>
 *       <td>维持"每轮必检索"：闲聊、算数、问时间都会白烧一次 embedding 与一次向量查询，
 *           而且<b>无关片段会被塞进 system</b>，反而干扰模型（HANDOFF 3.5 已实测：
 *           阈值挡不住无关问题，无关提问的距离与相关提问完全重叠）。</td></tr>
 * </table>
 *
 * <h2>★ 为什么它同时是"可插拔的插件"</h2>
 *
 * <p>{@link com.hagent.config.RetrievalProperties#isEnabled()} 语义已调整为
 * "<b>关掉检索工具</b>"：
 * <ul>
 *   <li>开启 → 模型能看到 {@code retrieveKnowledge}，可以按需检索；</li>
 *   <li>关闭 → 模型<b>看不到这个工具</b>（工具不注册），也就无从检索。</li>
 * </ul>
 * 于是"检索"整体成了<b>一个可插拔的能力</b>：插上是一行配置，拔掉也是一行配置，
 * 而管线代码一行都不用改。
 *
 * <h2>★★ 为什么返回值是"已渲染好的文本"，而不是结构化对象</h2>
 *
 * <p><b>模型只能看到工具返回的字符串</b>。所以"来源 / 相关度"这两项元数据
 * <b>必须由本工具拼进文本</b>，模型才可能"说明出处"（决策 22 的甲方案）。
 *
 * <p>渲染格式与阶段 8 的 {@code PromptBuilder#renderFragments} 完全一致
 * （那段逻辑随检索一起从 prompt 拼装<b>搬到工具里</b>）：
 * <pre>
 *   【知识库片段 1／来源：员工手册 第二章 假期管理／相关度：高】
 *   员工每年享有五天带薪年假……
 * </pre>
 *
 * <p>★ <b>一处真实的耦合</b>：{@code prompts/rag-answer-prompt.md} 里写的是
 * "优先依据【知识库片段】回答"。改这里的字样必须同步改提示词，
 * 否则约束会指向一个不存在的标记，而<b>不会有任何报错</b>。
 *
 * <h2>★★ 权限过滤为什么仍然有效（这条最容易搞错）</h2>
 *
 * <p>检索从"管线第③步"搬到"工具内部"之后，<b>越权风险一点没变</b>：
 * 过滤仍然是 {@code KnowledgeRetrievalService} 内部的 SQL 条件
 * （{@code level IN (...)}），而<b>调用方身份必须传进工具</b>。
 *
 * <p>通道是框架的 {@link ToolContext}：{@code AgentPipeline} 把它放进
 * {@code ToolCallingChatOptions.toolContext(...)}，框架在执行工具时构造
 * {@code ToolContext} 并注入本方法参数（已核实 {@code MethodToolCallback}
 * 支持 {@code ToolContext} 类型的参数，且 {@code DefaultToolCallingManager}
 * 会从 options 里取 {@code getToolContext()}）。
 *
 * <p><b>★ 身份缺失时落到匿名（只看 PUBLIC）并记 WARN</b> ——
 * 与阶段 6 的 {@code RagRetrievalAdvisor} 同一条判据：
 * 失败方向必须偏保守（"该看到的看不到"好过"不该看到的看到"），
 * 但<b>必须告警</b>，否则表现为"检索结果莫名其妙地少"。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    /** 放进 {@code ToolContext} 的键 —— 身份。 */
    public static final String TOOL_CONTEXT_USER = "hagentToolUser";

    /**
     * 放进 {@code ToolContext} 的键 —— 事件出口（{@code ToolEventSink}）。
     *
     * <p>用途：把"本次参考了哪几篇"作为<b>结构化事件</b>发给前端。
     * 见 {@link com.hagent.app.ToolEventSink} 的类注释（为什么不塞进返回字符串）。
     */
    public static final String TOOL_CONTEXT_SINK = "hagentToolEventSink";

    /**
     * 片段块的起始标记 —— 提示词里的约束引用它。
     *
     * <p>与阶段 8 的 {@code PromptBuilder.FRAGMENT_HEADER} 同值：
     * 这次搬迁<b>没有改字样</b>，所以 {@code rag-answer-prompt.md} 里的约束仍然成立
     * （阶段 9.1 只需把"检索到的片段"的表述改成"工具返回的结果"，见该文件）。
     */
    private static final String FRAGMENT_HEADER = "【知识库片段】";

    private final KnowledgeRetrievalService retrievalService;

    /**
     * 按查询串检索知识库，返回<b>带来源与相关度的片段文本</b>。
     *
     * <p><b>★ 描述（{@code description}）是写给模型看的</b>：
     * 它决定模型"什么时候该调用我"。所以写成
     * "当问题涉及公司制度、流程、规定等内部知识时调用"，
     * 而不是"检索向量库"（后者是给运维看的，模型理解不了意图）。
     *
     * @param query 检索用的自然语言问题。<b>由模型生成</b> ——
     *              它可以把用户的模糊提问改写成更利于检索的形式
     *              （这是工具化后的额外收益：阶段 8 只能用原始提问去检索）
     * @param toolContext 框架注入的工具上下文（携带调用方身份）
     * @return 渲染好的片段文本；没命中或失败时返回一句明确的说明
     */
    @Tool(name = "retrieveKnowledge",
            description = """
                    在企业知识库中检索与问题相关的内部资料（公司制度、流程、规定、员工手册等）。
                    当用户的问题可能与企业内部信息有关时调用；纯闲聊、算术、通用常识问题不要调用。
                    查询串应当是具体的自然语言问题，可以比用户的原始提问更明确。""")
    public String retrieveKnowledge(
            @ToolParam(description = "检索用的自然语言问题，例如：年假要提前几天申请")
            String query,
            ToolContext toolContext) {

        UserContext user = resolveUser(toolContext);
        List<RetrievedChunk> chunks = retrievalService.retrieve(query, user);

        // ★ 把"参考了哪几篇"作为【结构化事件】发给前端。
        //   为什么放在这里而不是让管线从返回字符串里解析：
        //   返回字符串是【给模型看的】，结构化出处是【给前端看的】——
        //   两件事走两条通道（协议第 7 条）。
        emitRetrieved(toolContext, chunks, query);

        // ★ 检索"没命中"与"失败"在 Service 内部已被区分（前者 DEBUG、后者 ERROR 带堆栈）。
        //   这里把"没命中"渲染成一句给模型看的明确说明 ——
        //   让模型知道"我查过了、但没有"，而不是"工具坏了"，从而按提示词约束
        //   回答"知识库中未找到相关内容"而不是凭常识编造。
        if (chunks.isEmpty()) {
            log.debug("[工具-检索] 未命中：query={} 视角={}（已按权限过滤）", query, user.userId());
            return "知识库中没有找到与该问题相关的内容。";
        }

        log.debug("[工具-检索] 命中 {} 条：query={} 视角={} role={}",
                chunks.size(), query, user.userId(), user.role());

        return render(chunks);
    }

    /**
     * 发出 {@code Retrieved} 事件。
     *
     * <p><b>★ 取不到事件出口时是正常状态，不告警</b>：
     * 工具可以被独立调用（例如单元测试里直接调、或将来别的入口复用），
     * 那时没有管线在监听。而"没有前端在看"不等于出错。
     *
     * <p>与身份不同：身份缺失必须告警（那是<b>权限</b>问题，静默会变成"结果莫名其妙地少"）；
     * 事件出口缺失只影响"能不能展示出处"，不影响正确性。
     */
    private void emitRetrieved(ToolContext toolContext, List<RetrievedChunk> chunks, String query) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object sink = toolContext.getContext().get(TOOL_CONTEXT_SINK);
        if (sink instanceof com.hagent.app.ToolEventSink eventSink) {
            try {
                eventSink.emit(new com.hagent.app.PipelineEvent.Retrieved(chunks, query));
            } catch (Exception e) {
                // 见 ToolEventSink 的注释：发事件失败不该把一次成功的检索变成"工具失败"。
                log.warn("[工具-检索] 发出 Retrieved 事件失败（不影响检索结果本身）：{}", e.toString());
            }
        }
    }

    /**
     * 把片段渲染成给模型看的文本块。
     *
     * <p><b>从哪里搬来的</b>：阶段 8 这段逻辑在 {@code PromptBuilder#renderFragments}
     * （由拼装者把片段拼进 system）。检索工具化之后，片段<b>不再进 system</b>，
     * 而是作为工具结果回到模型手里 —— 于是渲染的职责跟着数据走，落到本类。
     *
     * <p>格式一字未改（含 {@link #FRAGMENT_HEADER}），因为提示词约束引用着它。
     */
    private String render(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRAGMENT_HEADER).append('\n');
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("【知识库片段 ").append(i + 1)
                    .append("／来源：").append(chunk.displaySource())
                    .append("／相关度：").append(chunk.band().displayName())
                    .append("】\n");
            sb.append(chunk.content() == null ? "" : chunk.content().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 从 {@link ToolContext} 取出调用方身份。
     *
     * <p>三种情况各有明确处理（与阶段 6 的切面取值逻辑同构）：
     * <ul>
     *   <li><b>没有 ToolContext 或没有这个键</b> → 匿名 + <b>WARN</b>。
     *       这是"调用方漏传"，必须可见，否则表现为"检索结果莫名其妙地少"；</li>
     *   <li><b>类型不对</b> → 匿名 + WARN，并逐字报出实际类型。
     *       静默当匿名会让"我明明传了 ADMIN"变成谜；</li>
     *   <li><b>正常</b> → 用它。</li>
     * </ul>
     */
    private UserContext resolveUser(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            log.warn("[工具-检索] 没有 ToolContext —— 已按【匿名】处理（仅可见 PUBLIC）。"
                    + "正确用法：ToolCallingChatOptions.toolContext({}, userContext)",
                    TOOL_CONTEXT_USER);
            return UserContext.ANONYMOUS;
        }
        Object value = toolContext.getContext().get(TOOL_CONTEXT_USER);
        if (value instanceof UserContext user) {
            return user;
        }
        if (value == null) {
            log.warn("[工具-检索] ToolContext 里没有 {} —— 已按【匿名】处理（仅可见 PUBLIC）。"
                            + "正确用法：ToolCallingChatOptions.toolContext({}, userContext)",
                    TOOL_CONTEXT_USER, TOOL_CONTEXT_USER);
        } else {
            log.warn("[工具-检索] ToolContext 里的 {} 类型不是 UserContext 而是 {} —— 已按【匿名】处理",
                    TOOL_CONTEXT_USER, value.getClass().getName());
        }
        return UserContext.ANONYMOUS;
    }
}
