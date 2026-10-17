package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具调用审计的配置项，绑定 {@code hagent.tool.audit.*}。
 *
 * <h2>为什么它需要一个"开关"，而不是总是开着</h2>
 *
 * <p>与其它配置不同，审计表的增长是<b>无界</b>的：一轮对话可能有 N 次工具调用，
 * 每次都要存下参数与完整结果（结果可能是几 KB 的制度文本）。
 * 高流量下它会成为库里增长最快的表。
 *
 * <p>所以给出一个明确的关停入口 —— 但<b>默认开启</b>：
 * 审计数据的价值在于"出事时能查"，而<b>它只能从现在开始攒</b>，
 * 事后无法补录。默认关掉等于默认放弃可追溯性。
 *
 * <p>★ 关掉之后的行为要清楚：工具照常执行、对话照常进行，
 * 只是<b>不再落库</b>。这一点在启动日志里会明确打出来 ——
 * 否则运维会以为"审计一直在跑"。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.tool.audit")
public class ToolAuditProperties {

    /**
     * 是否记录工具调用审计。
     *
     * <p>默认 {@code true}。关掉它的唯一合理场景是"这张表太大、需要临时止血"。
     */
    private boolean enabled = true;

    /**
     * 审计表名。
     *
     * <p>它会被拼进 SQL，因此只允许普通标识符（在实现类的构造器里校验）。
     */
    private String tableName = "chat_tool_invocation";
}
