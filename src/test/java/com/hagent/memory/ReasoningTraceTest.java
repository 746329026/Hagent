package com.hagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReasoningTrace} 的格式测试 —— 写的一侧与读的一侧必须严格对称。
 *
 * <p>标记语法是前后两半共用的契约：写侧改了格式、读侧没跟上，症状是"历史里的思考忽然全变成第 1 步"
 * 或"整段思考消失"，而且不会报错。所以这里既测绝对格式（防漂移），也测往返（防两侧不对称）。
 */
class ReasoningTraceTest {

    private static ReasoningTrace.Section section(int step, List<String> tools, String text) {
        return new ReasoningTrace.Section(step, tools, text);
    }

    @Test
    @DisplayName("★ 单步（没有工具）→ 纯文本，一个标记都没有")
    void singleStepHasNoMarker() {
        String rendered = ReasoningTrace.render(List.of(section(1, List.of(), "只想了这一下")));

        assertThat(rendered)
                .as("绝大多数回合都是单步，落库必须是干净的一段话")
                .isEqualTo("只想了这一下");
    }

    @Test
    @DisplayName("★ 两步 → 第 2 步带标记，且工具名跟在标记后面")
    void secondStepCarriesMarkerAndTools() {
        String rendered = ReasoningTrace.render(List.of(
                section(1, List.of(), "先查知识库"),
                section(2, List.of("retrieveKnowledge"), "查到了，直接答")));

        assertThat(rendered).isEqualTo(
                "先查知识库\n\n@@step:2 tool:retrieveKnowledge\n查到了，直接答");
    }

    @Test
    @DisplayName("一步里调多个工具 → 工具名用逗号连接")
    void multipleToolsInOneMarker() {
        String rendered = ReasoningTrace.render(List.of(
                section(1, List.of(), "同时查两个"),
                section(2, List.of("a", "b"), "好了")));

        assertThat(rendered).contains("@@step:2 tool:a,b");
    }

    @Test
    @DisplayName("★ 正文为空但有工具名 → 标记必须保留（那一步确实发生了）")
    void emptyTextButHasToolsStillWritesMarker() {
        String rendered = ReasoningTrace.render(List.of(
                section(1, List.of(), null),
                section(2, List.of("getWeather"), "答完了")));

        assertThat(rendered)
                .as("模型可能不写思考就直接调工具。丢掉标记会让『上一步调了 getWeather』这个事实消失")
                .isEqualTo("@@step:2 tool:getWeather\n答完了");
    }

    @Test
    @DisplayName("没有任何内容 → null（不是空串）")
    void nothingToStoreReturnsNull() {
        assertThat(ReasoningTrace.render(List.of())).isNull();
        assertThat(ReasoningTrace.render(List.of(section(1, List.of(), "   ")))).isNull();
    }

    @Test
    @DisplayName("★★ 往返：render → parse 恒等")
    void renderThenParseRoundTrips() {
        // 第 1 段的 tools 刻意留空：格式契约规定"工具名挂在下一步的标记上"，第 1 步没有标记可挂。
        // 管线产生的数据永远是这个形状，所以这里按真实形状构造。
        List<ReasoningTrace.Section> sections = List.of(
                section(1, List.of(), "用户问年假，我先查知识库"),
                section(2, List.of("retrieveKnowledge"), "查到的是满一年 5 天，再确认不满一年怎么算"),
                section(3, List.of("retrieveKnowledge"), "算好了：8 个月按比例"));

        assertThat(ReasoningTrace.parse(ReasoningTrace.render(sections)))
                .as("★ 写与读必须对称 —— 不对称时表现是『历史里的思考错位/丢失』，且不报错")
                .isEqualTo(sections);
    }

    @Test
    @DisplayName("★ 第 1 段的 tools 被忽略（第 1 步没有标记可挂）—— 契约的一部分")
    void firstSectionToolsAreIgnored() {
        assertThat(ReasoningTrace.render(List.of(section(1, List.of("getWeather"), "想了"))))
                .as("这条不是缺陷而是格式契约：管线永远不会产出这种形状"
                        + "（第 1 步的 tools 恒为空），断言它只是为了把契约钉住")
                .isEqualTo("想了");
    }

    @Test
    @DisplayName("没有标记的文本 → 一个 step=1 的分段（单步回合存下来的样子）")
    void plainTextParsesAsSingleSection() {
        assertThat(ReasoningTrace.parse("就是一段想法"))
                .containsExactly(section(1, List.of(), "就是一段想法"));
    }

    @Test
    @DisplayName("null / 空白 → 空列表（不返回 null，前端可以直接遍历）")
    void blankParsesToEmptyList() {
        assertThat(ReasoningTrace.parse(null)).isEmpty();
        assertThat(ReasoningTrace.parse("  \n ")).isEmpty();
    }

    @Test
    @DisplayName("★ 标记只在【行首】生效 —— 正文中间出现的同形串不会被切开")
    void markerOnlyAppliesAtLineStart() {
        String text = "我在想 @@step:9 这个词是什么意思\n还是没想明白";

        assertThat(ReasoningTrace.parse(text))
                .as("若正文中间的 @@step: 也算标记，一段真实思考会被劈成两半 —— 而且不报错")
                .hasSize(1);
    }

    @Test
    @DisplayName("不认识的标记（步号非法）被当作正文保留，不丢内容")
    void unknownMarkerKeptAsText() {
        List<ReasoningTrace.Section> sections = ReasoningTrace.parse("开头\n@@step:abc\n后面");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).text())
                .as("宁多一行看不懂的文字，也不要让一段真实思考消失")
                .contains("@@step:abc");
    }

    @Test
    @DisplayName("步号连跳（1 → 3）也能解析，不补空段")
    void skippedStepNumbersAreTolerated() {
        assertThat(ReasoningTrace.parse("一\n\n@@step:3\n三"))
                .extracting(ReasoningTrace.Section::step)
                .containsExactly(1, 3);
    }
}
