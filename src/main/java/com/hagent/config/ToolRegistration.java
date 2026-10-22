package com.hagent.config;

import com.hagent.retrieval.KnowledgeSearch;
import com.hagent.tool.KnowledgeRetrievalTool;
import com.hagent.tool.LongTermMemoryTools;
import com.hagent.tool.audit.MysqlToolInvocationStore;
import com.hagent.tool.audit.ToolInvocationStore;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * <b>工具注册</b> —— 本项目所有可被模型调用的工具的<b>唯一装配点</b>。
 *
 * <p>★ 为什么集中在"注册"而不是各自 {@code @Component} 完事：<b>模型能看到哪些工具</b>必须有一处
 * 集中决定 —— 它是<b>能力边界</b>（看一眼就知道模型能做什么，也是审计与评审的入口），
 * 将来做按角色过滤（最小权限）、改名、审计开关时，改动只在这里。
 *
 * <p>★ 为什么用框架自带的 {@code MethodToolCallbackProvider}，而不是第三方
 * {@code org.springaicommunity:spring-ai-agent-utils} 的 {@code ToolCallbacks.from(...)}：
 * 框架已自带等价能力（就在已有的 {@code spring-ai-model} jar 里），
 * <b>少一个第三方依赖 = 少一处版本漂移风险</b>。
 *
 * <p>★★ 工具描述的来源：方法上的 {@code @Tool(description=...)} 与参数上的
 * {@code @ToolParam(description=...)}，由框架翻译成 JSON Schema 随请求发给模型。
 * ⇒ <b>工具用法绝不写进 system prompt</b>：那是"同一件事说两遍"，两处必然漂移。
 *
 * <p>★ 可插拔：{@code hagent.rag.retrieval.enabled=false} 时检索工具<b>不注册</b> ——
 * 模型看不到 {@code retrieveKnowledge}，也就无从检索。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ToolAuditProperties.class)
public class ToolRegistration {

    /**
     * 注册模型可用的全部工具。
     *
     * <p>★ 长期记忆的 3 个工具与检索开关无关：记忆是"跨会话的事实"，不是知识库的一部分 ——
     * 关掉检索时它们照常在。空数组只可能出现在"一个工具类都没注册"的编程错误里。
     *
     * @param retrievalProperties 检索开关；关掉则不注册检索工具
     */
    @Bean
    public ToolCallback[] allTools(KnowledgeSearch retrievalService,
                                   RetrievalProperties retrievalProperties,
                                   com.hagent.memory.longterm.LongTermMemoryStore longTermMemoryStore) {
        java.util.List<Object> toolObjects = new java.util.ArrayList<>(2);
        if (retrievalProperties.isEnabled()) {
            toolObjects.add(new KnowledgeRetrievalTool(retrievalService));
        } else {
            log.warn("[工具] hagent.rag.retrieval.enabled=false —— 检索能力整体关闭"
                    + "（模型看不到 retrieveKnowledge，不会发起检索）；长期记忆工具照常注册");
        }
        // ★ 长期记忆工具与检索开关无关：记忆是"跨会话的事实"，不是知识库的一部分
        toolObjects.add(new LongTermMemoryTools(longTermMemoryStore));

        ToolCallback[] tools = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();

        log.info("[工具] 已注册 {} 个工具：{}", tools.length,
                java.util.Arrays.stream(tools)
                        .map(t -> t.getToolDefinition().name())
                        .toList());
        return tools;
    }

