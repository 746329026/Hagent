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
 * <h2>★ 为什么必须是 EnvironmentPostProcessor（一段真实的踩坑）</h2>
 *
 * <p>这个校验被实现过三次，前两次都<b>没能生效</b>，值得完整记录：
 *
 * <p><b>第一次</b>：写在 {@code MemoryProperties.afterPropertiesSet()} 里。
 * 结果没执行 —— 因为配置不合法时，所有实现的 {@code @ConditionalOnProperty} 都不匹配，
 * 容器里没有 {@code ConversationMemory}，而 {@code AgentPipeline} 的构造器依赖它，
 * 在更早的阶段就创建失败了。
 *
 * <p><b>第二次</b>：把它做成一个 Bean，并让 {@code AgentPipeline} 依赖它，
 * 想用"依赖关系"保证它先执行。结果<b>仍然没执行</b> ——
 * 因为 {@code AgentPipeline} 的构造器参数里<b>同时</b>需要
 * {@code ConversationMemory}，Spring 解析参数时一旦走到那一个，
 * 发现"这个 Bean 不存在"就<b>直接抛错返回，不会再去创建别的参数</b>。
 *
 * <p>两次的教训是同一条，值得单独记住：<b>"用依赖关系保证执行顺序"只在那个依赖
 * 真能被解析到时才成立</b>。而这里要校验的恰恰是"记忆 Bean 到底造不造得出来" ——
 * 把校验挂在记忆 Bean 的消费者身上，顺序保证就建立在一个可能根本不存在的前提上：
 * 校验还没轮到执行，容器已经先因为缺 Bean 失败了。
 *
 * <p><b>第三次（本类）</b>：改用 {@link EnvironmentPostProcessor} ——
 * 它在 {@code Environment} 准备完成后、**任何 Bean 创建之前**运行。
 * 这是 Spring Boot 提供的、真正意义上"最早的机会"，
 * 也正因为如此，它<b>不依赖任何 Bean</b>：{@code AgentPipeline} /
 * {@code ConversationMemory} 能不能创建出来，与本类无关。
 *
 * <h2>★ 阶段 12：它从 {@code com.hagent.memory} 搬到了这里</h2>
 *
 * <p>它是"启动期配置校验"，一个<b>装配</b>期的东西，而且它<b>一个 memory 类型都不依赖</b>
 * （只读 {@code Environment} 与 {@link MemoryProperties} 的两个常量）。
 * 住在 {@code memory} 包时，它凭空构成了一条 {@code memory → config} 依赖 ——
 * 也就是一个包级环的一半。判据仍是"它是什么种类"，而不是"它讲什么业务"。
 *
 * <h2>★ 第四次修正：接口在 Spring Boot 4.0 换了包</h2>
 *
 * <p>上面写的是"第三次"，这次是<b>包名</b>的修正，与运行时机无关：
 * <pre>
 *   Spring Boot 3.x / 4.0 之前 : org.springframework.boot.env.EnvironmentPostProcessor
 *   Spring Boot 4.0 起（本类） : org.springframework.boot.EnvironmentPostProcessor
 * </pre>
 * 旧包名自 {@code 4.0.0} 起被标注 <b>{@code @Deprecated(forRemoval = true)}</b>。
 * 它现在<b>不会导致编译失败，只会产生弃用警告</b> —— 正因如此容易被忽略。
 *
 * <p>⚠️ <b>配套要改两处</b>，漏一处会静默失效：
 * <ol>
 *   <li>本类的 {@code import}；</li>
 *   <li>{@code META-INF/spring.factories} 里的 <b>key</b>（同样要用新包全名）。</li>
 * </ol>
 *
 * <h2>★ 阶段 7：从校验一个开关变成校验两个</h2>
 *
 * <p>{@code hagent.memory.chat.type}（三选一）已被
 * {@code record-store} 与 {@code context-cache} 取代 —— 因为那是<b>两个角色</b>，
 * 不是一份数据的三种存法。这里必须同步改：
 * <b>只校验旧的 {@code type} 键、或者只校验新的一个键，都会让"配置写错"重新变成静默问题。</b>
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
     * <p><b>这是踩坑之后的修正</b>：最初取 {@code HIGHEST_PRECEDENCE}（最先执行），
     * 结果本类<b>读不到配置值</b>，误报"不能为空"。
     *
     * <p>原因：Spring Boot 加载 {@code application.yml} 这件事，
     * <b>本身就是由一个 EnvironmentPostProcessor 完成的</b>
     * （{@code ConfigDataEnvironmentPostProcessor}）。
     * 如果我们在它之前运行，Environment 里自然还没有 YAML 里的属性。
     *
     * <p>所以正确顺序是"**等配置加载完、但仍在 Bean 创建之前**" ——
     * 取最大值即可保证在所有内置 post-processor 之后执行。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
