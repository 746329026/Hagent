package com.hagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>包的归属守卫</b> —— 配置类必须住在 {@code com.hagent.config}。
 *
 * <h2>★ 为什么要有一个"测目录结构"的测试</h2>
 *
 * <p>因为同一个操作性错误已经犯过<b>两次</b>：
 * <ol>
 *   <li>踩坑 #25（阶段 8）：新类按"它讲什么业务"放，而不是按"它是什么种类"放；</li>
 *   <li>阶段 11：{@code LongTermMemoryProperties} / {@code LongTermMemoryConfig}
 *       又被放进了 {@code memory.longterm}（"它装配记忆，就跟记忆走"）—— 而
 *       {@code config/} 里已经有 7 个 {@code *Properties} 与 4 个 {@code *Config}。</li>
 * </ol>
 * 按协议第 6 条：**重复 ≥2 次必须机制化**，不许只口头提醒。这个测试就是那次机制化 ——
 * 下一次有人（包括我）把配置类放错包，它会**立刻红**，而且报错直接说清该放哪。
 *
 * <h2>它检查什么</h2>
 *
 * <pre>
 *   ① 类名以 Properties 结尾  → 必须在 com.hagent.config
 *   ② 行首有 @Configuration    → 必须在 com.hagent.config
 * </pre>
 *
 * <p>两条规则都带<b>已记录的例外</b>（见下面两个 Map）。例外不是"放过"，而是
 * "记下来、且长期可见"：每个例外都必须<b>仍然存在</b>，否则说明它被删了却没从名单里去掉 ——
 * 那样名单会慢慢变成一张"什么都不拦"的废纸。
 *
 * <h2>为什么不检查 Service / Store / Tool 的归属</h2>
 *
 * <p>那些类的归属判据是"能力域"（{@code memory}/{@code tool}/{@code retrieval}），
 * 边界本来就是模糊的（一个类同时属于两个域时怎么放？）——
 * 这种规则写进测试只会逼着后来者加一堆例外，反而降低可信度。
 * <b>只对"有唯一正确归属"的类别设卡</b>：配置类就是这种。
 */
class PackagePlacementTest {

    /** 源码根。surefire 的工作目录是项目根，所以这个相对路径在测试里成立。 */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/hagent");

    private static final String CONFIG_PACKAGE = "com.hagent.config";

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    /**
     * 行首的 {@code @Configuration}。
     *
     * <p>★ 允许<b>全限定写法</b>（{@code @org.springframework.context.annotation.Configuration}）：
     * 第一版只匹配裸 {@code @Configuration}，而植入缺陷时正好写成了全限定形式 →
     * **守卫没抓到**。这不是假想的边角：临时脚本、生成代码里全限定写法很常见。
     * 所以这里用 {@code (?:[\w.]+\.)?} 同时接受两种写法。
     */
    private static final Pattern ANNOTATION_LINE =
            Pattern.compile("(?m)^\\s*@(?:[\\w.]+\\.)?Configuration\\b");

    /**
     * 已记录的例外：全限定名 → 理由。
     *
     * <p>它们都是"配置类"，但搬迁会牵动非本阶段的改动（{@code MemoryProperties} 有十几处 import）。
     * 记录在此，等一次专门的"包归属整理"再动；**新增的类不许再加进这个名单**。
     */
    private static final Map<String, String> PROPERTIES_EXCEPTIONS = exceptions(
            "com.hagent.memory.MemoryProperties",
            "绑定在 MemoryConfig 里，而 MemoryConfig 自己也在 memory 包；搬迁要改十几处 import，"
                    + "属另一次独立改动");

    private static final Map<String, String> CONFIG_EXCEPTIONS = exceptions(
            "com.hagent.memory.MemoryConfig",
            "阶段 7 落地时就放在 memory 包（它装配的四个 Bean 全是记忆层内部件）；搬迁同上",
            "com.hagent.tool.ToolRegistration",
            "名字与职责都指向「工具注册表」，且是工具最小权限过滤的唯一改动点");

    // ========================================================================

