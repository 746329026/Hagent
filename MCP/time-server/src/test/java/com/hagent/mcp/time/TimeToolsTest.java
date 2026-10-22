package com.hagent.mcp.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间工具的<b>纯逻辑</b>测试。
 *
 * <p>它不启动 Spring、不碰 MCP 协议 —— 那些由端到端握手验证（见 HANDOFF 的验收清单：
 * 用一条 stdio 握手脚本发 {@code initialize / tools/list / tools/call}）。
 *
 * <p>★ 这里盯的是两件"错了也不会报错"的事：
 * <ol>
 *   <li><b>默认时区</b>：省略参数时必须落到 Asia/Shanghai，而不是 JVM 默认时区
 *       （换台机器/换个容器就变成 UTC，时间差 8 小时，而没有任何提示）；</li>
 *   <li><b>非法时区不抛异常</b>：抛异常会被框架翻成错误文本喂给模型，
 *       而"时区名写错"是可纠正的输入问题 —— 返回一句建议，模型能自己改。</li>
 * </ol>
 */
class TimeToolsTest {

    private final TimeTools tools = new TimeTools();

    @Test
    @DisplayName("★ 省略时区 → 用 Asia/Shanghai，且输出里带中文星期")
    void defaultsToShanghaiAndIncludesWeekday() {
        String text = tools.getCurrentTime(null);

        assertThat(text)
                .as("默认时区必须是 Asia/Shanghai —— 依赖 JVM 默认时区的话，"
                        + "换台机器就会差 8 小时且毫无提示")
                .contains("Asia/Shanghai")
                .contains("当前时间：")
                .containsPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} 星期[一二三四五六日天]");
    }

    @Test
    @DisplayName("★ 非法时区 → 返回可纠正的说明，不抛异常")
    void invalidZoneReturnsAdvice() {
        String text = tools.getCurrentTime("Mars/Olympus");

        assertThat(text)
                .as("抛异常会被框架转成错误文本，模型只能放弃；返回建议它还能改对")
                .startsWith("无法识别时区")
                .contains("Asia/Shanghai");
    }

    @Test
    @DisplayName("显式时区生效（UTC 与上海相差 8 小时，格式都合法）")
    void explicitZoneIsRespected() {
        String utc = tools.getCurrentTime("UTC");

        assertThat(utc).contains("UTC").contains("时区 UTC");
    }
}
