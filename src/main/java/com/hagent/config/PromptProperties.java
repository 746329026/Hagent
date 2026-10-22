package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词拼装的可调参数，绑定 {@code hagent.rag.prompt.*}。
 *
 * <p>为什么做成配置项：排查"回答质量是检索的锅还是提示词的锅"时，需要一个开关把约束整段
 * 换掉（或摘掉），而不是改代码重新编译。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.prompt")
public class PromptProperties {

    /**
     * 回答约束提示词的资源位置，默认 {@code classpath:prompts/rag-answer-prompt.md}。
     *
     * <p><b>为什么提示词放 {@code .md} 而不写死在 Java 里</b>：提示词是业务语义的载体、
     * 调整频繁，调整不该要求改 Java 重新编译；放 {@code prompts/*.md} 还能用 {@code git diff}
     * 直接看出"提示词改了什么"。
     *
     * <p>⚠️ 资源不存在时<b>启动即失败</b>（见 {@code RagConfig}）：若静默用空约束，
     * 模型会失去"禁止编造来源"的约束，表现是"回答照常通顺、只是偶尔编个文件名"。
     */
    private String constraintResource = "classpath:prompts/rag-answer-prompt.md";

    /**
     * 拼装时是否注入回答约束提示词。
     *
     * <p>默认 true。关掉它的唯一用途是<b>对照实验</b>：同一批问题、同一份片段，开/关各跑一次，
     * 才能说明"约束起了多少作用"。不要用它"临时让模型自由发挥" —— 那是在拿掉唯一的兜底
     * （阈值挡不住无关提问）。
     */
    private boolean includeConstraint = true;
}
