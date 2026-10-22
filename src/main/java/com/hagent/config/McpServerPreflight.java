package com.hagent.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * <b>MCP 连接的前置检查</b>（阶段 16）—— 在<b>任何 Bean 创建之前</b>确认
 * "配置里要拉起的 server jar 真的存在"。
 *
 * <h2>★ 为什么需要它（这条是实测出来的，不是假想）</h2>
 *
 * <p>MCP server 是<b>独立项目</b>，主应用的 {@code mvnw package} 不会构建它。
 * 所以"忘了先构建 server"是<b>常态</b>，而它现在的表现是：
 *
 * <pre>
 *   ... 20 秒后 ...
 *   Caused by: java.util.concurrent.TimeoutException:
 *       Did not observe any item or terminal signal within 20000ms in 'map'
 * </pre>
 *
 * <p>这句报错<b>一个字都没提"jar 不存在"</b>。真实原因藏在更早的一行
 * {@code STDERR Message received: Error: Unable to access jarfile ...} 里 ——
 * 而没有人会在 20 秒超时之后往回翻日志。
 *
 * <p>⇒ 这正是本项目反复在防的那类错误：<b>报错读起来像别的问题</b>。
 * 处置方式与 `MemoryConfigValidator` 一致：<b>能在启动期用一句话说清的，
 * 不要让它变成 20 秒后的一条超时</b>。
 *
 * <h2>★ 为什么放在 {@link EnvironmentPostProcessor} 而不是 Bean 里</h2>
 *
 * <p>因为要<b>抢在 MCP 客户端 Bean 之前</b>。放在 Bean 里（哪怕是 {@code @PostConstruct}）
 * 都已经太晚：客户端在容器 refresh 期间就会去连，失败了整条启动链以超时收场。
 *
 * <h2>★ 顺序为什么是 {@code LOWEST_PRECEDENCE}</h2>
 *
 * <p>与 {@code MemoryConfigValidator} 同样的理由（踩坑 #7）：
 * 加载 {@code application.yml} / {@code application-mcp.yml} 这件事<b>本身</b>
 * 也是由一个 EnvironmentPostProcessor 完成的；抢在它之前运行，读到的配置全是空的。
 */
public class McpServerPreflight implements EnvironmentPostProcessor, Ordered {

    /** 与 Spring AI 的 stdio 客户端配置前缀保持一致（键名来自其配置元数据，不是猜的）。 */
    private static final String CONNECTIONS_PREFIX = "spring.ai.mcp.client.stdio.connections";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // ★ 用 Binder 而不是 getProperty：连接名是**动态的**（time / excel / ...），
        //   没法预先知道要读哪个键。Binder 还负责解析 ${java.home} / ${user.dir} 这类占位符。
        Map<String, Object> connections = Binder.get(environment)
                .bind(CONNECTIONS_PREFIX, Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());

        // 没有 MCP 连接（未激活 mcp profile）是**正常状态**，不是错误
        for (Map.Entry<String, Object> entry : connections.entrySet()) {
            verifyConnection(entry.getKey(), entry.getValue());
        }
    }

    private void verifyConnection(String name, Object raw) {
        if (!(raw instanceof Map<?, ?> parameters)) {
            return;
        }
        List<String> args = argList(parameters.get("args"));
        // 只认 java -jar <path> 这一种形态（本项目两个 server 都是这个形态）
        int jarFlag = args.indexOf("-jar");
        if (jarFlag < 0 || jarFlag + 1 >= args.size()) {
            return;
        }
        String jar = args.get(jarFlag + 1);
        if (Files.isRegularFile(Path.of(jar))) {
            return;
        }
        throw new IllegalStateException(String.format("""
                MCP 连接 '%s' 要拉起的 server 不存在：
                  %s
                【为什么】MCP server 是**独立项目**，主应用的构建不会构建它 —— 先单独构建它：
                  %s
                【只想先关掉 MCP】启动时不要激活 mcp profile（用 --spring.profiles.active=local）。
                【为什么不让你等到超时】不拦的话，启动会在 20 秒后报
                  TimeoutException: Did not observe any item ... within 20000ms
                —— 那句话里没有一个字提到"jar 不存在"。
                """, name, jar, buildCommand(jar)));
    }

    /**
     * 从 jar 路径反推构建命令：{@code .../MCP/time-server/target/x.jar}
     * → {@code .\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package}。
     *
     * <p>★ 为什么不按连接名猜项目名：连接名是 {@code time}，项目目录却是
     * {@code time-server} —— 两套命名不该由检查代码去"约定"（约定会漂移）。
     * 路径里的 {@code /target/} 才是可靠线索：那是 Maven 的固定产物目录。
     */
    private static String buildCommand(String jar) {
        Path path = Path.of(jar);
        Path parent = path.getParent();
        if (parent != null && parent.getFileName() != null
                && "target".equals(parent.getFileName().toString()) && parent.getParent() != null) {
            String projectDir = parent.getParent().toString().replace('\\', '/');
            return ".\\mvnw.cmd -B -ntp -f " + projectDir + "/pom.xml package";
        }
        // 认不出项目目录时给通用说法，而不是编一个可能错的路径
        return "先构建提供该 jar 的项目（本项目的形状：.\\mvnw.cmd -B -ntp -f MCP/<server>/pom.xml package）";
    }

    /**
     * 把 {@code args} 归一化成列表。
     *
     * <p>★ <b>这里有一段实测得来的坑</b>：当绑定目标是 {@code Object} 时，
     * Spring 的 Binder 会把 {@code args[0] / args[1]} 绑成
     * <b>{@code Map{"0": ..., "1": ...}}</b>，而不是 {@code List}。
     * 我第一版代码只判了 {@code instanceof List}，于是"检查"静默地什么都没查 ——
     * 而它看起来完全正常（启动照走、20 秒后照样超时）。
     *
     * <p>所以这里两种形态都认，并<b>按数字键排序</b>还原顺序（Map 的迭代顺序不可依赖）。
     */
    private static List<String> argList(Object rawArgs) {
        if (rawArgs instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (rawArgs instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparingInt(entry -> indexOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getValue()))
                    .toList();
        }
        return List.of();
    }

    private static int indexOf(Object key) {
        try {
            return Integer.parseInt(String.valueOf(key));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
