package com.hagent.memory;

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
 * <p>这个校验被实现过两次，前两次都<b>没能生效</b>，值得完整记录：
 *
 * <p><b>第一次</b>：写在 {@code MemoryProperties.afterPropertiesSet()} 里。
 * 结果没执行 —— 因为配置 {@code type=mysql} 时，所有实现的
 * {@code @ConditionalOnProperty} 都不匹配，容器里没有 {@code ConversationMemory}，
 * 而 {@code AiConfig.chatClient} 依赖它，在更早的阶段就创建失败了。
 *
 * <p><b>第二次</b>：把它做成一个 Bean，并让 {@code chatClient} 依赖它，
 * 想用"依赖关系"保证它先执行。结果<b>仍然没执行</b> ——
 * 因为 {@code chatClient} 的构造器参数里<b>同时</b>需要
 * {@code ConversationMemory}，Spring 解析参数时第一个就发现"这个 Bean 不存在"，
 * <b>直接抛错返回，不会再去创建其它参数</b>。
 *
 * <p><b>第三次（本类）</b>：改用 {@link EnvironmentPostProcessor} ——
 * 它在 {@code Environment} 准备完成后、**任何 Bean 创建之前**运行。
 * 这是 Spring Boot 提供的、真正意义上"最早的机会"。
 *
 * <p>报错内容也随之从"找不到 Bean（不说原因）"变成
 * "mysql 尚未实现，请用 file 或 redis（并说明为什么不静默降级）"。
 *
 * <h2>★ 第四次修正：接口在 Spring Boot 4.0 换了包</h2>
 *
 * <p>上面写的是"第三次"，这次是<b>包名</b>的修正，与运行时机无关：
 * <pre>
 *   Spring Boot 3.x / 4.0 之前 : org.springframework.boot.env.EnvironmentPostProcessor
 *   Spring Boot 4.0 起（本类） : org.springframework.boot.EnvironmentPostProcessor
 * </pre>
 * 旧包名自 {@code 4.0.0} 起被标注 <b>{@code @Deprecated(forRemoval = true)}</b>，
 * 即"将在未来版本中删除"。它现在<b>不会导致编译失败，只会产生弃用警告</b> ——
 * 正因如此容易被忽略，直到某次升级 Boot 后突然编译不过。
 * 本项目选择现在就换到新包名，而不是等它爆。
 *
 * <p><b>两个接口的方法签名完全相同</b>（已用 {@code javap} 核对），
 * 所以这次修正<b>只改 import</b>，一行逻辑都没动。
 *
 * <p>⚠️ <b>配套要改两处</b>，漏一处会静默失效：
 * <ol>
 *   <li>本类的 {@code import}（上面）；</li>
 *   <li>{@code META-INF/spring.factories} 里的 <b>key</b> ——
 *       它同样要用新包全名。Spring Boot 4 的
 *       {@code SpringFactoriesEnvironmentPostProcessorsFactory} 虽然<b>仍兼容</b>旧 key
 *       （走一条标为 deprecated 的加载分支），但会给出警告，
 *       且该兼容分支会随旧接口一起被移除。</li>
 * </ol>
 *
 * <p><b>为什么不换成别的机制</b>：{@code EnvironmentPostProcessor} 本身<b>没有</b>被替代品取代 ——
 * 被弃用的是"旧的包名"，不是这个扩展点。它仍然是"Bean 创建之前最早介入"的唯一手段。
 *
 * <h2>为什么这个校验值得费这些周折</h2>
 *
 * <p>它防的是"**配置没生效却看起来正常**"：
 * 若配了 {@code type=mysql} 而实现不存在，而系统<b>静默降级</b>到某个默认实现，
 * 使用者会以为 MySQL 已生效，实际历史存在别处（甚至只在内存里，重启就丢）——
 * 而这一切不会有任何报错。这正是本项目全篇在防的一类问题。
 */
@Slf4j
public class MemoryConfigValidator implements EnvironmentPostProcessor, Ordered {

    /** 已提供实现的类型。阶段 7 提供 MySQL 实现后，把 "mysql" 加进来即可。 */
    private static final Set<String> IMPLEMENTED = Set.of("file", "redis");

    /** 所有合法取值（含尚未实现的）。 */
    private static final Set<String> VALID = Set.of("file", "redis", "mysql");

    private static final String PROPERTY = "hagent.memory.chat.type";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(PROPERTY);
        String type = raw == null ? "" : raw.trim().toLowerCase();

        if (type.isEmpty()) {
            throw new IllegalStateException(
                    PROPERTY + " 不能为空。合法取值：file / redis / mysql");
        }
        if (!VALID.contains(type)) {
            throw new IllegalStateException(
                    PROPERTY + " 取值非法：" + raw + "。合法取值：file / redis / mysql");
        }
        if (!IMPLEMENTED.contains(type)) {
            throw new IllegalStateException(String.format(
                    "%s=%s 目前还没有实现（已实现：%s）。"
                            + "MySQL 实现计划在阶段 7 提供（届时会有专门的数据表与 DDL）。"
                            + "在此之前请把它设为 file 或 redis。"
                            + "【为什么直接失败而不是降级】：若静默降级到别的实现，"
                            + "你会以为配置已生效，而历史其实存在别处"
                            + "（甚至只在内存里、重启即丢），且不会有任何报错。",
                    PROPERTY, raw, IMPLEMENTED));
        }
        log.debug("[记忆] 配置预检通过：{}={}", PROPERTY, type);
    }

    /**
     * ★ 顺序取"最后"（LOWEST_PRECEDENCE），而不是"最先"。
     *
     * <p><b>这是踩坑之后的修正</b>：最初取 {@code HIGHEST_PRECEDENCE}（最先执行），
     * 结果本类<b>读不到配置值</b>，误报"type 不能为空"。
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
