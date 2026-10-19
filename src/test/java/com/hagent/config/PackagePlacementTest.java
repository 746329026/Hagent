package com.hagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>包的归属守卫</b> —— "有唯一正确归属"的那几类类，必须在对的包里。
 *
 * <h2>★ 为什么要有一个"测目录结构"的测试</h2>
 *
 * <p>因为同一个操作性错误犯过<b>两次</b>：
 * <ol>
 *   <li>踩坑 #25（阶段 8）：新类按"它讲什么业务"放，而不是按"它是什么种类"放；</li>
 *   <li>阶段 11：{@code LongTermMemoryProperties} / {@code LongTermMemoryConfig}
 *       又被放进了 {@code memory.longterm}（"它装配记忆，就跟记忆走"）。</li>
 * </ol>
 * 按协议第 6 条：**重复 ≥2 次必须机制化**，不许只口头提醒。
 *
 * <h2>它检查什么（全部规则，<b>零例外</b>）</h2>
 *
 * <pre>
 *   *Properties      → com.hagent.config        （13/13）
 *   @Configuration   → com.hagent.config        （8/8）
 *   *Controller      → com.hagent.controller    （3/3）
 *   *Service         → com.hagent.app           （6/6）
 *   *Tool / *Tools   → com.hagent.tool          （2/2）
 * </pre>
 *
 * <p><b>为什么这些类别可以设卡</b>：它们的归属是<b>唯一</b>的 ——
 * "装配"只有一个家、"控制器"只有一个家。
 * 而 {@code *Store} / {@code *Builder} / {@code *Codec} 这类是<b>能力域</b>，
 * 边界天然模糊（一个类同时属于两个域时怎么放？），写进测试只会逼人加一堆例外，
 * 反而不如不写。**只对"有唯一正确答案"的事设卡**，是让这条规则长期有效的前提。
 *
 * <h2>★ 这道守卫自己也被验证过"会红"（踩坑 #27 的纪律）</h2>
 *
 * <pre>
 *   植入 2 个违规类（memory.longterm 下的 TempWrongPackage*）
 *     → 2 条失败 → 删掉后绿
 *   ★ 植入时还发现第一版漏了【全限定注解写法】
 *     （{@code @org.springframework.context.annotation.Configuration}）→ 已修
 * </pre>
 */
class PackagePlacementTest {

    /** 源码根。surefire 的工作目录是项目根，所以这个相对路径在测试里成立。 */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/hagent");

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    /**
     * 行首的 {@code @Configuration}。
     *
     * <p>★ 允许<b>全限定写法</b>：第一版只匹配裸 {@code @Configuration}，
     * 而植入缺陷时正好写成了全限定形式 → **守卫没抓到**。临时脚本、生成代码里这种写法很常见。
     */
    private static final Pattern CONFIGURATION_ANNOTATION =
            Pattern.compile("(?m)^\\s*@(?:[\\w.]+\\.)?Configuration\\b");

    // ========================================================================
    // 规则
    // ========================================================================

    @Test
    @DisplayName("★★ 配置类（*Properties 与 @Configuration）必须住在 com.hagent.config")
    void configurationClassesLiveInConfigPackage() {
        List<String> offenders = new ArrayList<>();
        offenders.addAll(byName("Properties", "com.hagent.config"));
        offenders.addAll(byAnnotation(CONFIGURATION_ANNOTATION, "com.hagent.config"));

        assertThat(offenders)
                .as("装配与配置的唯一归属是 com.hagent.config（那里已有 13 个 *Properties 与 8 个 @Configuration）。"
                        + "【判据】看它是什么种类，不看它讲什么业务（踩坑 #25）。"
                        + "【怎么修】挪文件 + 改 package 行；Bean 靠组件扫描照常装配")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 控制器必须住在 com.hagent.controller")
    void controllersLiveInControllerPackage() {
        assertThat(byName("Controller", "com.hagent.controller"))
                .as("HTTP 层只有一个家：com.hagent.controller")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 应用服务必须住在 com.hagent.app")
    void servicesLiveInAppPackage() {
        assertThat(byName("Service", "com.hagent.app"))
                .as("app 层 = 编排与用例；*Service 的唯一归属是 com.hagent.app")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 工具类必须住在 com.hagent.tool")
    void toolsLiveInToolPackage() {
        List<String> offenders = new ArrayList<>(byName("Tool", "com.hagent.tool"));
        offenders.addAll(byName("Tools", "com.hagent.tool"));

        assertThat(offenders)
                .as("模型可调用的工具只有一个家：com.hagent.tool（且全部由 ToolRegistration 装配）")
                .isEmpty();
    }

    // ========================================================================
    // 扫描
    // ========================================================================

    /** 按"类名后缀"判定，断言其包等于 {@code expectedPackage}。 */
    private List<String> byName(String suffix, String expectedPackage) {
        return scan(path -> simpleNameOf(path).endsWith(suffix), expectedPackage);
    }

    /** 按"行首注释"判定（用于 {@code @Configuration} 这种名字不带后缀的）。 */
    private List<String> byAnnotation(Pattern annotation, String expectedPackage) {
        return scan(path -> {
            String text = readText(path);
            return annotation.matcher(text).find();
        }, expectedPackage);
    }

    private List<String> scan(java.util.function.Predicate<Path> matcher, String expectedPackage) {
        assertThat(Files.isDirectory(SOURCE_ROOT))
                .as("找不到源码根 %s —— 这个测试依赖 surefire 在项目根运行。"
                        + "若工作目录变了，请修这个测试而不是删掉它", SOURCE_ROOT.toAbsolutePath())
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (!matcher.test(path)) {
                    continue;
                }
                String text = readText(path);
                Matcher packageMatcher = PACKAGE_DECL.matcher(text);
                if (!packageMatcher.find()) {
                    continue;
                }
                String packageName = packageMatcher.group(1);
                if (!expectedPackage.equals(packageName)) {
                    offenders.add(packageName + "." + simpleNameOf(path)
                            + "（应为 " + expectedPackage + "）← " + path);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码目录失败：" + SOURCE_ROOT.toAbsolutePath(), e);
        }
        return offenders;
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
}
