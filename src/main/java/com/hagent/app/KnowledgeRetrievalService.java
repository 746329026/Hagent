package com.hagent.app;

import com.hagent.config.RetrievalProperties;
import com.hagent.retrieval.RetrievalFilterBuilder;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库检索服务 —— 把"一个问题和一个人"变成"若干条可引用的片段"。
 *
 * <h2>★ 它产出什么、谁消费（协议第 7 条要求的答案）</h2>
 *
 * <p>产出 {@code List<RetrievedChunk>}；消费者是 {@code RagRetrievalAdvisor}，
 * 它把结果放进 {@code request.context()}，再由 {@code PromptAssemblyAdvisor}
 * 拼进 prompt。<b>本类不接触 prompt、不接触 request context</b> ——
 * 它是纯函数式的一步："检索"，不含编排。
 *
 * <h2>★ 检索失败为什么降级而不是抛出</h2>
 *
 * <p>与"记忆写回"同一条推理：<b>知识检索不在关键路径上</b>。
 * 它的失败意味着"这轮回答没有企业资料可依据"，而不是"这轮请求失败"。
 * 若向上抛，一次向量库抖动就会让整个对话 500 ——
 * 而模型完全有能力在没有片段时给出通用回答，提示词里也已经写明
 * "知识库中没有相关内容时明确说未找到"。
 *
 * <p>但<b>必须记 ERROR</b>：否则就是"静默地没有知识库"，
 * 用户无感、运维也查不到。这一点与 {@code MemoryWritebackAdvisor} 的容错处理一致。
 *
 * <h2>★ "检索失败"与"检索没命中"必须能区分</h2>
 *
 * <p>两者都返回空列表，但在日志里完全不同：
 * <ul>
 *   <li><b>失败</b> → 本类打 {@code ERROR} 带堆栈（基础设施问题）；</li>
 *   <li><b>没命中</b> → 只有 {@code DEBUG} 一行（正常结果，用户问的确实不在库里）。</li>
 * </ul>
 * 若两者都只打同一行日志，排查"为什么没检索到"时会误判方向。
 *
 * <h2>★ 单次调用，不含幂等</h2>
 *
 * <p>本类<b>没有</b>做"重复调用时跳过"的处理 —— 因为按当前切面顺序，
 * {@code RagRetrievalAdvisor} 位于 {@code ToolCallingAdvisor}（{@code MIN+300}）
 * 的<b>外侧</b>，一次请求只会进到这里一次。幂等的那道保险在 advisor 里
 * （靠 context 中的片段键判断），见该切面注释中的说明。
 * 把幂等做在服务里会掩盖"调用位置错了"这件事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final VectorStore vectorStore;
    private final RetrievalProperties properties;
    private final RetrievalFilterBuilder filterBuilder;

    /**
     * 按查询串检索，并施加调用方可见范围的过滤。
     *
     * @param query 检索用的问题文本（通常是本次用户消息）
     * @param user  调用方身份 —— 决定 {@code level IN (...)} 的取值
     * @return 命中片段（可能为空，<b>不会为 null</b>）；失败时同样返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, UserContext user) {
        if (!properties.isEnabled()) {
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
                .topK(properties.getTopK())
                // ★ 单位换算在这里闭环：配置是"距离上限"，框架要"相似度下限"。
                //   实测确认过该参数是相似度下限（0.0 = 不筛）。
                .similarityThreshold(properties.similarityThreshold())
                .filterExpression(filter)
                .build();

        try {
            List<Document> documents = vectorStore.similaritySearch(request);
            List<RetrievedChunk> chunks = documents.stream()
                    .map(this::toChunk)
                    .toList();

            log.debug("[检索] 命中 {} 条（topK={} 粗筛=距离<= {}）| 视角 userId={} role={} 可见密级={}",
                    chunks.size(), properties.getTopK(), properties.getMaxDistance(),
                    user.userId(), user.role(),
                    com.hagent.document.AccessLevel.visibleLevels(user.clearance()));
            chunks.forEach(c -> log.debug("[检索]   · 相似度={} 档位={} 出处={}",
                    String.format("%.4f", c.similarity()), c.band().displayName(), c.displaySource()));

            return chunks;
        } catch (Exception e) {
            // 见类注释：降级但不静默。ERROR 是这里唯一正确的级别 ——
            // 用户会拿到一个"没有企业资料依据"的回答，运维必须能从日志看出原因。
            log.error("[检索] 知识库检索失败，本轮不注入任何片段（对话会继续，但回答不基于企业资料）。"
                            + "【排查方向】① pgvector 容器是否可用；② Ollama embedding 是否可用；"
                            + "③ 过滤表达式是否与 pgvector 版本兼容。查询={}",
                    query, e);
            return List.of();
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
                properties.bandOf(similarity));
    }
}
