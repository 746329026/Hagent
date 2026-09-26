package com.hagent.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 会话记忆相关配置项，绑定 {@code hagent.memory.chat.*}。
 *
 * <p>这里集中了"实现选择"与"各项参数"——目的是让 <b>换实现只改一行配置</b>：
 * <pre>
 *   hagent.memory.chat.type: file | redis | mysql
 * </pre>
 *
 * <p><b>★ 实现了 {@link InitializingBean} 做启动自检</b>：
 * 配置写错（比如 {@code mysql} 还没实现、或者打错字）时
 * <b>在启动阶段就明确失败</b>，而不是让应用跑起来后"静默降级到某个默认实现" ——
 * 后者会让人以为"配置生效了"，实际数据存在了完全不同的地方。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.memory.chat")
public class MemoryProperties implements InitializingBean {

    /** 当前已提供实现的类型。阶段 7 提供 MySQL 实现后，这里会变为"已实现"。 */
    private static final Set<String> IMPLEMENTED_TYPES = Set.of("file", "redis");

    /** 所有合法取值（含尚未实现的 mysql）。 */
    private static final Set<String> VALID_TYPES = Set.of("file", "redis", "mysql");

    /**
     * 记忆实现类型。
     *
     * <p><b>合法取值</b>：{@code file} / {@code redis} / {@code mysql}。
     *
     * <p>★ 关于 {@code mysql}：本阶段（阶段 6）尚未实现，配置后<b>启动会明确失败</b>
     * 并提示原因，而不是静默降级到别的实现 ——
     * 因为"你以为在用 MySQL 存、其实历史在内存里、重启就丢"是最难排查的一类问题。
     * 阶段 7 会提供实现并放开这个值。
     */
    private String type = "file";

    // ==================== 预算参数 ====================

    /**
     * 会话模式的 token 预算（主限制，直接对应成本）。
     *
     * <p><b>口径</b>：只约束<b>历史消息</b>，不含 system 提示词与本次问题。
     */
    private int conversationMaxTokens = 5000;

    /** 会话模式的条数上限（兜底：防大量超短消息累积）。 */
    private int conversationMaxMessages = 30;

    /**
     * 工作流 / Agent 模式的 token 预算。
     *
     * <p>比会话模式大一个数量级，因为：多步循环中每一轮的工具返回结果
     * （如检索到的整篇制度文档）都会成为下一轮的输入，上下文自然更长。
     */
    private int workflowMaxTokens = 50000;

    /** 工作流模式的条数上限。 */
    private int workflowMaxMessages = 200;

    // ==================== 各实现的参数 ====================

    /** 文件实现的存储目录（type=file 时使用）。 */
    private String fileDir = "tmp/chat-memory";

    /**
     * Redis 实现的会话过期时间（秒），type=redis 时使用。
     *
     * <p><b>取值依据</b>：24 小时 —— 办公场景里"当天没聊完、第二天接着聊"很常见，
     * 而过期时间太长会让无用会话长期占内存。
     */
    private long redisTtlSeconds = 24 * 60 * 60L;

    /** Redis key 前缀，避免与其它应用的 key 冲突。 */
    private String redisKeyPrefix = "hagent:chat:memory:";

    // ==================== MySQL（阶段 7 使用）====================

    /**
     * MySQL 实现的表名（阶段 7 使用）。
     *
     * <p>现在就把配置项列出来，是为了<b>阶段 7 只加实现、不改配置结构</b>。
     * 这不属于"备而不用"——它是配置文档的一部分，且阶段 7 会真的消费它。
     */
    private String mysqlTableName = "chat_message";

    /**
     * 启动自检 —— 把"配置写错"与"配了没实现的实现"变成**明确失败**。
     *
     * <p>为什么必须在启动阶段拦：如果放过它，{@code @ConditionalOnProperty} 会让
     * 所有实现都不创建，容器里没有 {@code ConversationMemory}，
     * 而 Spring 可能装配一个内存兜底实现 →
     * <b>你以为在用 MySQL 存，实际历史在堆里、重启就丢，且不报错</b>。
     * 这类"配置没生效却看起来正常"的问题，正是本项目要系统性避免的。
     */
    @Override
    public void afterPropertiesSet() {
        if (type == null || type.isBlank()) {
            throw new IllegalStateException(
                    "hagent.memory.chat.type 不能为空。合法取值：" + VALID_TYPES);
        }
        String normalized = type.trim().toLowerCase();
        if (!VALID_TYPES.contains(normalized)) {
            throw new IllegalStateException(
                    "hagent.memory.chat.type 取值非法：" + type + "。合法取值：" + VALID_TYPES);
        }
        if (!IMPLEMENTED_TYPES.contains(normalized)) {
            throw new IllegalStateException(String.format(
                    "hagent.memory.chat.type=%s 尚未提供实现（当前已实现：%s）。"
                            + "MySQL 实现计划在阶段 7 提供 —— 该阶段会有专门的数据表与 DDL。"
                            + "在此之前请使用 file 或 redis。"
                            + "【为什么直接失败而不是降级】：降级会让你以为配置生效了，"
                            + "而历史其实存在别处（甚至只在内存里，重启即丢），且不会有任何报错。",
                    type, IMPLEMENTED_TYPES));
        }
    }
}
