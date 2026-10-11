package com.hagent.advisor;

import com.hagent.app.KnowledgeRetrievalService;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 知识库检索切面 —— 检索并施加权限过滤，把片段放进 {@code context}，<b>不改 prompt</b>。
 *
 * <h2>★ 它解决什么问题、产出谁消费</h2>
 *
 * <p>产出 {@code List<RetrievedChunk>} 写入 {@link PromptFragmentKeys#RAG_FRAGMENTS}；
 * 消费者是 {@code PromptAssemblyAdvisor}。不做它会怎样：
 * 模型只能凭训练时的一般知识回答"年假几天"这类问题 ——
 * 答案可能与本公司的制度完全不符，<b>而且读起来同样自信</b>。
 *
 * <h2>★ 为什么只产片段、不直接拼进 prompt</h2>
 *
 * <p>因为拼装必须只有一个出处。若本切面自己往 prompt 里塞片段，
 * 而 {@code PromptAssemblyAdvisor} 又要重建消息列表（插入 system 与历史），
 * 就会重现 {@code AdvisorChainMessageSemanticsTest} 实验 2 的行为：
 * <b>后重建的一方把先注入的片段静默丢弃</b>。
 *
 * <p>所以本切面刻意<b>不动 prompt 一个字节</b> —— 它对链的贡献全部通过 context 表达。
 * 这也是它能安心待在 {@code MIN+220}（拼装的内层之外）的原因。
 *
 * <h2>★ 幂等：从"必须"降级为"防御"（本轮的一个明确取舍）</h2>
 *
 * <p>检索一次 = 一次 embedding 调用 + 一次向量查询。若切面位于
 * {@code ToolCallingAdvisor}（{@code MIN+300}）的<b>内层</b>，一次用户提问会触发
 * <b>N 次</b>检索（N = 工具调用轮数），而用户的问题根本没变 ——
 * 成本被乘 N 倍，且没有任何报错。这就是 {@code WORKING-AGREEMENT} 第 10 条
 * 要求"RAG 检索必须幂等"的由来。
 *
 * <p>本项目的选择是<b>改变结构</b>而不是加检查：把本切面放在 {@code MIN+220}，
 * 即<b>工具循环之外</b>，于是"重复调用"在结构上不可能发生
 * （一次请求只进这里一次）。所以那道"context 里已有片段就跳过"的检查
 * <b>不再是唯一防线，而是一道保险</b>：
 *
 * <pre>
 *   以前：【必须】靠这行检查防止 N 倍成本 —— 删掉它、key 写错，成本立刻翻 N 倍且不报错
 *   现在：结构上不会重复；留着它是防"将来有人把 order 改回循环内层"
 * </pre>
 *
 * <p>第 10 条原文是"<b>靠结构</b>（缓存命中/唯一键/单一拼装者），<b>不靠记得先检查</b>"。
 * 等级 2（结果保证）与等级 1（调用次数保证）都是"靠结构"，但强度不同 ——
 * 这里拿到的是更强的那个。所以下面的检查保留，但注释如实标注它是防御。
 *
 * <h2>★ 身份缺失时为什么落到匿名而不是报错</h2>
 *
 * <p>身份由 {@code ChatAppService} 经 context 显式传入（{@code UserContext.CONTEXT_KEY}）。
 * 若某个调用路径忘了传，本切面的处理是：<b>按匿名（只看 PUBLIC）+ 记 WARN</b>。
 *
 * <ul>
 *   <li>选匿名而不是 {@code null}：与决策 21 一致 —— 失败方向必须偏保守，
 *       "该看到的人看不到"好过"不该看到的人看到"；</li>
 *   <li>必须记 WARN 而不是静默：否则调用方会以为"我传了 ADMIN 身份"，
 *       而实际只看到 PUBLIC，表现为"检索结果莫名其妙地少"。</li>
 * </ul>
 *
 * <p>这与 {@code MemoryReadAdvisor} 对 {@code MemoryMode} 的处理方式完全同构：
 * <b>取值缺失 → 落到更保守的默认 + 明确告警</b>，而不是静默忽略。
 */
@Slf4j
@RequiredArgsConstructor
public class RagRetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "RagRetrievalAdvisor";

    private final KnowledgeRetrievalService retrievalService;

    // ========================================================================
    // 非流式与流式：行为相同（都是 before 侧，与"何时拿到回复"无关）
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(withFragments(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(withFragments(request));
    }

    // ========================================================================
    // 核心：检索 → 写 context
    // ========================================================================

    private ChatClientRequest withFragments(ChatClientRequest request) {
        // ★ 防御性幂等（见类注释）：结构上不会走到这里，但留着它成本近似为零，
        //   且能防住"将来有人把本切面的 order 改回工具循环内层"。
        if (request.context().containsKey(PromptFragmentKeys.RAG_FRAGMENTS)) {
            log.debug("[检索] context 中已有片段，跳过重复检索（防御性幂等）");
            return request;
        }

        UserContext user = userOf(request);
        String query = queryOf(request);

        List<RetrievedChunk> chunks = retrievalService.retrieve(query, user);

        // ★ 无论有没有命中都写这个 key —— 见 PromptFragmentKeys.RAG_FRAGMENTS 的注释：
        //   "key 不存在"表示检索没跑，"key 存在但为空"表示跑了没命中。
        //   这两种状态在排障时含义完全不同，必须在 context 里可区分。
        return request.mutate()
                .context(PromptFragmentKeys.RAG_FRAGMENTS, chunks)
                .build();
    }

    /**
     * 取检索用的查询文本 —— 用<b>本次用户消息</b>，不用注入后的 prompt。
     *
     * <p>本切面在 {@code MIN+220}，位于拼装（{@code MIN+250}）之外，
     * 所以它收到的 prompt 仍是原始的"system（若有）+ 用户消息"。
     * 若用注入后的内容做 query，检索会把"自己上一轮注入的片段"也算进查询语义里，
     * 形成自我强化的偏置 —— 越检索越偏。
     *
     * <p>⚠️ <b>当前限制（诚实声明）</b>：query 只有本次消息，<b>不含历史</b>。
     * 多轮对话里"那它的报销标准呢？"这类带指代的提问会因为无从消解而检索跑偏。
     * 解决办法是让本切面读 {@code MemoryReadAdvisor} 放在 context 里的历史、
     * 拼成"历史要点 + 本次问题"再检索 —— 顺序上已经预留好了
     * （读取在 {@code MIN+200}，检索在 {@code MIN+220}），
     * 但"怎么拼历史才不引入噪声"需要真实需求来驱动，本阶段不做。
     */
    private String queryOf(ChatClientRequest request) {
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage == null ? null : userMessage.getText();
    }

    /** 从 context 取身份；缺失或类型不符时落到匿名并告警（见类注释）。 */
    private UserContext userOf(ChatClientRequest request) {
        Object value = request.context().get(UserContext.CONTEXT_KEY);
        if (value instanceof UserContext user) {
            return user;
        }
        if (value != null) {
            log.warn("[检索] context 里的 {} 类型不是 UserContext 而是 {}，已按【匿名】处理（仅可见 PUBLIC）。"
                            + "正确用法：a.param(UserContext.CONTEXT_KEY, userContext)",
                    UserContext.CONTEXT_KEY, value.getClass().getName());
        } else {
            log.warn("[检索] context 里没有 {} —— 已按【匿名】处理（仅可见 PUBLIC）。"
                            + "若这是预期之外的，请检查调用方是否漏传身份："
                            + "a.param(UserContext.CONTEXT_KEY, userContext)",
                    UserContext.CONTEXT_KEY);
        }
        return UserContext.ANONYMOUS;
    }

    // ========================================================================
    // Advisor 元信息
    // ========================================================================

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.RAG_RETRIEVAL;
    }
}
