package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 长期记忆的配置，绑定 {@code hagent.memory.long-term.*}。
 *
 * <h2>★ 阶段 11 之前，这个配置键是"死的"</h2>
 *
 * <p>{@code application.yml} 里从阶段 5 就写着
 * <pre>
 *   hagent:
 *     memory:
 *       long-term:
 *         dir: "${user.dir}/tmp/agent-memory"
 * </pre>
 * 但<b>没有任何 Java 字段绑定它</b>（全项目搜不到 {@code longTerm}）——
 * 一个"配了没人读"的键。它不报错、不影响任何行为，只是安静地骗人：
 * 改了它、以为记忆换地方了，其实什么都没发生。
 *
 * <p>这正是本项目记过的坑 #12（"写了不等于生效"）。阶段 11 把它绑定上，
 * 并在 {@link #afterPropertiesSet()} 里做启动校验。
 *
 * <h2>为什么三个上限放在配置里</h2>
 *
 * <p>它们决定的是<b>成本</b>（索引每轮都注入 prompt）与<b>安全性</b>（单条记忆多大），
 * 而不是"代码结构"。按项目一贯的做法，能算成本/可调的数字一律外置。
 *
 * <h2>★ 为什么它在 {@code com.hagent.config}</h2>
 *
 * <p>协议第 13 条：项目里 8 个 {@code @ConfigurationProperties} 类有 7 个在
 * {@code com.hagent.config}，配置类的既定归属就是那里。本类最初放进了
 * {@code com.hagent.memory.longterm}（"它讲记忆，就跟记忆走"）——
 * 那正是踩坑 #25 的同一种错：**判据看的是"它是什么种类"，不是"它讲什么业务"**。
 * 现已挪回，并由 {@code PackagePlacementTest} 守着。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.memory.long-term")
public class LongTermMemoryProperties implements InitializingBean {

    /** 记忆根目录。实际路径是 {@code {dir}/{userId}/}。 */
    private String dir = "tmp/agent-memory";

    /**
     * 注入 prompt 的<b>索引块</b>最大字符数（默认 1000）。
     *
     * <p><b>为什么必须有上限</b>：索引是<b>每一轮</b>都注入 system 的，
     * 它直接吃掉会话历史的预算（{@code conversation-max-tokens=5000}）。
     * 1000 字 ≈ 15%–20%，再大就会把"最近几轮对话"挤掉 ——
     * 而那不是省钱问题，是<b>模型忘掉上下文</b>。
     *
     * <p>超限时的行为是<b>拒绝写新记忆</b>（而不是静默淘汰最旧的）：
     * 悄悄丢用户数据比"让模型自己整理一下"糟得多。
     */
    private int indexMaxChars = 1000;

    /**
     * 单条记忆正文的最大字符数（默认 2000）。
     *
     * <p>它<b>不占</b> prompt 预算（正文只在模型调 {@code readMemory} 时才进上下文），
     * 所以这个值的意义是"防止一条记忆大到荒谬"，而不是控制成本。
     */
    private int contentMaxChars = 2000;

    /** 记忆标题的最大字符数（默认 60）。标题会进索引，所以必须短。 */
    private int titleMaxChars = 60;

    @Override
    public void afterPropertiesSet() {
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "hagent.memory.long-term.dir 不能为空 —— 长期记忆需要一个可写的目录");
        }
        requirePositive("index-max-chars", indexMaxChars);
        requirePositive("content-max-chars", contentMaxChars);
        requirePositive("title-max-chars", titleMaxChars);
    }

    private void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "hagent.memory.long-term." + key + " 必须为正数：" + value
                            + "（0 或负数会让记忆功能无法工作：一个字符都写不进去）");
        }
    }
}
