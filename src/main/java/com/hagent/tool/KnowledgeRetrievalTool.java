package com.hagent.tool;

import com.hagent.agent.PipelineEvent;
import com.hagent.agent.ToolEventSink;

import com.hagent.prompt.PromptFragments;

import com.hagent.retrieval.KnowledgeSearch;
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
 * <p>不这么做的后果：闲聊、算数、问时间都会白烧一次 embedding 与向量查询，
 * 且<b>无关片段会被塞进 system</b> 反而干扰模型（阈值挡不住无关提问，见 {@code RelevanceBand}）。
 * 工具化后"要不要查、查什么"由模型决定 —— 这才是 agent 与"固定 RAG 流程"的实质差别。
 *
 * <p><b>插拔开关</b>：{@code hagent.rag.retrieval.enabled} 的语义是"关掉检索工具" ——
 * 关闭时工具不注册，模型看不到 {@code retrieveKnowledge}，管线代码一行不用改。
 *
 * <h2>★ 返回值是"已渲染好的文本"</h2>
 *
 * <p>模型只能看到工具返回的字符串，所以"来源 / 相关度"必须由本工具拼进文本
 * （{@code 【知识库片段 1／来源：员工手册 第二章 假期管理／相关度：高】}），它才可能说明出处。
 * ★ <b>一处真实耦合</b>：{@code prompts/rag-answer-prompt.md} 里写着"优先依据【知识库片段】回答"，
 * 改这里的字样必须同步改提示词，否则约束会指向一个不存在的标记，而<b>不会有任何报错</b>。
 *
 * <h2>★ 权限过滤为什么仍然有效</h2>
 *
 * <p>过滤仍是 {@code KnowledgeSearch} 里的 SQL 条件（{@code level IN (...)}），
 * 前提是<b>调用方身份必须传进工具</b> —— 通道是框架的 {@link ToolContext}。
 * <b>身份缺失时落到匿名（只看 PUBLIC）并记 WARN</b>：失败方向必须偏保守，
 * 但不告警就会表现为"检索结果莫名其妙地少"。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    // 身份键与事件键都在 ToolContextSupport：长期记忆工具也读同一个身份键，
    // 身份解析的三条分支也收在那里，避免两个工具对"缺身份"给出不同的处理方向。


    /**
     * 检索<b>不可用</b>时返回给模型的说明。
     *
     * <p><b>它必须与"没命中"的说明长得完全不一样</b>，因为两者对应的事实相反：
     * 没命中 = 查过了、确实没有；不可用 = 没查成、不能说有没有。措辞刻意做三件事：
     * 开头就写【检索不可用】，让模型一眼看出这不是结果；明确写出"<b>不是</b>没有相关资料"，
     * 堵住模型最可能的错误推断；给出它会照做的一句话（"请如实告知用户…稍后重试"），
     * 而不是只让它别做什么。
     */
    static final String RETRIEVAL_UNAVAILABLE_TEXT = """
            【检索不可用】知识库检索这次失败了（系统故障，不是「没有相关资料」）。
            请如实告知用户「知识库检索暂时不可用，请稍后重试」；不要据此断言知识库中没有相关内容，也不要凭常识代答。""";

    private final KnowledgeSearch retrievalService;

    /**
     * 按查询串检索知识库，返回<b>带来源与相关度的片段文本</b>。
     *
     * <p><b>描述（{@code description}）是写给模型看的</b>：它决定模型"什么时候该调用我"。
     * 所以写成"当问题涉及公司制度、流程、规定等内部知识时调用"，而不是"检索向量库"
     * （后者是给运维看的，模型理解不了意图）。
     *
     * @param query 检索用的自然语言问题。<b>由模型生成</b> —— 它可以把用户的模糊提问改写成
     *              更利于检索的形式，这是工具化后的额外收益
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

        List<RetrievedChunk> chunks;
        try {
            chunks = retrievalService.retrieve(query, user);
        } catch (com.hagent.exception.RetrievalUnavailableException e) {
            // ★ 失败【不】伪装成"没命中"：Service 抛异常，这里把它翻译成一句【给模型看的】
            //   明确说明，让它去说"稍后重试"，而不是断言"资料不存在"（旧做法把故障渲染成
            //   "没有找到"，实测时用户问的年假就在库里）。
            //   注意：本方法【不抛出去】—— 工具抛异常会被框架翻成一段文本结果，
            //   等于又把"故障"降级成一段没人读的字符串，还丢了这句针对性的话。
            log.warn("[工具-检索] ★ 检索不可用（这是【故障】，不是没命中）：query={} 视角={} 原因={}",
                    query, user.userId(), e.getMessage());
            return RETRIEVAL_UNAVAILABLE_TEXT;
        }

        // ★ 把"参考了哪几篇"作为【结构化事件】发给前端：返回字符串是【给模型看的】，
        //   结构化出处是【给前端看的】，两件事走两条通道。
        //
        //   ⚠️ 失败时【不发】这个事件 —— 发了就等于告诉前端"检索成功、命中 0 条"，
        //   而那正是本次要消灭的"失败与没命中同形"。
        emitRetrieved(toolContext, chunks, query);

        // ★ 真正"没命中"的出口：查了、通了、但没有相关内容。
        //   这是一条【正常结果】，所以给模型的是一句陈述，不是告警。
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
     * <p><b>取不到事件出口时是正常状态，不告警</b>：工具可以被独立调用（单测里直接调、
     * 或将来别的入口复用），那时没有管线在监听，而"没有前端在看"不等于出错。
     *
     * <p>与身份不同：身份缺失必须告警（那是<b>权限</b>问题，静默会变成"结果莫名其妙地少"）；
     * 事件出口缺失只影响"能不能展示出处"，不影响正确性。
     */
    private void emitRetrieved(ToolContext toolContext, List<RetrievedChunk> chunks, String query) {
        if (toolContext == null || toolContext.getContext() == null) {
            return;
        }
        Object sink = toolContext.getContext().get(ToolContextSupport.EVENT_SINK);
        if (sink instanceof com.hagent.agent.ToolEventSink eventSink) {
            try {
                eventSink.emit(new com.hagent.agent.PipelineEvent.Retrieved(chunks, query));
            } catch (Exception e) {
                // 见 ToolEventSink 的注释：发事件失败不该把一次成功的检索变成"工具失败"。
                log.warn("[工具-检索] 发出 Retrieved 事件失败（不影响检索结果本身）：{}", e.toString());
            }
        }
    }

    /**
     * 把片段渲染成给模型看的文本块。
     *
     * <p>渲染职责落在本类而不是拼装者：片段<b>不再进 system</b>，而是作为工具结果回到模型手里。
     * 格式（含 {@link PromptFragments#KNOWLEDGE_FRAGMENT_HEADER}）一字未改，因为提示词约束引用着它。
     */
    private String render(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptFragments.KNOWLEDGE_FRAGMENT_HEADER).append('\n');
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append("【知识库片段 ").append(i + 1)
                    .append("／来源：").append(chunk.displaySource())
                    .append("／").append(PromptFragments.RELEVANCE_FIELD_LABEL).append("：").append(chunk.band().displayName())
                    .append("】\n");
            sb.append(chunk.content() == null ? "" : chunk.content().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 从 {@link ToolContext} 取出调用方身份。
     *
     * <p>委托给 {@link ToolContextSupport#resolveUser}：长期记忆的工具也要做同一件事，
     * 两处各写一份必然在"缺身份怎么办"上漂移。
     */
    private UserContext resolveUser(ToolContext toolContext) {
        return ToolContextSupport.resolveUser(toolContext, "工具-检索");
    }
}
