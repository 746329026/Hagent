package com.hagent.config;

import com.hagent.memory.longterm.LongTermMemorySettings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 长期记忆的配置，绑定 {@code hagent.memory.long-term.*}。
 *
 * <p>★ 为什么在启动期校验（{@link #afterPropertiesSet()}）：这个键曾经"配了没人读" ——
 * 不报错、不影响行为，只是安静地骗人（改了它以为记忆换地方了，其实什么都没发生）。
 *
 * <p>★ 为什么三个上限放在配置里：它们决定的是<b>成本</b>（索引每轮都注入 prompt）与
 * <b>安全性</b>（单条记忆多大），按项目一贯做法，能算成本/可调的数字一律外置。
 *
 * <p>★ 为什么实现的是 {@link LongTermMemorySettings}：{@code memory} 包不能反向依赖
 * {@code config}（否则是包级环），所以"实现需要哪些参数"由领域声明成端口、本类去实现。
 * 方法全由 {@code @Getter} 生成，这里不新增方法。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.memory.long-term")
public class LongTermMemoryProperties implements InitializingBean, LongTermMemorySettings {

    /** 记忆根目录。实际路径是 {@code {dir}/{userId}/}。 */
    private String dir = "tmp/agent-memory";

    /**
     * 注入 prompt 的<b>索引块</b>最大字符数（默认 1000）。
     *
     * <p><b>为什么必须有上限</b>：索引是<b>每一轮</b>都注入 system 的，直接吃掉会话历史的
     * 预算（{@code chat-max-tokens=5000}）。1000 字 ≈ 15%–20%，再大会把"最近几轮对话"挤掉 ——
     * 那不是省钱问题，是<b>模型忘掉上下文</b>。
     *
     * <p>超限时<b>拒绝写新记忆</b>（而不是静默淘汰最旧的）：悄悄丢用户数据更糟。
     */
    private int indexMaxChars = 1000;

    /**
     * 单条记忆正文的最大字符数（默认 2000）。
     *
     * <p>正文只在模型调 {@code readMemory} 时才进上下文，<b>不占</b> prompt 预算，
     * 所以这个值的意义是"防止一条记忆大到荒谬"，不是控制成本。
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
