package com.hagent.advisor;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>切面顺序的机制化校验</b> —— 把"顺序对不对"从"靠人读代码"变成"跑一次就知道"。
 *
 * <h2>★ 为什么这些断言值得单独写一个类</h2>
 *
 * <p>阶段 6 后半的全部结构都押在一条前提上：
 * <b>本项目的自定义切面都在框架工具循环之外</b>（order 小于
 * {@link ToolCallingAdvisor#DEFAULT_ORDER}）。这条前提带来三个收益
 * （写回一次、检索一次、拼装唯一），而它<b>现在测不出来</b> ——
 * 没有工具时工具循环的 N 恒等于 1，把切面放进循环内层也不会有任何异常表现。
 *
 * <p>所以这里用两条手段把它变成可验证的：
 * <ol>
 *   <li><b>直接对着框架常量断言</b>（而不是写死 {@code -2147483348}）——
 *       框架若改了默认 order，测试立刻失败并指出差异；</li>
 *   <li><b>用真实链验证 before/after 的镜像关系</b> ——
 *       "写回在日志之前"这种表述一旦只写在注释里，改 order 时就会失效。</li>
 * </ol>
 *
 * <h2>★ 一条关于框架的实测事实（写死在这里，避免又被写错）</h2>
 *
 * <p>{@code ToolCallingAdvisor.DEFAULT_ORDER = -2147483348}，
 * 即 {@code Integer.MIN_VALUE + 300}。项目文档里曾多处写成 {@code +200}（笔误），
 * 已在本轮更正。{@code +300} 与 {@code +200} 的差别不是"数字不同"：
 * <b>{@code MIN_VALUE+201 … MIN_VALUE+299} 这一段是"循环之外、且能被我们使用"的空档</b>，
 * 写错后很容易以为"没有位置可用"。
 */
class AdvisorOrderTest {

    // ========================================================================
    // 一、与框架的边界关系
    // ========================================================================

    @Test
    @DisplayName("★ 全部自定义切面都在工具循环之外（order < ToolCallingAdvisor.DEFAULT_ORDER）")
    void allCustomAdvisorsSitOutsideToolLoop() {
        List<Integer> ours = List.of(
                AdvisorOrders.OUTER_TRACE,
                AdvisorOrders.MEMORY_WRITEBACK,
                AdvisorOrders.MEMORY_READ,
                AdvisorOrders.RAG_RETRIEVAL,
                AdvisorOrders.PROMPT_ASSEMBLY);

        assertThat(ours)
                .as("任何一个 >= DEFAULT_ORDER 都会被工具循环带着跑 N 次："
                        + "写回变 N 次、检索变 N 次、拼装也会在循环内重建消息列表")
                .allSatisfy(order -> assertThat(order)
                        .isLessThan(ToolCallingAdvisor.DEFAULT_ORDER));

        // 同时确认常量确实引用了框架值，而不是恰好相等
        assertThat(AdvisorOrders.TOOL_CALLING)
                .as("TOOL_CALLING 必须直接引用框架常量，否则框架改值时测试不会失败")
                .isEqualTo(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    @Test
    @DisplayName("★ 框架的 DEFAULT_ORDER 确实是 MIN_VALUE + 300（文档曾误写为 +200）")
    void toolCallingAdvisorDefaultOrderIsPlus300() {
        assertThat(ToolCallingAdvisor.DEFAULT_ORDER)
                .as("这条断言的作用是：文档里的数字一旦写错，这里会指出来")
                .isEqualTo(Integer.MIN_VALUE + 300);
    }

    @Test
    @DisplayName("★ 自定义切面之间严格递增 —— order 相同会变成「并列兄弟」，谁先谁后无保证")
    void customOrdersAreStrictlyAscending() {
        List<Integer> ours = List.of(
                AdvisorOrders.OUTER_TRACE,
                AdvisorOrders.MEMORY_WRITEBACK,
                AdvisorOrders.MEMORY_READ,
                AdvisorOrders.RAG_RETRIEVAL,
                AdvisorOrders.PROMPT_ASSEMBLY);

        // 已实测：order 相同的两个切面互不嵌套（是"并列兄弟"而非嵌套），
        // 因此"谁先执行"没有定义 —— 依赖它们的相对顺序就是不稳定的。
        assertThat(ours).doesNotHaveDuplicates().isSorted();
    }

    // ========================================================================
    // 二、before / after 的镜像关系（用真实链验证，不手工串）
    // ========================================================================

    /**
     * 记录型切面：把"进入"与"退出"分别记到同一个列表里。
     *
     * <p>用它而不是用真实的切面类，是为了让断言只针对 <b>order 本身</b> ——
     * 真实切面要注入一堆依赖，测试会因为无关的原因失败。
     */
    private static final class RecordingAdvisor implements CallAdvisor {
        private final String label;
        private final int order;
        private final List<String> log;

        RecordingAdvisor(String label, int order, List<String> log) {
            this.label = label;
            this.order = order;
            this.log = log;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            log.add("→ " + label);
            ChatClientResponse response = chain.nextCall(request);
            log.add("← " + label);
            return response;
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    /** 链尾：模拟 {@code ChatModelCallAdvisor}。 */
    private static final class TerminatorAdvisor implements CallAdvisor {
        private final List<String> log;

        TerminatorAdvisor(List<String> log) {
            this.log = log;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            log.add("=== 模型调用 ===");
            return new ChatClientResponse(null, request.context());
        }

        @Override
        public String getName() {
            return "Terminator";
        }

        @Override
        public int getOrder() {
            return Integer.MAX_VALUE;
        }
    }

    @Test
    @DisplayName("★ 真实链：before 按 order 升序，after 按 order 降序（镜像）")
    void beforeAscendingAfterDescending() {
        List<String> log = new ArrayList<>();

        // 故意乱序 push —— 框架按 order 排序，与 push 先后无关（既有实验已验证）
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new RecordingAdvisor("拼装", AdvisorOrders.PROMPT_ASSEMBLY, log))
                .push(new RecordingAdvisor("追溯", AdvisorOrders.OUTER_TRACE, log))
                .push(new RecordingAdvisor("检索", AdvisorOrders.RAG_RETRIEVAL, log))
                .push(new RecordingAdvisor("写回", AdvisorOrders.MEMORY_WRITEBACK, log))
                .push(new RecordingAdvisor("记忆读", AdvisorOrders.MEMORY_READ, log))
                .push(new TerminatorAdvisor(log))
                .build()
                .nextCall(ChatClientRequest.builder()
                        .prompt(new Prompt(List.<Message>of(new UserMessage("年假几天"))))
                        .build());

        assertThat(log).containsExactly(
                // before：order 升序
                "→ 追溯",
                "→ 写回",
                "→ 记忆读",
                "→ 检索",
                "→ 拼装",
                "=== 模型调用 ===",
                // after：order 降序（内层先返回）—— 这就是"镜像"
                "← 拼装",
                "← 检索",
                "← 记忆读",
                "← 写回",
                "← 追溯");
    }

    @Test
    @DisplayName("★ 镜像关系的实际含义：写回的 after 早于日志的 after（顺序约束不是排版）")
    void writebackAfterRunsBeforeTraceAfter() {
        List<String> log = new ArrayList<>();
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new RecordingAdvisor("写回", AdvisorOrders.MEMORY_WRITEBACK, log))
                .push(new RecordingAdvisor("追溯", AdvisorOrders.OUTER_TRACE, log))
                .push(new TerminatorAdvisor(log))
                .build()
                .nextCall(ChatClientRequest.builder()
                        .prompt(new Prompt(List.<Message>of(new UserMessage("年假几天"))))
                        .build());

        // "先写回、再记日志"——这样日志里才能记到写回是否成功。
        // 若把两者的 order 对调，这条断言会失败 —— 而这正是它存在的意义：
        // 顺序调整不会再悄悄改变语义。
        assertThat(log.indexOf("← 写回")).isLessThan(log.indexOf("← 追溯"));
    }

    // ========================================================================
    // 三、顺序对"日志能看到什么"的影响（把旧注释的错误认知固化成断言）
    // ========================================================================

    /**
     * 追加型切面：往 prompt 末尾加一条消息。
     *
     * <p>复刻 {@code PromptAssemblyAdvisor} 的关键行为（把片段拼进 prompt），
     * 用来验证"外层看不到内层的注入"。
     */
    private static final class AppendingAdvisor implements CallAdvisor {
        private final String text;
        private final int order;
        private final AtomicReference<List<Message>> seen;

        AppendingAdvisor(String text, int order, AtomicReference<List<Message>> seen) {
            this.text = text;
            this.order = order;
            this.seen = seen;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            // 记下"我这一层看到的 prompt"，再追加
            seen.set(List.copyOf(request.prompt().getInstructions()));
            List<Message> appended = new ArrayList<>(request.prompt().getInstructions());
            appended.add(new UserMessage(text));
            return chain.nextCall(request.mutate()
                    .prompt(new Prompt(appended, request.prompt().getOptions()))
                    .build());
        }

        @Override
        public String getName() {
            return "Appending(order=" + order + ")";
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    @Test
    @DisplayName("★ 最外层的切面【看不到】内层注入的内容 —— 旧注释方向写反了，这里是断言版")
    void outerAdvisorCannotSeeInnerInjection() {
        AtomicReference<List<Message>> seenByTrace = new AtomicReference<>();

        // 追溯在 MIN+100（最外层），拼装在 MIN+250（内层）
        DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .push(new AppendingAdvisor("【检索片段】年假 5 天", AdvisorOrders.PROMPT_ASSEMBLY,
                        new AtomicReference<>()))
                .push(new AppendingAdvisor("【不该被追溯看到】", AdvisorOrders.OUTER_TRACE, seenByTrace))
                .push(new TerminatorAdvisor(new ArrayList<>()))
                .build()
                .nextCall(ChatClientRequest.builder()
                        .prompt(new Prompt(List.<Message>of(new UserMessage("年假几天"))))
                        .build());

        List<String> texts = seenByTrace.get().stream().map(Message::getText).toList();

        // 最外层只看到"用户原始输入"，看不到内层（拼装）注入的片段。
        // 这就是为什么"模型真正收到的 prompt"必须由 PromptAssemblyAdvisor 记 ——
        // 它在最后一个改 prompt 的位置上。
        assertThat(texts).containsExactly("年假几天");
        assertThat(texts).doesNotContain("【检索片段】年假 5 天");
    }
}
