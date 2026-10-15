package com.hagent.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>模型输出的形状实测探针</b> —— 阶段 8 把 advisor 链换成 pipeline 之前，
 * 必须先用<b>真实模型</b>回答四个问题。全部是"不实测就会猜错、且猜错不报错"的那一类。
 *
 * <h2>为什么必须在动手前实测</h2>
 *
 * <p>阶段 8 之后，`ChatModel` 由 pipeline <b>直接</b>调用，不再经 `ChatClient`。
 * 而 `ChatClient.stream().content()` 帮我们做了两件事（字节码已证）：
 * <pre>
 *   mapNotNull(chunk -&gt; chunk.getResult().getOutput().getText())
 *   filter(text -&gt; !text.isEmpty())        ← ★ 空片段被丢掉
 * </pre>
 * 换成直连 `ChatModel` 后<b>这两件事都要自己写</b>。问题在于：写错了不会报错 ——
 * 只会往 SSE 里多吐几个空事件，或把增量当累计、把回答拼重复。
 *
 * <h2>它要回答的四个问题</h2>
 *
 * <ol>
 *   <li><b>流式每片 text 是"增量"还是"累计"？</b>
 *       决定 `Final` 事件里的完整文本是"取最后一片"还是"自己拼"。
 *       猜错的后果：回答要么重复 N 遍，要么只剩最后一个字。</li>
 *   <li><b>思考内容（reasoning）出现在哪些片？</b>
 *       决定前端"思考区"什么时候开始渲染、什么时候关闭。</li>
 *   <li><b>同一片里 text 与 reasoning 是否互斥？</b>
 *       若思考阶段 text 全为空、回答阶段 reasoning 全为空，前端就该按<b>两条独立流</b>渲染；
 *       否则按一条流。</li>
 *   <li><b>阻塞式 call 也拿得到思考吗？</b>
 *       `/chat` 那条路要不要显示思考，取决于此。</li>
 * </ol>
 *
 * <h2>★ 取值路径的实测结论（本条已由字节码证实，探针用于复核）</h2>
 *
 * <p>思考内容<b>不在</b> {@code getText()} 里，它是一个独立字段：
 * <pre>
 *   DeepSeekAssistantMessage extends AssistantMessage
 *       private String reasoningContent;
 *       public String getReasoningContent();
 * </pre>
 * 而且 {@code ChatGenerationMetadata} <b>没有</b>对应的 metadata key 常量 ——
 * 只能走 {@code instanceof DeepSeekAssistantMessage} 取值。
 * 所以本探针<b>刻意直接引用</b>该类型：它就是要暴露这处"供应商耦合"，
 * 将来若换掉 DeepSeek，这里的编译失败正是我们要的提醒。
 *
 * <h2>★★ 为什么用独立环境变量做开关，而不是 DEEPSEEK_API_KEY</h2>
 *
 * <p>跑测试时必须设 {@code DEEPSEEK_API_KEY}（哪怕是个假值，启动期只做
 * {@code Assert.hasText}）。若拿它当"是否真调模型"的判据，<b>每次跑全量测试都会真的花钱</b>。
 * 所以这里要求另设 {@code HAGENT_LIVE_PROBE=1} —— 双重条件才放行。
 *
 * <h2>跑法（会产生真实 token 费用，约几分钱）</h2>
 *
 * <pre>
 *   $env:DEEPSEEK_API_KEY = "&lt;真 key&gt;"
 *   $env:HAGENT_LIVE_PROBE = "1"
 *   .\mvnw.cmd -B -ntp test -Dtest=ModelOutputProbeTest
 * </pre>
 *
 * <p><b>本测试不断言"结果应该是什么"</b>（那样会变成猜标准答案），它只断言
 * <b>"确实拿到了非空输出"</b>作为正向对照，把形状全部打印出来供人工判读 ——
 * 对应协议第 3 条"结论必须有证据"，以及"失败与正常同形的地方必须有正向断言"。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 关掉启动探针的真实调用：本探针自己会调模型，不需要启动时再花一次
        "hagent.probe.live-call=false"
})
class ModelOutputProbeTest {

    private static final String LIVE_PROBE_FLAG = "HAGENT_LIVE_PROBE";

