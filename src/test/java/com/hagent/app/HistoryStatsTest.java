package com.hagent.app;

import com.hagent.dto.SessionDtos.SessionDetail;
import com.hagent.dto.SessionDtos.SessionMessage;
import com.hagent.memory.context.ConversationContextCache;
import com.hagent.memory.catalog.SessionStore;
import com.hagent.memory.MessageCodec;
import com.hagent.memory.record.MessageStore;
import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStatus;
import com.hagent.observability.TurnStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 14 后补：<b>历史回放也要能看到那一行统计</b>（{@code GET /chat/sessions/{id}/messages}）。
 *
 * <h2>★ 它解决的那个具体问题</h2>
 *
 * <p>实时那一行来自 SSE 的 {@code stats} 事件，<b>刷新页面就没了</b> ——
 * 因为消息表当时只知道自己属于哪个会话，不知道"是哪一轮产生的"。
 * 补法：写回时把 {@code turn_id} 挂进 assistant 消息的 properties（落进
 * {@code chat_message.turn_id}），回放时按它批量查 {@code chat_turn}。
 *
 * <h2>★ 这条边为什么必须单独测</h2>
 *
 * <p>写的一侧（{@code AgentPipeline#writeBack}）与读的一侧（本类）<b>各自都能通过</b>：
 * 一条断言"库里存了 turn_id"，一条断言"stats 能序列化"。而
 * <b>"存了但读不出来"</b>（properties 键名写歪、或回放忘了查库）只有把两端放进
 * 同一个断言才抓得到 —— 它的症状是"历史里那一行永远是空的"，<b>不报错</b>。
 *
 * <p>用 mock 而不是真库：这里要验的是"拼装逻辑"，真库那条往返由
 * {@code MysqlMessageStoreTest#turnIdRoundTripsThroughDatabase} 负责。
 */
class HistoryStatsTest {

    private static final String CONVERSATION = "conv-history-1";
    private static final String TURN_ID = "turn-abc";

    @Test
    @DisplayName("★★ 历史回放：assistant 消息带上所属那一轮的统计，用户消息不带")
    void historyCarriesTurnStatsOnAssistantMessage() {
        SessionStore catalog = mock(SessionStore.class);
        MessageStore recordStore = mock(MessageStore.class);
        ConversationContextCache cache = mock(ConversationContextCache.class);
        TurnStore turnStore = mock(TurnStore.class);

        when(catalog.find(CONVERSATION)).thenReturn(Optional.of(
                new SessionStore.Session(CONVERSATION, "年假怎么算", "chat",
                        Instant.now(), Instant.now())));
        when(recordStore.readAll(CONVERSATION)).thenReturn(List.of(
                new UserMessage("公司年假怎么算？"),
                AssistantMessage.builder()
                        .content("满一年 5 天。")
                        .properties(Map.of(MessageCodec.TURN_ID_PROPERTY, TURN_ID))
                        .build()));
        when(turnStore.find(anyCollection())).thenReturn(Map.of(TURN_ID, record()));

        SessionDetail detail = service(catalog, recordStore, cache, turnStore).detail(CONVERSATION);

        assertThat(detail.messages()).hasSize(2);

        SessionMessage ask = detail.messages().get(0);
        assertThat(ask.stats())
                .as("★ 用户消息不挂统计：它是『发起』，回答才是这一轮的产出。"
                        + "挂在两边会让前端出现两行长字")
                .isNull();

        SessionMessage answer = detail.messages().get(1);
        assertThat(answer.stats())
                .as("★ 刷新后仍要看到那一行 —— 这是本用例存在的唯一理由")
                .isNotNull();
        assertThat(answer.stats().status()).isEqualTo("SUCCESS");
        assertThat(answer.stats().elapsedMs()).isEqualTo(7270L);
        assertThat(answer.stats().totalTokens()).isEqualTo(2500);
        assertThat(answer.stats().modelCalls()).isEqualTo(2);
        assertThat(answer.stats().toolCalls()).isEqualTo(2);

        // ★ 批量查、而且是按消息里带的 turnId 查的（不是按会话把整张表拖出来）
        verify(turnStore).find(List.of(TURN_ID));
    }

    @Test
    @DisplayName("★ 阶段 14 之前的历史（没有 turn_id）：不去查账，stats 为 null")
    void oldHistoryWithoutTurnIdSkipsTheLookup() {
        SessionStore catalog = mock(SessionStore.class);
        MessageStore recordStore = mock(MessageStore.class);
        ConversationContextCache cache = mock(ConversationContextCache.class);
        TurnStore turnStore = mock(TurnStore.class);

        when(catalog.find(CONVERSATION)).thenReturn(Optional.of(
                new SessionStore.Session(CONVERSATION, "老会话", "chat", Instant.now(), Instant.now())));
        when(recordStore.readAll(CONVERSATION)).thenReturn(List.<Message>of(
                new UserMessage("旧问题"),
                new AssistantMessage("旧回答")));

        SessionDetail detail = service(catalog, recordStore, cache, turnStore).detail(CONVERSATION);

        assertThat(detail.messages()).allSatisfy(message -> assertThat(message.stats()).isNull());
        verify(turnStore, never()).find(any());
    }

    @Test
    @DisplayName("★★ 账读不出来时：历史照常返回（正文与思考都在），只是没有那一行统计")
    void turnStoreFailureDoesNotBreakHistory() {
        SessionStore catalog = mock(SessionStore.class);
        MessageStore recordStore = mock(MessageStore.class);
        ConversationContextCache cache = mock(ConversationContextCache.class);
        TurnStore broken = mock(TurnStore.class);

        when(catalog.find(CONVERSATION)).thenReturn(Optional.of(
                new SessionStore.Session(CONVERSATION, "会话", "chat", Instant.now(), Instant.now())));
        when(recordStore.readAll(CONVERSATION)).thenReturn(List.<Message>of(
                new UserMessage("问"),
                AssistantMessage.builder()
                        .content("答")
                        .properties(Map.of(MessageCodec.TURN_ID_PROPERTY, TURN_ID))
                        .build()));
        when(broken.find(anyCollection())).thenThrow(new IllegalStateException("库挂了"));

        SessionDetail detail = service(catalog, recordStore, cache, broken).detail(CONVERSATION);

        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(1).content())
                .as("★ 为一个可选的旁证让『打开会话』失败是本末倒置（与检索降级同一立场）")
                .isEqualTo("答");
        assertThat(detail.messages().get(1).stats()).isNull();
    }

    // ==================== 脚手架 ====================

    private ConversationService service(SessionStore catalog, MessageStore recordStore,
                                        ConversationContextCache cache, TurnStore turnStore) {
        return new ConversationService(catalog, recordStore, cache, turnStore);
    }

    private TurnRecord record() {
        return new TurnRecord(TURN_ID, CONVERSATION, "zhangsan", "EMPLOYEE", "chat",
                TurnStatus.SUCCESS, "stop", 7270L, 1203L, 2, 2, 45L,
                2000, 500, 2500, 0, null, null);
    }
}
