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
 * 模型输出的形状实测探针：用真实模型回答四个"不实测就会猜错、且猜错不报错"的问题。
 *
 * <p>pipeline 之后 {@code ChatModel} 被直接调用，不再经 {@code ChatClient}：后者的
 * {@code stream().content()} 帮我们丢掉了空片段、取出了 text，直连后这两件事都要自己写 ——
 * 写错不报错，只是往 SSE 多吐几个空事件、或把增量当累计把回答拼重复。
 *
 * <ol>
 *   <li><b>流式每片 text 是增量还是累计？</b>决定 Final 取最后一片还是自己拼（猜错则回答重复或只剩最后一个字）。</li>
 *   <li><b>reasoning 出现在哪些片？</b>决定前端"思考区"何时开始渲染、何时关闭。</li>
 *   <li><b>同一片里 text 与 reasoning 是否互斥？</b>决定前端按两条独立流还是一条流渲染。</li>
 *   <li><b>阻塞式 call 也拿得到思考吗？</b>决定 /chat 那条路要不要显示思考。</li>
 * </ol>
 *
 * <p>取值路径：思考内容不在 {@code getText()} 里，是 {@link DeepSeekAssistantMessage} 的独有字段
 * {@code reasoningContent}，Spring AI 2.0.1 没有为它提供通用 metadata key，只能 instanceof 取。
 * 本探针刻意直接引用该类型 —— 它就是要暴露这处供应商耦合，换掉 DeepSeek 时编译失败正是提醒。
 *
 * <p>必须另设 {@code HAGENT_LIVE_PROBE=1} 才真调模型：跑测试必须设 {@code DEEPSEEK_API_KEY}
 * （启动期只做 {@code Assert.hasText}），拿它当判据会让每次全量测试都真花钱；本探针会真实计费。
 *
 * <p>不断言"结果应该是什么"（那会变成猜标准答案），只断言"确实拿到了非空输出"作正向对照，
 * 形状全部打印出来供人工判读。
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

    // 问题 1 ~ 3：流式形状

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
                // 用 doOnNext 而不是 collectList().block()：后者要等整流结束才打印第一个字，
                // 屏幕长时间无输出看起来就像卡死 —— 实测发生过
                .doOnNext(chunk -> {
                    int i = index.getAndIncrement();
                    lastChunk.set(chunk);

                    String text = textOf(chunk);
                    String reasoning = reasoningOf(chunk);
                    accumulatedText.append(text == null ? "" : text);
                    accumulatedReasoning.append(reasoning == null ? "" : reasoning);

                    System.out.printf("[片 %2d] 距开始 %5.2fs  finishReason=%-12s%n",
                            i, (System.nanoTime() - startNanos) / 1_000_000_000.0, finishReasonOf(chunk));
                    // 全量打印，便于观测
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

    // 问题 4：阻塞式 call 的形状

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

    // 取值辅助 —— 刻意把"供应商耦合"集中在这两个方法里

    /** 取文本；每片可能没有 result（例如只有 metadata 的收尾片）。 */
    private static String textOf(ChatResponse response) {
        return outputOf(response)
                .map(AssistantMessage::getText)
                .orElse(null);
    }

    /**
     * 取思考内容 —— 只能靠 {@code instanceof}：{@code reasoningContent} 是
     * {@link DeepSeekAssistantMessage} 的独有字段，没有通用 metadata key。
     * 拿不到时返回 null 而不抛异常 —— 换模型后"没有思考"是合法状态，调用方据此记 WARN。
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
