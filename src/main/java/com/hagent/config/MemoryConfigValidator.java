package com.hagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Set;

/**
 * 记忆配置的**早期校验** —— 在环境准备完成后、任何 Bean 创建之前执行。
 *
 * <p>★ 为什么必须是 {@link EnvironmentPostProcessor}：这个校验被实现过三次，前两次都没生效，
 * 原因是同一条 —— <b>"用依赖关系保证执行顺序"只在那个依赖真能被解析到时才成立</b>。
 * 而这里要校验的恰恰是"记忆 Bean 到底造不造得出来"：把校验挂在记忆 Bean 的消费者身上，
 * 顺序保证就建立在一个可能根本不存在的前提上（校验还没轮到，容器已因缺 Bean 失败）。
 * EnvironmentPostProcessor 是 Spring Boot 真正意义上"最早的机会"，且<b>不依赖任何 Bean</b>。
 *
 * <p>★ 校验<b>两个</b>键：{@code record-store} 与 {@code context-cache} 是两个角色，
 * 不是一份数据的三种存法 —— 只校验其中一个，配错就会重新变成静默问题。
 * 已废弃的旧键 {@code type} 配了要<b>明确报错</b>，而不是被静默忽略。
 *
 * <p>★ 包名用的是 Spring Boot 4 的 {@code org.springframework.boot.EnvironmentPostProcessor}
 * （旧包自 4.0 起 {@code @Deprecated(forRemoval = true)}，只产生警告、不编译失败）。
 * 改包名时 {@code META-INF/spring.factories} 的 key 必须同步改，漏一处会静默失效。
 */
@Slf4j
public class MemoryConfigValidator implements EnvironmentPostProcessor, Ordered {
    private static final String RECORD_PROPERTY = "hagent.memory.chat.record-store";
    private static final String CONTEXT_PROPERTY = "hagent.memory.chat.context-cache";

    /** 已废弃的旧键 —— 配了它要明确报错，而不是被静默忽略。 */
    private static final String LEGACY_PROPERTY = "hagent.memory.chat.type";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // ★ 旧键必须明确报错：静默忽略会让"改了配置却没生效"重新出现
        String legacy = environment.getProperty(LEGACY_PROPERTY);
        if (legacy != null && !legacy.isBlank()) {
            throw new IllegalStateException(String.format(
                    "%s=%s 已废弃（阶段 7 起会话记忆分成两层，不再是三选一）。"
                            + "请改为两个键：%s（%s）与 %s（%s）。"
                            + "【为什么报错而不是兼容】：两套语义并存会让后来者不知道该看哪个，"
                            + "而本项目尚未上线、没有外部调用方，兼容层只会成为永久负担。",
                    LEGACY_PROPERTY, legacy,
                    RECORD_PROPERTY, MemoryProperties.VALID_RECORD_STORES,
                    CONTEXT_PROPERTY, MemoryProperties.VALID_CONTEXT_CACHES));
        }

        requireOneOf(environment, RECORD_PROPERTY, MemoryProperties.VALID_RECORD_STORES);
        requireOneOf(environment, CONTEXT_PROPERTY, MemoryProperties.VALID_CONTEXT_CACHES);

        log.debug("[记忆] 配置预检通过：{}={} {}={}",
                RECORD_PROPERTY, environment.getProperty(RECORD_PROPERTY),
                CONTEXT_PROPERTY, environment.getProperty(CONTEXT_PROPERTY));
    }

    private void requireOneOf(ConfigurableEnvironment environment, String property, Set<String> valid) {
        String raw = environment.getProperty(property);
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (value.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "%s 不能为空。合法取值：%s", property, valid));
        }
        if (!valid.contains(value)) {
            throw new IllegalStateException(String.format(
                    "%s 取值非法：%s。合法取值：%s。"
                            + "【为什么直接失败而不是降级】：若静默用了别的实现，"
                            + "你会以为配置已生效，而数据其实存在别处"
                            + "（甚至只在内存里、重启即丢），且不会有任何报错。",
                    property, raw, valid));
        }
    }

    /**
     * ★ 顺序取"最后"（LOWEST_PRECEDENCE），而不是"最先"。
     *
     * <p>取 {@code HIGHEST_PRECEDENCE} 时本类<b>读不到配置值</b>、误报"不能为空"：
     * Spring Boot 加载 {@code application.yml} 这件事<b>本身</b>也是由一个
     * EnvironmentPostProcessor（{@code ConfigDataEnvironmentPostProcessor}）完成的。
     *
     * <p>正确时机是"**等配置加载完、但仍在 Bean 创建之前**" —— 取最大值即可保证在所有
     * 内置 post-processor 之后执行。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
