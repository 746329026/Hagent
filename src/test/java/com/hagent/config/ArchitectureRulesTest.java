package com.hagent.config;


import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>架构守卫</b> —— 用<b>字节码</b>说话，补上 {@link PackagePlacementTest} 与手写扫描都看不见的盲区。
 *
 * <p>{@code PackagePlacementTest} 不够的两条实证：
 * <ol>
 *   <li><b>看不见全限定名引用</b>：{@code KnowledgeRetrievalTool:204,206} 直接用
 *       {@code com.hagent.agent.ToolEventSink} / {@code PipelineEvent} 而没 import ——
 *       import 级扫描漏掉它们，于是 {@code app ⇄ tool} 这条真实环不会被发现；</li>
 *   <li><b>看不见"常量内联"</b>：{@code WebConfig} 用了 {@code ChatController.ID}，但那是编译期常量，
 *       {@code javap -c} 显示它被内联成 {@code ldc "X-Conversation-Id"}、<b>零指令</b>引用
 *       {@code ChatController} ⇒ 这条边在字节码里<b>不存在</b>，任何字节码级守卫都看不见它。</li>
 * </ol>
 *
 * <p>⇒ 两道守卫<b>并存、各管一侧</b>：本类管"字节码级的真实依赖与环"，那边管"源码级的
 * import 与包归属"。任何一道被删掉，都会有一类错误重新变成"无人值守"。
 *
 * <p>包级环直接判 {@code beFreeOfCycles()}，<b>没有基线、没有例外</b>：环已清零，出现任何一个
 * 包级环构建直接红。曾用 {@code FreezingArchRule} 冻结基线，但基线的键是违规文本（改名 / 移动就
 * 变成"新违规"，实测误报 4 次），且一份"允许带病的台账"总会被人当成"再加一个环也可以"的许可 ——
 * 零例外比带理由的白名单可靠。
 */
class ArchitectureRulesTest {

    /**
     * 只导入<b>生产类</b>。
     *
     * <p>{@code DO_NOT_INCLUDE_TESTS} 不是可选项：跑测试时 {@code target/test-classes} 也在
     * classpath 上，而测试辅助类（{@code RecordingMemory} / {@code TestAudit} / {@code TestLongTermMemory} …）
     * 正好住在 {@code com.hagent.app} 下且引用很多包 —— 不排除它们，量出来的"依赖图"里
     * 大半是测试脚手架，守卫会说出完全错误的结论。
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hagent");

    /** 第一级切片：{@code com.hagent.app} / {@code com.hagent.config} / … 各自一个节点。 */
    private static final ArchRule NO_PACKAGE_CYCLES =
            slices().matching("com.hagent.(*)..").should().beFreeOfCycles()
                    .because("包级环会让『改一处要动两处』，且新代码只会照着旧形状抄"
                            + "（见 docs/架构问题.md 的复核结论）");

    /**
     * <b>先证明"看得见东西"，再断言"没有违规"</b>。
     *
     * <p>"失败"与"正常"同形的地方必须有正向断言：若导入因任何原因拿到 0 个类（classpath 变了、
     * {@code ImportOption} 写错、包名改了），{@code beFreeOfCycles()} 会<b>因为"没有类"而通过</b> ——
     * 一条永远绿的守卫，比没有守卫更糟。
     */
    @Test
    @DisplayName("★ 守卫自检：真的导入了生产类（否则'无环'会因空集而假绿）")
    void guardActuallySeesProductionClasses() {
        assertThat(PRODUCTION_CLASSES.size())
                .as("导入到的生产类数量。若为 0 或异常少，说明 classpath / ImportOption / 包名出了问题 —— "
                        + "此时下面的'无环'断言会因为空集而假绿")
                .isGreaterThan(90);
    }

    @Test
    @DisplayName("★★ 包级无环")
    void noPackageCycles() {
        NO_PACKAGE_CYCLES.check(PRODUCTION_CLASSES);
    }
}
