package com.hagent.app;

import com.hagent.agent.PipelineEvent;
import com.hagent.config.StreamProperties;
import com.hagent.dto.SessionDtos.TurnStatsView;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.catalog.SessionStore;
import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStatus;
import com.hagent.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>回合账 → SSE 帧</b>这一段契约（{@code TurnFinished} → {@code stats}）。
 *
 * <p>为什么这条边要单独测："A 类负责抛、B 类负责接"时，两边各自的用例都能通过，
 * 而 <b>A→B 那条边没人测</b> —— 本项目就因此漏过"非法 token 报 500"（177 用例全绿）。
 *
 * <p>为什么断言<b>序列化之后的 JSON</b>而不是 record 的访问器：浏览器拿到的是 JSON，
 * 字段名一旦写错，访问器断言照样全绿。所以这里用与生产同一个 Jackson
 * （{@code tools.jackson}，不是 2.x）序列化出来看真实键名。
 */
class StatsFrameTest {

    @Test
    @DisplayName("★★ TurnFinished → stats 帧：只含前端要的字段，且绝不带身份")
    void turnFinishedBecomesStatsFrameWithoutIdentity() {
        AgentPipeline pipeline = mock(AgentPipeline.class);
        SessionStore catalog = mock(SessionStore.class);
        ChatAppService service = new ChatAppService(pipeline, new StreamProperties(), catalog);

        TurnRecord record = new TurnRecord("turn-1", "conv-1", "zhangsan", "EMPLOYEE", "chat",
                TurnStatus.SUCCESS, "stop", 7270L, 1203L, 2, 2, 45L,
                2000, 500, 2500, 0, null, null);
        when(pipeline.run(any(), any(), any(), any()))
                .thenReturn(Flux.just(
                        new PipelineEvent.ModelOutput.Final("答案", "想过", "STOP",
                                new DefaultUsage(2000, 500, 2500)),
                        new PipelineEvent.TurnFinished(record)));

        CapturingEmitter emitter = new CapturingEmitter();
        service.stream("问一句", "conv-1", MemoryMode.CHAT, UserContext.ANONYMOUS, emitter);

        assertThat(emitter.joinedText())
                .as("事件名必须是 stats —— 前端就是按这个名字分支的")
                .contains("event:stats");

        String json = emitter.statsJson();
        assertThat(json)
                .as("★ 只放前端要显示的那几项：record 的字段名就是前端契约")
                .contains("\"status\"", "\"elapsedMs\"", "\"modelCalls\"", "\"toolCalls\"",
                        "\"firstTokenMs\"", "\"totalTokens\"")
                .as("★ 身份绝不进帧：浏览器就是这个人，回显只是把身份写进前端状态与日志")
                .doesNotContain("zhangsan")
                .doesNotContain("EMPLOYEE")
                .doesNotContain("userId")
                .doesNotContain("role")
                .doesNotContain("conv-1")
                .doesNotContain("turn-1");
        assertThat(json).contains("\"status\":\"SUCCESS\"").contains("\"totalTokens\":2500");

        assertThat(emitter.joinedText())
                .as("★ done 的载荷仍是字面量 [DONE] —— 阶段 14 刻意没动这个契约"
                        + "（改了它，前端的 done 分支与 api.js 的注释都要跟着动）")
                .contains("[DONE]");
    }

    /**
     * 捕获型 {@code SseEmitter}：把每个事件拆成"字符串片段 + 载荷对象"收集起来。
     *
     * <p>{@code send(SseEventBuilder)} 不是 final，且 {@code builder.build()} 会把
     * id/event/data 全部物化成 {@code DataWithMediaType} 列表 —— 于是"服务端到底发了什么"
     * 可以被逐项断言，而不是只看"没抛异常"。
     */
    private static final class CapturingEmitter extends SseEmitter {

        /** 与生产同一个 Jackson 3（{@code tools.jackson}），不是 2.x。 */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final List<Object> items = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                items.add(item.getData());
            }
        }

        /** 所有字符串片段拼起来 —— 用来断言 "event:stats" 与 "[DONE]"。 */
        String joinedText() {
            StringBuilder sb = new StringBuilder();
            for (Object item : items) {
                if (item instanceof String text) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }

        /** 唯一的一个统计载荷，按浏览器实际收到的方式序列化。 */
        String statsJson() {
            List<TurnStatsView> views = items.stream()
                    .filter(TurnStatsView.class::isInstance)
                    .map(TurnStatsView.class::cast)
                    .toList();
            assertThat(views).as("应当恰好一个 stats 载荷").hasSize(1);
            return MAPPER.writeValueAsString(views.getFirst());
        }
    }
}
