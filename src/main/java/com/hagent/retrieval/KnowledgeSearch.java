package com.hagent.retrieval;


import com.hagent.exception.RetrievalUnavailableException;
import com.hagent.retrieval.RetrievalFilterBuilder;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索服务 —— 把"一个问题和一个人"变成"若干条可引用的片段"。
 *
 * <p>只做"检索"这一步：产出 {@code List<RetrievedChunk>}，由 {@code AgentPipeline} 的第③步
 * 取用并交给 {@code PromptBuilder} 拼进 prompt —— 本类不接触 prompt、不参与编排。
 * 管道是直线（一次请求只调用一次），故本类<b>不做</b>"重复调用跳过"的幂等：
 * 做在服务里只会掩盖"调用位置错了"。
 *
 * <h2>★ "检索失败"与"检索没命中"必须可区分</h2>
 *
 * <ul>
 *   <li><b>没命中</b> → 返回<b>空列表</b>（这是结果，DEBUG 一行）；</li>
 *   <li><b>故障</b> → <b>抛 {@link RetrievalUnavailableException}</b>（ERROR 带堆栈与查询串）。</li>
 * </ul>
 *
 * <p>失败不能降级成空列表：空列表到了上层与"真的没命中"完全同形，工具会回"没找到"、
 * 模型会答"知识库中未找到相关内容" —— <b>把系统故障说成了"资料不存在"</b>。
 * 降级改放在 {@code KnowledgeRetrievalTool}：它接住异常，回"检索不可用（系统故障，不是没有
 * 相关资料）"，对话照常继续、不会 500。可迁移的判据：<b>降级要发生在"能说出降级原因"的那一层。</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearch {

    private final VectorStore vectorStore;
    private final RetrievalSettings settings;
    private final RetrievalFilterBuilder filterBuilder;

    /**
     * 按查询串检索，并施加调用方可见范围的过滤。
     *
     * @param query 检索用的问题文本（通常是本次用户消息）
     * @param user  调用方身份 —— 决定 {@code level IN (...)} 的取值
     * @return 命中片段（可能为空，<b>不会为 null</b>）；检索故障时抛异常
     */
    public List<RetrievedChunk> retrieve(String query, UserContext user) {
        if (!settings.enabled()) {
            log.debug("[检索] 已通过 hagent.rag.retrieval.enabled=false 关闭，跳过检索");
            return List.of();
        }
        if (query == null || query.isBlank()) {
            // 空查询会退化成"取任意向量"，返回一堆无意义的最近邻 ——
            // 明确跳过，而不是把噪声当结果。
            log.debug("[检索] 查询文本为空，跳过检索");
            return List.of();
        }

        // 过滤条件在发起检索前就绪：越权内容不离开数据库（见 RetrievalFilterBuilder）
        var filter = filterBuilder.build(user.clearance());

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(settings.topK())
                // ★ 单位换算在这里闭环：配置是"距离上限"，框架要"相似度下限"。
                //   实测确认过该参数是相似度下限（0.0 = 不筛）。
                .similarityThreshold(settings.similarityThreshold())
                .filterExpression(filter)
                .build();

        try {
            List<Document> documents = vectorStore.similaritySearch(request);
            List<RetrievedChunk> chunks = documents.stream()
                    .map(this::toChunk)
                    .toList();

            log.debug("[检索] 命中 {} 条（topK={} 粗筛=距离<= {}）| 视角 userId={} role={} 可见密级={}",
                    chunks.size(), settings.topK(), settings.maxDistance(),
                    user.userId(), user.role(),
                    com.hagent.document.AccessLevel.visibleLevels(user.clearance()));
            chunks.forEach(c -> log.debug("[检索]   · 相似度={} 档位={} 出处={}",
                    String.format("%.4f", c.similarity()), c.band().displayName(), c.displaySource()));

            return chunks;
        } catch (Exception e) {
            // ★ 先记 ERROR（带堆栈、带查询串）：异常马上会被工具接住，
            //   之后就没有人会再打印它的堆栈了 —— 这里是唯一的诊断入口。
            log.error("[检索] 知识库检索失败【不是「没命中」】—— 将向上抛 RetrievalUnavailable，"
                            + "由工具翻译成「检索不可用」告知模型（避免它把故障说成「资料不存在」）。"
                            + "【排查方向】① pgvector 容器是否可用；② Ollama embedding 是否可用；"
                            + "③ 调用是否发生在 Reactor 非阻塞线程上（会让阻塞调用直接抛错）；"
                            + "④ 过滤表达式是否与 pgvector 版本兼容。查询={}",
                    query, e);
            throw new RetrievalUnavailableException("知识库检索失败（查询=" + query + "）", e);
        }
    }

    /** 把框架的 {@link Document} 转成本项目的领域记录；字段取值规则全在 {@link RetrievedChunk#from}。 */
    private RetrievedChunk toChunk(Document document) {
        Double score = document.getScore();
        // score 为空时给 0.0（= 距离 1.0，最远）而不是给一个"看起来不错"的值：
        // 拿不到分数就意味着无法判断相关性，此时应当落到【低】档，让提示词约束去处理。
        double similarity = score == null ? 0.0 : score;
        return RetrievedChunk.from(
                document.getMetadata(),
                document.getText(),
                similarity,
                settings.bandOf(similarity));
    }
}
