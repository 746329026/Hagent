package com.hagent.mcp.time;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 时间工具 —— 本 server 的<b>全部</b>能力（"每个 server 只干一件事"）。
 *
 * <h2>★ 它解决什么问题</h2>
 *
 * <p>模型<b>不知道今天是几号</b>，而办公场景大量问题与相对日期有关
 * （"年假要提前几天申请"、"上个月的报销截止了吗"）。没有基准时间它只能猜 ——
 * 而<b>猜错不会报错</b>，答案看起来一样通顺。产出去向：模型上下文 → 最终回答，
 * 同时进 {@code chat_tool_invocation} 审计表。
 *
 * <h2>★ 为什么它挂在 MCP server 上，而不是主应用里再写一个 Java 工具类</h2>
 *
 * <p>它<b>不需要知道任何业务数据</b>（不碰知识库、不碰会话、不碰记忆），所以天然属于
 * "外部工具"，可以用另一种部署形态交付 —— 主应用<b>一行 Java 都不用为它改</b>，
 * 只在 {@code application-mcp.yml} 里挂一条连接。换成 Python / Node 写的时间服务，
 * 客户端<b>还是这一行配置</b>。
 *
 * <h2>★ 为什么返回一句话，而不是结构化 JSON</h2>
 *
 * <p>返回值是<b>给模型看的</b>。日期最不容易被弄错的形式是"已按人类习惯排好的一行字"
 * （含星期与时区），而不是让它自己解析 {@code epochMillis} —— 结构化数据有价值的地方是
 * 我们自己的代码要消费，而这里唯一的消费者是模型。
 */
@Service
public class TimeTools {

    /** 默认时区：本项目是中文办公场景，默认北京时间。 */
    private static final String DEFAULT_ZONE = "Asia/Shanghai";

    /** 输出格式：{@code 2026-10-21 18:45:03 星期三} —— 中文星期，模型与人都好读。 */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA);

    /**
     * 获取当前的日期与时间。
     *
     * @param zone IANA 时区（如 {@code Asia/Shanghai}、{@code America/New_York}）。
     *             <b>可省略</b>：省略时用 {@link #DEFAULT_ZONE}。
     * @return 一行人类可读的时间；时区非法时返回一句说明（<b>不抛异常</b>）
     */
    @McpTool(name = "getCurrentTime",
            description = """
                    获取当前的日期与时间（含星期与时区）。
                    凡涉及"今天/现在/还有几天/提前几天/是否过期"这类推算，先调用它拿到基准时间，
                    不要凭印象假设今天是哪一天。""")
    public String getCurrentTime(
            @McpToolParam(required = false,
                    description = "IANA 时区，例如 Asia/Shanghai；不传默认 Asia/Shanghai")
            String zone) {

        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank()
                    ? ZoneId.of(DEFAULT_ZONE)
                    : ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            // ★ 不抛异常：工具抛异常会被框架翻成一段错误文本喂给模型，
            //   而这里"时区写错了"是一个可解释的业务输入问题 —— 直接告诉它合法格式，
            //   模型可以自己改正后重试（把异常变成建议，而不是把失败变成终点）。
            return "无法识别时区：" + zone + "。请使用 IANA 时区名，例如 Asia/Shanghai、UTC、America/New_York。";
        }

        ZonedDateTime now = ZonedDateTime.now(zoneId);
        return "当前时间：" + now.format(FORMAT)
                + "（时区 " + zoneId.getId()
                + "，UTC" + now.getOffset() + "）";
    }
}