    /** 简单但会触发推理的问题（深思考才会吐 reasoning_content）。 */
    private static final String QUESTION =
            "一个班级有 30 名学生，其中 60% 是女生。女生里有一半参加了合唱团。参加合唱团的女生有多少人？请给出推理过程。";

    @Autowired
    private ChatModel chatModel;

    @BeforeEach
    void requireExplicitOptIn() {
        assumeTrue("1".equals(System.getenv(LIVE_PROBE_FLAG)),
                "未设置 " + LIVE_PROBE_FLAG + "=1 —— 本探针会真实调用模型并产生费用，"
                        + "故默认跳过。要跑请显式设置该环境变量。");
    }

    // ========================================================================
    // 问题 1 ~ 3：流式形状
    // ========================================================================

    @Test
    @DisplayName("★ 流式：逐片实时打印 text / reasoning，判定增量还是累计")
    void inspectStreamingShape() {
        System.out.println();
        System.out.println("================ 流式形状实测 ================");
        System.out.println("【提示】下面开始逐片实时输出。reasoning-effort=HIGH 时思考可能耗时数十秒，");
        System.out.println("        只要还在打印 [片 N] 就说明【正在正常工作】，请勿中断。");
        System.out.println();

        AtomicInteger index = new AtomicInteger();
        StringBuilder accumulatedText = new StringBuilder();
        StringBuilder accumulatedReasoning = new StringBuilder();
        AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();

        long startNanos = System.nanoTime();

        chatModel.stream(new Prompt(QUESTION))
                // ★ doOnNext 而不是 collectList().block()：
                //   后者要等整流结束才打印第一个字，屏幕长时间无输出，看起来就像卡死 —— 实测发生过
                .doOnNext(chunk -> {
                    int i = index.getAndIncrement();
                    lastChunk.set(chunk);

                    String text = textOf(chunk);
                    String reasoning = reasoningOf(chunk);
                    accumulatedText.append(text == null ? "" : text);
                    accumulatedReasoning.append(reasoning == null ? "" : reasoning);

                    System.out.printf("[片 %2d] 距开始 %5.2fs  finishReason=%-12s%n",
                            i, (System.nanoTime() - startNanos) / 1_000_000_000.0, finishReasonOf(chunk));
                    // ★ 全量打印（本人要求：测试阶段全部输出，便于观测）
                    System.out.println("        text      = " + quote(text));
                    System.out.println("        reasoning = " + quote(reasoning));
                    System.out.println("        text累计长度=" + accumulatedText.length()
                            + "  reasoning累计长度=" + accumulatedReasoning.length());
                })
                // 兜底超时：宁可明确失败，也不要无限等（真实模型调用可能挂住）
                .blockLast(Duration.ofMinutes(5));

        System.out.println();
        System.out.println("---- 判定用的关键观察 ----");
        ChatResponse last = lastChunk.get();
        System.out.println("总片数            = " + index.get());
        System.out.println("总耗时            = "
                + String.format("%.2fs", (System.nanoTime() - startNanos) / 1_000_000_000.0));
        if (last == null) {
            System.out.println("★ 一片都没收到 —— 上面若没有异常，说明超时了");
        } else {
            System.out.println("最后一片 text 长度 = " + length(textOf(last)));
            System.out.println("全片拼接 text 长度 = " + accumulatedText.length());
            System.out.println();
            System.out.println("→ 两者【相等】  ⇒ 每片是【累计】全量文本（Final 取最后一片即可）");
            System.out.println("→ 后者【远大于】⇒ 每片是【增量】（Final 必须自己拼接）");
            System.out.println();
            System.out.println("最后一片 reasoning 长度  = " + length(reasoningOf(last)));
            System.out.println("全片拼接 reasoning 长度  = " + accumulatedReasoning.length());
            System.out.println("最后一片 finishReason    = " + finishReasonOf(last));
            System.out.println();
            System.out.println("output 实际类型 = " + outputTypeOf(last));
            System.out.println("metadata        = " + metadataOf(last));
            System.out.println();
            System.out.println("---- 全片拼接后的完整内容 ----");
            System.out.println("[reasoning全文] " + quote(accumulatedReasoning.toString()));
            System.out.println("[text全文]      " + quote(accumulatedText.toString()));
        }
        System.out.println("================ 流式形状实测结束 ================");
        System.out.println();

        // 正向断言：必须真的拿到内容（否则"形状为空"会被当成"没有思考能力"）
        assertThat(index.get())
                .as("必须真的收到流式分片 —— 否则后面所有判读都是对着空列表做")
                .isPositive();
        assertThat(accumulatedText.length())
                .as("模型必须产出至少一个字符的回答文本")
                .isPositive();
    }

