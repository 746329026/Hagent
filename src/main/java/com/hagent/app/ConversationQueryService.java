package com.hagent.app;

import com.hagent.common.ErrorCode;
import com.hagent.dto.SessionDtos.ReasoningSection;
import com.hagent.dto.SessionDtos.SessionDetail;
import com.hagent.dto.SessionDtos.SessionMessage;
import com.hagent.dto.SessionDtos.SessionSummary;
import com.hagent.exception.AgentException;
import com.hagent.memory.MessageCodec;
import com.hagent.memory.ReasoningTrace;
import com.hagent.memory.record.ConversationRecordStore;
import com.hagent.memory.session.ConversationCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话列表 / 历史 / 改名 —— <b>只读 + 改名</b>，不涉及模型调用。
 *
 * <h2>★ 为什么它存在（而不是 Controller 直接调存储）</h2>
 *
 * <p>三件事必须有人做，而它们都不属于 HTTP 层，也不属于存储层：
 * <ol>
 *   <li><b>把两个存储拼起来</b>：标题/模式来自 {@link ConversationCatalog}，
 *       消息来自 {@link ConversationRecordStore}。"一个会话的详情"横跨这两者，
 *       拼接点是本层；</li>
 *   <li><b>把存储类型翻译成 API 类型</b>：{@code ConversationCatalog.Session} 与
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
public class ConversationQueryService {

    /** 侧栏默认返回多少个会话（调用方不指定时）。 */
    private static final int DEFAULT_LIMIT = 50;

    private final ConversationCatalog conversationCatalog;

    /** 直接依赖记录层而不是门面 {@code ConversationMemory}：门面只提供"按预算裁剪"的读。 */
    private final ConversationRecordStore recordStore;

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
        ConversationCatalog.Session session = conversationCatalog.find(conversationId)
                .orElseThrow(() -> new AgentException(ErrorCode.SESSION_NOT_FOUND, conversationId));
        List<Message> messages = recordStore.readAll(conversationId);
        return new SessionDetail(toSummary(session),
                messages.stream().map(this::toMessage).toList());
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

    // ==================== 类型翻译 ====================

    private SessionSummary toSummary(ConversationCatalog.Session session) {
        return new SessionSummary(session.conversationId(), session.title(),
                session.mode(), session.createdAt(), session.updatedAt());
    }

    private SessionMessage toMessage(Message message) {
        return new SessionMessage(
                message.getMessageType().name(),
                message.getText(),
                sectionsOf(message));
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
