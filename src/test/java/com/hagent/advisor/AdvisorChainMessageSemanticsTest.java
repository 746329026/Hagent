package com.hagent.advisor;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>切面链的消息传递语义实验</b> —— 用一个具体问题驱动：
 * "前一个切面加进 prompt 的消息，后一个切面能不能保留？"
 *
 * <h2>为什么必须做这个实验（而不是靠推测）</h2>
 *
 * <p>阶段 5 的 {@code ConversationMemoryAdvisor.augment()} <b>重建了整个消息列表</b>：
 * <pre>
 *   List&lt;Message&gt; messages = new ArrayList&lt;&gt;();        // 新建空列表
 *   messages.add(original.getSystemMessage());        // 只取 system
 *   messages.addAll(history);                         // 加历史
 *   messages.add(original.getUserMessage());          // 只取 user
 *   return new Prompt(messages, original.getOptions());  // 返回新列表
 * </pre>
 * 即：<b>任何不是 system / history / user 的消息都会被丢弃。</b>
 *
 * <p>阶段 6 的 RAG 要把"检索到的片段"加进 prompt，于是有两种互相矛盾的说法：
 * <ul>
 *   <li>说法一：框架的链会传递 {@code ChatClientRequest}，<b>后执行的切面在上一个的结果上改</b>，
 *       所以消息会累积；</li>
 *   <li>说法二：既然 {@code augment()} 重建了列表，前一个切面加的东西就没了。</li>
 * </ul>
 *
 * <p><b>本实验的结论：两种说法都对，但说的不是同一件事</b> ——
 * 框架确实会传递（实验 1），而"重建列表"是切面自身的写法选择、它会主动丢弃（实验 2），
 * 换成"追加"并在下游执行就保住了（实验 3）。
 *
 * <h2>方法：用真实链，不手工串</h2>
 *
 * <p>本测试用框架自己的 {@link DefaultAroundAdvisorChain} 构造真实链并让框架分发，
 * 而不是手工按顺序调用切面 —— 否则测的是"我以为的分发逻辑"，而不是框架的行为。
 * 最后一个切面负责把"链尾真正会交给模型的消息列表"截获下来。
 */
class AdvisorChainMessageSemanticsTest {

    private static final String SYSTEM_TEXT = "你是企业办公助手";
    private static final String USER_TEXT = "我们的年假怎么算？";
    private static final String RAG_TEXT = "【检索片段】年假 5 天，可累积";
    private static final String HISTORY_TEXT = "【历史】上一轮的回答";

    // ========================================================================
    // 三个用于实验的切面
    // ========================================================================

    /** 截获型：链的最后一环，把真正要交给模型的消息列表取出来。 */
    private static final class CapturingAdvisor implements CallAdvisor {
        private final AtomicReference<List<Message>> captured;

        CapturingAdvisor(AtomicReference<List<Message>> captured) {
            this.captured = captured;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            captured.set(List.copyOf(request.prompt().getInstructions()));
            return new ChatClientResponse(null, request.context());
        }

        @Override
        public String getName() {
            return "CapturingAdvisor";
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE - 1;   // 放最内层
        }
    }

