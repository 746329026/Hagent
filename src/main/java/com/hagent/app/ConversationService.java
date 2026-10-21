package com.hagent.app;

import com.hagent.common.ErrorCode;
import com.hagent.dto.SessionDtos.ReasoningSection;
import com.hagent.dto.SessionDtos.SessionDetail;
import com.hagent.dto.SessionDtos.SessionMessage;
import com.hagent.dto.SessionDtos.SessionSummary;
import com.hagent.dto.SessionDtos.TurnStatsView;
import com.hagent.exception.AgentException;
import com.hagent.memory.MessageCodec;
import com.hagent.memory.ReasoningTrace;
import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.record.MessageStore;
import com.hagent.memory.catalog.SessionStore;
import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 会话列表 / 历史 / 改名 / 删除 —— <b>不涉及模型调用</b>。
 *
 * <h2>★ 为什么它存在（而不是 Controller 直接调存储）</h2>
 *
 * <p>三件事必须有人做，而它们都不属于 HTTP 层，也不属于存储层：
 * <ol>
 *   <li><b>把两个存储拼起来</b>：标题/模式来自 {@link SessionStore}，
 *       消息来自 {@link MessageStore}。"一个会话的详情"横跨这两者，
 *       拼接点是本层；</li>
 *   <li><b>把存储类型翻译成 API 类型</b>：{@code SessionStore.Session} 与
 *       {@code ReasoningTrace.Section} 是<b>存储形态</b>（允许演化），
 *       {@code SessionDtos.*} 是<b>契约</b>（改名即破坏前端）。两者不共用类型；</li>
 *   <li><b>"不存在"要变成异常</b>：存储层返回 {@code Optional.empty()} / {@code false}，
 *       而 HTTP 层要的是 404 + {@code SESSION_NOT_FOUND}。这层负责翻。</li>
 * </ol>
 *
 * <h2>★ 为什么历史回放要把文本解析回结构</h2>
 *
 * <p>库里 {@code chat_message.reasoning} 是<b>带 {@code @@step:} 标记的文本</b>
 * （一个 TEXT 列放不下结构）。若把它原样发给前端，前端就得写一个<b>标记解析器</b>，
 * 而流式那条路收到的是 {@code step} 事件（本来就结构化）—— 于是前端有两套形状要照顾。
 *
 * <p>所以在<b>后端出口</b>把它拆回结构（{@link ReasoningTrace#parse}）：
 * <pre>
 *   流式：step 事件 + reasoning 帧   → 前端攒成 List&lt;段&gt;
 *   回放：GET /messages 直接给 List&lt;段&gt;
 * </pre>
 * 前端只有一种形状。★ 标记语法因此成为<b>纯存储细节</b>，只活在后端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /** 侧栏默认返回多少个会话（调用方不指定时）。 */
    private static final int DEFAULT_LIMIT = 50;

    private final SessionStore conversationCatalog;

    /** 直接依赖记录层而不是门面 {@code ConversationMemory}：门面只提供"按预算裁剪"的读。 */
    private final MessageStore recordStore;

    /**
     * 上下文层缓存（阶段 14 的删除链路才用到）—— <b>删除时必须一并清掉</b>。
     *
     * <p><b>为什么不能只删记录层</b>：模型上下文存在 Redis 里、带 24 小时 TTL，
     * 且读路径<b>命中就直接用</b>（不会和记录层比对）。所以"删了库、留了缓存"的症状是：
     * 用户删掉会话后继续在同一个页面说话，<b>模型仍然记得那些被删的内容</b>
     * —— 而用户以为自己删掉了。这是"删除"这个词最不该出现的表现。
     */
    private final ConversationContextCache contextCache;

    /**
     * 回合账（阶段 14 后补）—— <b>历史回放显示那一行统计的唯一数据来源</b>。
     *
     * <p>实时那一行来自 SSE 的 {@code stats} 事件；刷新页面后，消息只知道自己是哪一轮
     * （{@code chat_message.turn_id}），数字要按这个 id 回来查。
     *
     * <p>★ 为什么不在写回时把数字<b>抄一份</b>进消息：那样同一个数会有两处来源，
     * 迟早出现"库里那行说 3032、历史里说 3032 但其实是上一轮的"这种漂移，
     * 而它不会报错（协议第 13 条）。
     */
    private final TurnStore turnStore;

    /**
     * 列出会话（最近的在前）。
     *
     * @param limit 最多返回几条；{@code <= 0} 表示用默认值 {@value #DEFAULT_LIMIT}
     */
    public List<SessionSummary> listSessions(int limit) {
        int effective = limit <= 0 ? DEFAULT_LIMIT : limit;
        List<SessionSummary> sessions = conversationCatalog.list(effective).stream()
                .map(this::toSummary)
                .toList();
        log.debug("[会话查询] 列出 {} 个会话（请求上限 {}）", sessions.size(), effective);
        return sessions;
    }

    /**
     * 一个会话的完整详情：元信息 + <b>全部</b>消息（不按预算裁剪 —— 它是给用户看的记录）。
     *
     * @throws AgentException {@link ErrorCode#SESSION_NOT_FOUND} 会话不存在
     */
    public SessionDetail detail(String conversationId) {
        SessionStore.Session session = conversationCatalog.find(conversationId)
                .orElseThrow(() -> new AgentException(ErrorCode.SESSION_NOT_FOUND, conversationId));
        List<Message> messages = recordStore.readAll(conversationId);
        // ★ 阶段 14 后补：一次性把整段对话涉及的回合账取回来（不是逐条查 —— N+1 会拖慢长会话）
        Map<String, TurnRecord> turns = turnsOf(messages);
        return new SessionDetail(toSummary(session),
                messages.stream().map(message -> toMessage(message, turns)).toList());
    }

    /**
     * 取出这段历史用到的回合账。
     *
     * <p><b>失败只降级成"没有数字"</b>：历史回放的正文与思考都在、只是少一行统计。
     * 为一个可选的旁证让整个"打开会话"失败，属于本末倒置
     * （与检索失败降级、长期记忆索引降级同一条立场）。
     * 但<b>必须 WARN</b>：否则"数字忽然没了"会被当成前端 bug 排查很久。
     */
    private Map<String, TurnRecord> turnsOf(List<Message> messages) {
        List<String> turnIds = messages.stream()
                .map(MessageCodec::turnIdOf)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (turnIds.isEmpty()) {
            // 阶段 14 之前写入的历史：没有轮次可查 —— 这不是错误，不告警
            return Map.of();
        }
        try {
            return turnStore.find(turnIds);
        } catch (Exception e) {
            log.warn("[会话查询] 回合账读取失败（历史照常返回，只是没有那一行统计）：会话={} 原因={}",
                    messages.isEmpty() ? "?" : MessageCodec.turnIdOf(messages.getLast()), e.toString());
            return Map.of();
        }
    }

    /**
     * 改名。
     *
     * @return 更新后的摘要（前端直接替换侧栏那一行，不必再拉一次列表）
     * @throws AgentException {@link ErrorCode#SESSION_NOT_FOUND} 会话不存在
     */
    public SessionSummary rename(String conversationId, String title) {
        if (!conversationCatalog.rename(conversationId, title)) {
            throw new AgentException(ErrorCode.SESSION_NOT_FOUND, conversationId);
        }
        log.info("[会话查询] 会话 {} 已改名", conversationId);
        return conversationCatalog.find(conversationId)
                .map(this::toSummary)
                // 改名成功但紧接着读不到：只可能是并发的删除。返回一个最小摘要，
                // 不抛异常 —— 改名本身已经成功了，报错会让前端显示"改名失败"。
                .orElseGet(() -> new SessionSummary(conversationId, title, null, null, null));
    }

    /**
     * <b>删除整个会话</b>（阶段 14 补：这个能力在阶段 10 就欠着，前端一直没有删除键）。
     *
     * <h3>★ 它删三样东西，顺序不能换</h3>
     *
     * <pre>
     *   ① chat_message（记录层：权威·完整）   ← 失败就抛，用户看得见、可以重试
     *   ② Redis 上下文（可重建）             ← 失败只 WARN（它是缓存，删不掉也不会更糟）
     *   ③ chat_conversation（目录：侧栏那一行）← 最后删
     * </pre>
     *
     * <p><b>为什么是"内容先、目录后"</b>：两种失败模式的可见性不同 ——
     * <ul>
     *   <li>先删内容、目录失败 → 侧栏还留着那一行，点开是空的。<b>用户看得见没删干净，会再点一次</b>；</li>
     *   <li>先删目录、内容失败 → 侧栏里没有它，但数据还在库里。<b>用户以为自己删掉了</b>
     *       —— 那是最坏的结果：看不见的失败。</li>
     * </ul>
     * 与"降级要发生在能说出原因的那一层"同一条推理：<b>宁可失败得可见</b>。
     *
     * <p><b>为什么不走门面 {@code ConversationMemory#delete}</b>：它<b>吞掉</b>记录层异常
     * （只打 ERROR 日志）。对"写回"来说那是对的（模型回复已经产生，不能因为写库失败丢掉它），
     * 但对"删除"是错的：用户要求删数据，接口却返回 200 而数据还在 ——
     * 那就成了一次<b>假删除</b>。所以这里直接调记录层，让异常走正常错误路径。
     *
     * <h3>★ 明确<b>不删</b>的两样（这条会写进 HANDOFF 的诚实清单）</h3>
     *
     * <p>{@code chat_turn}（回合账）与 {@code chat_tool_invocation}（工具审计）
     * <b>按设计保留</b>：它们是"这个系统被调用过"的事实，不是"用户的对话内容"。
     * 审计随业务删除而消失，就不再是审计（真实系统里这由合规保留策略决定）。
     *
     * <p>⚠️ 但要诚实地说明代价：审计表的 {@code arguments} / {@code result} 里
     * <b>含用户原话与检索片段的原文</b>。所以本项目的"删除"目前是
     * <b>"删对话内容，不删行为痕迹"</b>，这个缺口已登记为待办
     * （要么改成"删除时脱敏这两列"，要么等鉴权阶段一起做保留策略）。
     *
     * @throws AgentException {@link ErrorCode#SESSION_NOT_FOUND} 会话不存在
     */
    public void delete(String conversationId) {
        if (conversationCatalog.find(conversationId).isEmpty()) {
            throw new AgentException(ErrorCode.SESSION_NOT_FOUND, conversationId);
        }
        // ① 记录层：失败直接抛（让用户知道没删掉，而不是收到 200 却什么都没发生）
        recordStore.delete(conversationId);
        // ② 上下文层：失败只 WARN。**必须做** —— 否则模型还会"记得"被删的内容
        try {
            contextCache.evict(conversationId);
        } catch (Exception e) {
            log.warn("[会话删除] 上下文缓存清除失败（会话内容已删除；下次读取会从记录层重建，"
                    + "而记录层已空）：会话={} 原因={}", conversationId, e.toString());
        }
        // ③ 目录：最后删 —— 侧栏那一行是"这个会话还在"的唯一可见标志
        conversationCatalog.delete(conversationId);
        log.info("[会话删除] 会话 {} 已删除：消息 + 上下文缓存 + 目录。"
                + "★ 审计（chat_tool_invocation）与回合账（chat_turn）按设计保留 —— "
                + "它们是行为痕迹，不是对话内容", conversationId);
    }

    // ==================== 类型翻译 ====================
    private SessionSummary toSummary(SessionStore.Session session) {
        return new SessionSummary(session.conversationId(), session.title(),
                session.mode(), session.createdAt(), session.updatedAt());
    }

    private SessionMessage toMessage(Message message, Map<String, TurnRecord> turns) {
        return new SessionMessage(
                message.getMessageType().name(),
                message.getText(),
                sectionsOf(message),
                statsOf(message, turns));
    }

    /**
     * 这条消息所属那一轮的统计；取不到返回 <b>null</b>（前端据此不显示那一行）。
     *
     * <p>★ 只有 assistant 消息带 {@code turn_id}：用户消息是"发起"，回答才是"这一轮的产出"。
     * 而"哪条消息显示统计"这个判断<b>只在这里做一次</b> —— 让前端去猜"该挂在哪条上"，
     * 就会出现两种实现（挂 user / 挂 assistant）各写一遍。
     */
    private TurnStatsView statsOf(Message message, Map<String, TurnRecord> turns) {
        if (turns.isEmpty()) {
            return null;
        }
        String turnId = MessageCodec.turnIdOf(message);
        if (turnId == null) {
            return null;
        }
        TurnRecord record = turns.get(turnId);
        if (record == null) {
            // 有 turn_id 但查不到账：只可能是账写入失败过（那条 ERROR 日志在 TurnStore 实现里）
            return null;
        }
        return new TurnStatsView(record.status().name(), record.elapsedMs(),
                record.firstTokenMs(), record.modelCalls(), record.toolCalls(),
                record.totalTokens(), record.usageMissingCalls());
    }

    /**
     * 把一条消息的思考文本解析成分段。
     *
     * <p>绝大多数消息（用户消息、没开思考时的助手消息）没有思考 →
     * 返回<b>空列表</b>而不是 null，前端可以直接 {@code v-for}。
     */
    private List<ReasoningSection> sectionsOf(Message message) {
        String trace = MessageCodec.reasoningOf(message);
        return ReasoningTrace.parse(trace).stream()
                .map(section -> new ReasoningSection(section.step(), section.tools(), section.text()))
                .toList();
    }
}