    @Test
    @DisplayName("★★ 所有 *Properties 必须住在 com.hagent.config")
    void allPropertiesLiveInConfigPackage() {
        List<String> offenders = scan(name -> name.endsWith("Properties"), PROPERTIES_EXCEPTIONS);

        assertThat(offenders)
                .as("""
                        *Properties 类的既定归属是 com.hagent.config ——
                        项目里 8 个已有 7 个在那里（唯一例外见白名单）。
                        【怎么修】把文件挪到 src/main/java/com/hagent/config/，并改它的 package 行。
                        判据：**看它是什么种类，不看它讲什么业务**（踩坑 #25）。""")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 所有 @Configuration 类必须住在 com.hagent.config")
    void allConfigurationClassesLiveInConfigPackage() {
        List<String> offenders = scan(name -> false, CONFIG_EXCEPTIONS, /* byAnnotation */ true);

        assertThat(offenders)
                .as("""
                        装配类一律进 com.hagent.config（那里已有 RagConfig / WebConfig /
                        VectorStoreConfig / BusinessDataSourceConfig / AiConfig /
                        LongTermMemoryConfig）。
                        【怎么修】挪文件 + 改 package 行；Bean 会被组件扫描照常装配。""")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 白名单里的类必须仍然存在 —— 否则它变成一张什么也不拦的废纸")
    void exceptionListsDoNotRot() throws IOException {
        Set<String> existing = scanFullyQualifiedNames();

        List<String> stale = new ArrayList<>();
        PROPERTIES_EXCEPTIONS.keySet().stream()
                .filter(name -> !existing.contains(name))
                .forEach(name -> stale.add("Properties 例外已不存在：" + name));
        CONFIG_EXCEPTIONS.keySet().stream()
                .filter(name -> !existing.contains(name))
                .forEach(name -> stale.add("Config 例外已不存在：" + name));

        assertThat(stale)
                .as("被例外放行的类如果哪天被删/改名，必须从名单里去掉 —— "
                        + "否则这份名单会越长越大，最后什么都不拦")
                .isEmpty();
    }

    // ========================================================================
    // 扫描
    // ========================================================================

    private List<String> scan(NamePredicate predicate,
                              Map<String, String> exceptions) {
        return scan(predicate, exceptions, false);
    }

    /**
     * @param byAnnotation 为 true 时按"行首有 {@code @Configuration}"认类，而不是按类名
     */
    private List<String> scan(NamePredicate predicate,
                              Map<String, String> exceptions,
                              boolean byAnnotation) {
        assertThat(Files.isDirectory(SOURCE_ROOT))
                .as("找不到源码根 %s —— 这个测试依赖 surefire 在项目根运行。"
                        + "若工作目录变了，请修这个测试而不是删掉它", SOURCE_ROOT.toAbsolutePath())
                .isTrue();

        Map<String, String> offenders = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> {
                String text = readText(path);
                if (text == null) {
                    return;
                }
                boolean matches = byAnnotation
                        ? ANNOTATION_LINE.matcher(text).find()
                        : predicate.matches(simpleNameOf(path));
                if (!matches) {
                    return;
                }
                String packageName = packageOf(text);
                if (packageName == null) {
                    return;   // 没有 package 行（不该发生，交给别的检查）
                }
                String fqn = packageName + "." + simpleNameOf(path);
                if (CONFIG_PACKAGE.equals(packageName) || exceptions.containsKey(fqn)) {
                    return;
                }
                offenders.put(fqn, path.toString());
            });
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码目录失败：" + SOURCE_ROOT.toAbsolutePath(), e);
        }

        return offenders.keySet().stream()
                .map(fqn -> fqn + "（" + offenders.get(fqn) + "）")
                .toList();
    }

    private Set<String> scanFullyQualifiedNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = readText(path);
                String packageName = text == null ? null : packageOf(text);
                if (packageName != null) {
                    names.add(packageName + "." + simpleNameOf(path));
                }
            }
        }
        return names;
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读源码失败：" + path, e);
        }
    }

    private static String simpleNameOf(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String packageOf(String text) {
        Matcher matcher = PACKAGE_DECL.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> exceptions(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 按"类的简单名"判定的谓词 —— 只是一层可读性包装。 */
    @FunctionalInterface
    private interface NamePredicate {
        boolean matches(String simpleName);
    }
}