    /**
     * 重建型：<b>复刻 ConversationMemoryAdvisor 的真实做法</b> ——
     * 新建列表，只挑 system + 历史 + user。
     */
    private static final class RebuildingAdvisor implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            Prompt original = request.prompt();
            List<Message> rebuilt = new ArrayList<>();
            if (original.getSystemMessage() != null) {
                rebuilt.add(original.getSystemMessage());
            }
            rebuilt.add(new AssistantMessage(HISTORY_TEXT));
            if (original.getUserMessage() != null) {
                rebuilt.add(original.getUserMessage());
            }
            return chain.nextCall(request.mutate()
                    .prompt(new Prompt(rebuilt, original.getOptions())).build());
        }

        @Override
        public String getName() {
            return "RebuildingAdvisor";
        }

        @Override
        public int getOrder() {
            return 1;
        }
    }

    /** 追加型：阶段 6 的 RAG 若做成切面，形状就是这样 —— 在现有列表上 append 一条 system 消息。 */
    private static final class AppendingAdvisor implements CallAdvisor {
        private final String text;
        private final int order;

        AppendingAdvisor(String text, int order) {
            this.text = text;
            this.order = order;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            List<Message> appended = new ArrayList<>(request.prompt().getInstructions());
            appended.add(new SystemMessage(text));
            return chain.nextCall(request.mutate()
                    .prompt(new Prompt(appended, request.prompt().getOptions())).build());
        }

        @Override
        public String getName() {
            return "AppendingAdvisor(order=" + order + ")";
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    // ========================================================================
    // 实验
    // ========================================================================

    private static Prompt basePrompt() {
        return new Prompt(List.of(new SystemMessage(SYSTEM_TEXT), new UserMessage(USER_TEXT)));
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder().prompt(basePrompt()).build();
    }

    @Test
    @DisplayName("★ 实验1：追加型在前、截获在内 → 追加的消息出现在链尾（框架确实传递）")
    void appendingMessageReachesInnerChain() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();

        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new AppendingAdvisor(RAG_TEXT, 0))
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(request());

        List<String> texts = captured.get().stream().map(Message::getText).toList();

        // ★ 前一个切面追加的消息，在后一个切面里能看到 —— 框架确实传递
        assertThat(texts).contains(RAG_TEXT);
        assertThat(texts).contains(SYSTEM_TEXT, USER_TEXT);
        assertThat(texts).hasSize(3);
    }

    @Test
    @DisplayName("★★ 实验2：追加型在外、重建型在内 → 追加的消息【被丢弃】（说法二成立，这是写法问题）")
    void rebuildingAdvisorDiscardsUpstreamAppend() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();

        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new AppendingAdvisor(RAG_TEXT, 0))     // 模拟 RAG，在外层
                .push(new RebuildingAdvisor())                // 模拟记忆切面，在内层
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(request());

        List<String> texts = captured.get().stream().map(Message::getText).toList();

        // ★ 关键断言：RAG 的片段不见了 —— 因为重建时只挑了 system / 历史 / user
        assertThat(texts).doesNotContain(RAG_TEXT);
        // 但其余内容都在（说明不是"整条链坏了"，而是"重建丢掉了不认识的消息"）
        assertThat(texts).contains(SYSTEM_TEXT, USER_TEXT, HISTORY_TEXT);
        assertThat(texts).hasSize(3);
    }

    @Test
    @DisplayName("★ 实验3：重建型在外、追加型在内 → 追加的消息【保住了】（order 必须不同，否则并列）")
    void appendingInnerOfRebuildingSurvives() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();

        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new RebuildingAdvisor())                // 记忆切面在外层（order 更小）
                .push(new AppendingAdvisor(RAG_TEXT, 5))      // RAG 在内层（order 必须 > 重建型）
                .push(new CapturingAdvisor(captured))
                .build()
                .nextCall(request());

        List<String> texts = captured.get().stream().map(Message::getText).toList();

        // ★ 这次 RAG 片段保住了 —— 因为它在"重建"之后才追加
        assertThat(texts).contains(RAG_TEXT);
        assertThat(texts).contains(SYSTEM_TEXT, USER_TEXT, HISTORY_TEXT);
        assertThat(texts).hasSize(4);
    }

    @Test
    @DisplayName("★ 实验4：框架按 order 排序分发（不是 push 的先后）—— 这决定了谁在外层")
    void frameworkSortsByOrderNotPushSequence() {
        AtomicReference<List<String>> executionOrder = new AtomicReference<>(new ArrayList<>());

        CallAdvisor first = new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                executionOrder.get().add("order=10");
                return chain.nextCall(request);
            }

            @Override
            public String getName() {
                return "ten";
            }

            @Override
            public int getOrder() {
                return 10;
            }
        };
        CallAdvisor second = new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                executionOrder.get().add("order=-5");
                return chain.nextCall(request);
            }

            @Override
            public String getName() {
                return "minusFive";
            }

            @Override
            public int getOrder() {
                return -5;
            }
        };

        // 故意先 push order=10，再 push order=-5
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(first)
                .push(second)
                .push(new CapturingAdvisor(new AtomicReference<>()))   // 链尾必须有 advisor
                .build()
                .nextCall(request());

        // ★ order 小的先执行（在外层），与 push 顺序无关
        assertThat(executionOrder.get()).containsExactly("order=-5", "order=10");
    }
}