    // ========================================================================
    // 问题 4：阻塞式 call 的形状
    // ========================================================================

    @Test
    @DisplayName("★ 阻塞式：call 是否也带 reasoning（决定 /chat 能否显示思考）")
    void inspectBlockingShape() {
        System.out.println();
        System.out.println("================ 阻塞式形状实测 ================");
        System.out.println("【提示】阻塞调用期间【不会有任何输出】，这是正常的 —— 它要等完整回复。");
        System.out.println("        reasoning-effort=HIGH 时可能要等数十秒，请勿中断。");
        System.out.println();

        long startNanos = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(QUESTION));
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        assertThat(response)
                .as("阻塞调用必须拿到响应")
                .isNotNull();
        assertThat(textOf(response))
                .as("阻塞调用必须拿到回答文本")
                .isNotBlank();

        System.out.println(String.format("耗时 = %.2fs", seconds));
        System.out.println("finishReason = " + finishReasonOf(response));
        System.out.println("metadata     = " + metadataOf(response));
        System.out.println("output 实际类型 = " + outputTypeOf(response));
        System.out.println();
        System.out.println("reasoning 长度 = " + length(reasoningOf(response)));
        System.out.println("reasoning 内容 = " + quote(reasoningOf(response)));
        System.out.println();
        System.out.println("text 长度 = " + length(textOf(response)));
        System.out.println("text 内容 = " + quote(textOf(response)));
        System.out.println();
        System.out.println("→ reasoning 非空 ⇒ 阻塞路径也能显示思考");
        System.out.println("→ reasoning 为空 ⇒ /chat 那条路拿不到思考，只有流式能显示");
        System.out.println("================ 阻塞式形状实测结束 ================");
        System.out.println();
    }

    // ========================================================================
    // 取值辅助 —— 刻意把"供应商耦合"集中在这两个方法里
    // ========================================================================

    /** 取文本；每片可能没有 result（例如只有 metadata 的收尾片）。 */
    private static String textOf(ChatResponse response) {
        return outputOf(response)
                .map(AssistantMessage::getText)
                .orElse(null);
    }

    /**
     * 取思考内容 —— ★ 只能靠 {@code instanceof}。
     *
     * <p>见类注释：Spring AI 2.0.1 没有为它提供任何通用 metadata key，
     * {@code reasoningContent} 是 {@link DeepSeekAssistantMessage} 的独有字段。
     * 拿不到时返回 null，<b>不抛异常</b> —— 换模型时"没有思考"是合法状态，
     * 但调用方应当据此记 WARN（本探针靠打印暴露它）。
     */
    private static String reasoningOf(ChatResponse response) {
        return outputOf(response)
                .filter(DeepSeekAssistantMessage.class::isInstance)
                .map(DeepSeekAssistantMessage.class::cast)
                .map(DeepSeekAssistantMessage::getReasoningContent)
                .orElse(null);
    }

    private static String outputTypeOf(ChatResponse response) {
        return outputOf(response)
                .map(m -> m.getClass().getName())
                .orElse("<无 output>");
    }

    private static Optional<AssistantMessage> outputOf(ChatResponse response) {
        if (response == null) {
            return Optional.empty();
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(generation.getOutput());
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return "<无>";
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    private static String metadataOf(ChatResponse response) {
        return response == null ? "<无响应>" : String.valueOf(response.getMetadata());
    }

    /** 打印用：null 与空串要能分辨（这正是"失败与正常同形"的地方）。 */
    private static String quote(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isEmpty()) {
            return "<空串>";
        }
        return "「" + value + "」";
    }

    private static int length(String value) {
        return value == null ? -1 : value.length();
    }
}
