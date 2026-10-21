package com.hagent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 工作流规划提示词的<b>唯一出处</b>：{@code classpath:prompts/workflow-plan-prompt.md}。
 *
 * <h2>★ 为什么不走 {@code PromptProperties}</h2>
 *
 * <p>{@code PromptProperties} 绑的是"可配置"的提示词（回答约束、RAG 提示词）——
 * 它们能通过 yml 替换，因为部署方可能要改措辞。规划提示词不是那种东西：
 * 它的<b>JSON 形状与解析器是一对死契约</b>（改了提示词就必须改解析器），
 * 允许 yml 覆盖只会让"部署时悄悄改坏"成为可能。
 *
 * <p>所以它是<b>打包资源 + 编译期常量</b>：读不到就启动/首次调用即失败，
 * 而不是退回一份"内置默认文本"（那会让包装错时静默用了旧文案）。
 *
 * <h2>★ 为什么用原生 {@code getResourceAsStream} 而不是 Spring 的 {@code ClassPathResource}</h2>
 *
 * <p>这是纯静态文本，连 Spring 容器都不需要参与；用 JDK API 的话，
 * 单元测试里直接调 {@link #text()} 就能读到，不必起 Spring 上下文。
 */
public final class WorkflowPlanPrompt {

    private static final String RESOURCE = "/prompts/workflow-plan-prompt.md";

    /** 类加载时读一次 —— 资源是只读的，重复读盘没有意义。 */
    private static final String TEXT = load();

    private WorkflowPlanPrompt() {
    }

    public static String text() {
        return TEXT;
    }

    private static String load() {
        try (InputStream in = WorkflowPlanPrompt.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到规划提示词资源 " + RESOURCE + "（打包漏了？它必须在 src/main/resources 下）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("读取规划提示词失败：" + RESOURCE, e);
        }
    }
}
