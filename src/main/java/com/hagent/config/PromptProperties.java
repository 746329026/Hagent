package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词拼装的可调参数，绑定 {@code hagent.rag.prompt.*}。
 *
 * <p>把提示词资源路径做成配置项，理由与项目其它配置一致：<b>能被环境改变的 = 配置项</b>。
 * 这里的"环境差异"是真实存在的 —— 排查"回答质量问题是检索的锅还是提示词的锅"时，
 * 需要一个开关把约束整段换掉（或摘掉），而不是改代码重新编译。
 *
 * <p>★ 包归属（原文写错了，本轮核对源码时发现并修正）：本类<b>在 {@code config} 包</b>。
 * 原文写着"放在 {@code prompt} 包而不是 {@code config} 包"，而那个包<b>并不存在</b> ——
 * 这正对应协议第 12 条规则 4（名字/注释不得撒谎）：注释描述了一个不存在的结构。
 * 实际的判据是协议第 12 条规则 5：{@code *Properties} 这一类有既定归属 {@code config}
 * （实测本项目 7/10 的 Properties 都在这里），所以跟种类走，不跟领域走。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.prompt")
public class PromptProperties {

    /**
     * 回答约束提示词的资源位置。
     *
     * <p>默认指向 {@code classpath:prompts/rag-answer-prompt.md}。
     *
     * <p><b>为什么提示词放在 {@code .md} 而不是写死在 Java 里</b>
     * （这个做法从阶段 1 沿用至今）：
     * <ul>
     *   <li>提示词是<b>业务语义的载体</b>，调整频繁，而调整不该要求改 Java、重新编译；</li>
     *   <li>放在 {@code prompts/*.md} 可以用 {@code git diff} 直接看出"提示词改了什么" ——
     *       这是回答质量变化时最重要的一条线索。</li>
     * </ul>
     *
     * <p>⚠️ 资源不存在时<b>启动即失败</b>（见 {@code RagConfig}）：
     * 若静默用一个空的约束，模型会失去"禁止编造来源"的约束，
     * 而表现是"回答看起来正常、只是偶尔编个文件名"—— 没人会往提示词缺失上想。
     */
    private String constraintResource = "classpath:prompts/rag-answer-prompt.md";

    /**
     * 拼装时是否注入回答约束提示词。
     *
     * <p>默认 true。关掉它的唯一用途是<b>对照实验</b>：
     * 同一批问题、同一份片段，开/关约束各跑一次，才能说明"约束到底起了多少作用"。
     * 不要用它来"临时让模型自由发挥" —— 那是在拿掉唯一的兜底
     * （阈值挡不住无关提问，见 {@code RetrievalProperties} 的注释）。
     */
    private boolean includeConstraint = true;
}