    /**
     * 把工具清单包成框架的 {@link ToolCallbackProvider} 交给管线 ——
     * 同时也是"<b>本地工具 + MCP 工具</b>"的唯一合并点。
     *
     * <p>★ 为什么不直接让 {@code AgentPipeline} 注入 {@code ToolCallback[]}：语义不同 ——
     * 注入 Provider 表达的是"<b>能提供工具的东西</b>"，这正是框架设计该接口的用意，
     * 也与"可插拔"天然对齐（MCP 工具就靠这一点接进来，管线一行没改）。
     * 而且<b>光有 {@code ToolCallback[]} 这个 Bean，容器里就没有 {@code ToolCallbackProvider}</b> ——
     * 所有 {@code @SpringBootTest} 会在创建 {@code agentPipeline} 时失败
     * （{@code No qualifying bean of type 'ToolCallbackProvider'}），框架的自动配置不会补它。
     *
     * <p>★ 为什么 MCP 工具也在这里合并：工具来源有两种，但<b>"模型能看到哪些工具"只能有一个
     * 决定点</b>，否则按角色过滤、改名、审计开关都要写两遍，而它们必然漂移。
     *
     * <p>更硬的理由是<b>装配冲突</b>：starter 默认也会注册一个 {@code SyncMcpToolCallbackProvider}
     * （同样实现 {@link ToolCallbackProvider}），两个同类 Bean 并存 → 按类型注入单个 → 启动期
     * {@code expected single matching bean but found 2}。所以 {@code application.yml} 里关掉了
     * {@code spring.ai.mcp.client.toolcallback.enabled}，由本方法接管。
     *
     * <p>★ <b>没有挂 MCP 时（默认情况）行为不变</b>：{@code mcpClients} 为空 → 原样返回本地工具。
     *
     * @param mcpClients MCP 客户端（由 starter 创建；<b>可能一个都没有</b> ——
     *                   {@code mcp} profile 未激活时就是空列表，那是合法状态）
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(ToolCallback[] allTools,
                                                     ObjectProvider<List<McpSyncClient>> mcpClients,
                                                     org.springframework.core.env.Environment environment) {
        List<McpSyncClient> clients = mcpClients.getIfAvailable(List::of);
        if (clients == null || clients.isEmpty()) {
            warnIfMcpConfiguredButInactive(environment, allTools.length);
            return () -> allTools;
        }

        ToolCallback[] mcpTools = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                // ★ 一律加前缀：MCP 工具名由**外部进程**决定，没法假设它不叫 retrieveKnowledge /
                //   saveMemory —— 撞名的后果是本地工具被悄悄顶掉（同名时后注册的覆盖先注册的，且不报错）。
                .toolNamePrefixGenerator(ToolRegistration::mcpToolName)
                .build()
                .getToolCallbacks();

        ToolCallback[] merged = new ToolCallback[allTools.length + mcpTools.length];
        System.arraycopy(allTools, 0, merged, 0, allTools.length);
        System.arraycopy(mcpTools, 0, merged, allTools.length, mcpTools.length);

        log.info("[工具] 已注册 {} 个工具（本地 {} + MCP {}，连接 {} 个）：{}",
                merged.length, allTools.length, mcpTools.length, clients.size(),
                java.util.Arrays.stream(merged)
                        .map(t -> t.getToolDefinition().name())
                        .toList());
        return () -> merged;
    }

    /**
     * 「MCP 配置在，但没启用」—— <b>必须说出来</b>，否则它是一次静默的能力缺失。
     *
     * <p>★ 这条日志是踩出来的：不带 {@code mcp} profile 启动时，应用一切正常、工具与回答都正常，
     * 但模型手里<b>根本没有</b>时间工具 —— 用户问"现在是什么时间"，模型只能凭印象答，
     * 而<b>没有任何一行日志说明"外部工具没启用"</b>，于是排查方向全在代码上。
     * 处置不是"下次记得加 profile"，而是让装配点自己把话说出来：
     * 它同时知道 {@code application-mcp.yml} 在不在 classpath 上、这一次有没有真的连上 MCP。
     */
    private void warnIfMcpConfiguredButInactive(org.springframework.core.env.Environment environment,
                                                int localToolCount) {
        boolean profileActive = environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of("mcp"));
        boolean configOnClasspath = new org.springframework.core.io.ClassPathResource(
                "application-mcp.yml").exists();
        if (!profileActive && configOnClasspath) {
            // ★ 用 WARN 而不是 INFO：它描述的是一次**用户以为能用、实际不能用**的状态
            log.warn("[工具] ★ MCP 已配置但【未启用】（mcp profile 未激活）—— 本次只有本地 {} 个工具，"
                            + "外部工具（时间等）模型看不到。要启用请加启动参数：\n"
                            + "        --spring.profiles.active=local,mcp\n"
                            + "        （IDEA：Run Configuration → Active profiles 填 local,mcp）",
                    localToolCount);
            return;
        }
        log.debug("[工具] 没有 MCP 连接（mcp profile 未激活）—— 工具清单 = 本地 {} 个", localToolCount);
    }

    /**
     * MCP 工具名的统一形状：{@code mcp_<server>_<tool>}，例 {@code mcp_hagent_time_getCurrentTime}。
     *
     * <p>三个理由：① <b>防撞名</b>（MCP 工具名不归我们管）；② <b>可追溯</b> ——
     * 审计表 {@code chat_tool_invocation.tool_name} 里一眼看出"这是外部进程提供的工具"；
     * ③ <b>可替换</b> —— 同一个工具从本地搬到 MCP server，名字前缀变化本身就是线索。
     */
    private static String mcpToolName(org.springframework.ai.mcp.McpConnectionInfo connection,
                                      io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        String server = connection != null
                && connection.initializeResult() != null
                && connection.initializeResult().serverInfo() != null
                ? connection.initializeResult().serverInfo().name()
                : null;
        return mcpToolName(server, tool.name());
    }

    /**
     * 命名规则的<b>纯函数</b>版本（独立出来的唯一目的：可被单测直接钉住）。
     *
     * <p>非法字符一律换成下划线：工具名会进 JSON Schema、审计表与日志，而
     * {@code hagent-time} 这种带连字符的 server 名在部分提供方那里不合法；
     * 换掉的是<b>暴露给模型的名字</b>，调用 server 时仍用它原来的名字。
     */
    static String mcpToolName(String serverName, String toolName) {
        return "mcp_" + sanitizeName(serverName) + "_" + sanitizeName(toolName);
    }

    private static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "server";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * 工具调用审计的存储。
     *
     * <p><b>为什么用 {@code @ConditionalOnProperty(matchIfMissing = true)} 而不是在方法里判 if</b>：
     * 关掉审计时应该<b>根本没有这个 Bean</b>，而不是"有了但什么都不做" ——
     * 后者会让 {@code AgentPipeline} 拿到一个假存储、以为自己写的审计真的落库了。
     *
     * <p>★ 关掉时管线侧看到的是"没有审计存储"（构造器收 {@code ObjectProvider}），
     * 于是它明确记一条 WARN 说"审计已关闭"，而不是静默地不写。
     *
     * @param dataSource <b>业务主库</b>数据源 —— 必须按名字取：{@code @Primary} 会让按类型注入
     *                   静默拿到向量库（理由见 {@code MysqlToolInvocationStore} 的类注释）。
     */
    @Bean
    @ConditionalOnProperty(name = "hagent.tool.audit.enabled", havingValue = "true",
            matchIfMissing = true)
    public ToolInvocationStore toolInvocationStore(
            @Qualifier("dataSource") DataSource dataSource,
            ToolAuditProperties properties) {
        return new MysqlToolInvocationStore(dataSource, properties.getTableName());
    }
}
