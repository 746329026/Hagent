package com.hagent.tool;

import com.hagent.app.KnowledgeRetrievalService;
import com.hagent.config.RetrievalProperties;
import com.hagent.config.ToolAuditProperties;
import com.hagent.tool.audit.MysqlToolInvocationStore;
import com.hagent.tool.audit.ToolInvocationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * <b>工具注册</b> —— 本项目所有可被模型调用的工具的<b>唯一装配点</b>。
 *
 * <h2>★ 为什么是"注册"而不是"各自 @Component 完事"</h2>
 *
 * <p>工具类本身可以是普通 Bean，但<b>模型能看到哪些工具</b>必须有一处集中决定：
 * <ul>
 *   <li>它是"能力边界" —— 看一眼这个类就知道模型能做什么（审计与评审的入口）；</li>
 *   <li>将来要做<b>按角色过滤</b>（最小权限）时，改动只在这里；</li>
 *   <li>参考实现（{@code kewei-ai-agent} 的 {@code ToolRegistration}）也是这么做的，
 *       而且它进一步分成了 {@code allTools} 与 {@code memoryTools} 两组。</li>
 * </ul>
 *
 * <h2>★ 为什么用 {@code MethodToolCallbackProvider} 而不是第三方 {@code ToolCallbacks}</h2>
 *
 * <p>参考实现用的是 {@code org.springframework.ai.support.ToolCallbacks.from(...)} ——
 * 它来自第三方包 {@code org.springaicommunity:spring-ai-agent-utils}，且那个项目用的是
 * Spring AI <b>2.0.0-M2</b>。
 *
 * <p>本项目<b>不引入那个依赖</b>：框架自带等价的
 * {@link MethodToolCallbackProvider}（就在我们已有的 {@code spring-ai-model} jar 里，
 * 而且参考实现那个工具类内部也是靠它做 {@code @Tool} 内省的）。
 * <b>少一个第三方依赖 = 少一处版本漂移风险</b>。
 *
 * <h2>★★ 工具的描述从哪来（这条决定"要不要写进提示词"）</h2>
 *
 * <p>来自方法上的 {@code @Tool(description=...)} 与参数上的 {@code @ToolParam(description=...)}，
 * 由框架自动翻译成 JSON Schema 随请求发给模型（已核实 {@code DeepSeekChatModel}
 * 会调用 {@code getFunctionTools(toolDefinitions)} 把它们转成
 * {@code FunctionTool(name, description, inputSchema)}）。
 *
 * <p>⇒ <b>工具用法绝不写进 system prompt</b>。写进去就是"同一件事说两遍"，
 * 而两处必然漂移（这正是已撤回的"前置意图提取层"的毛病）。
 *
 * <h2>★ 可插拔：检索工具的开与关</h2>
 *
 * <p>{@code hagent.rag.retrieval.enabled=false} 时本 Bean 返回<b>空数组</b> ——
 * 模型看不到 {@code retrieveKnowledge}，也就无从检索。
 * 这是刻意的语义调整（阶段 9）：该开关原先表示"切面不产片段"，
 * 现在表示"<b>检索这个能力整体拔掉</b>"。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ToolAuditProperties.class)
public class ToolRegistration {

    /**
     * 注册模型可用的全部工具。
     *
     * <p>★ 阶段 11 起<b>返回空数组不再是关掉检索的结果</b>：长期记忆的 3 个工具与检索无关，
     * 检索关掉时它们照常在。空数组只可能出现在"一个工具类都没注册"的编程错误里。
     *
     * @param retrievalProperties 检索开关；关掉则不注册检索工具
     */
    @Bean
    public ToolCallback[] allTools(KnowledgeRetrievalService retrievalService,
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
     * 把上一步的结果包成框架的 {@link ToolCallbackProvider} 交给管线。
     *
     * <h3>★ 为什么不直接让 {@code AgentPipeline} 注入 {@code ToolCallback[]}</h3>
     *
     * <p>两种写法都能跑，但<b>语义不同</b>：
     * <ul>
     *   <li>注入数组 → 管线的依赖是"一堆工具"，工具从哪来、怎么注册**不在依赖里**；</li>
     *   <li>注入 {@code ToolCallbackProvider} → 管线的依赖是"<b>能提供工具的东西</b>"，
     *       这正是框架设计这个接口的用意，而且它与"可插拔"的语义天然对齐
     *       （将来要按角色过滤工具时，只需换一个 provider 实现，管线一行不改）。</li>
     * </ul>
     *
     * <p>★ 还有一个现实原因：**光有 {@code ToolCallback[]} 这个 Bean，
     * 容器里就没有 {@code ToolCallbackProvider}** —— 于是所有 {@code @SpringBootTest}
     * 会在创建 {@code agentPipeline} 时失败（实测：66 个集成测试全红，
     * 报 {@code No qualifying bean of type 'ToolCallbackProvider'}）。
     * 框架自己的自动配置<b>不会</b>补这个 Bean（除非类路径上有 MCP 之类的客户端）。
     * 所以这里显式提供，装配关系一目了然。
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(ToolCallback[] allTools) {
        return () -> allTools;
    }

    /**
     * 工具调用审计的存储。
     *
     * <p><b>为什么用 {@code @ConditionalOnProperty(matchIfMissing = true)} 而不是在方法里判 if</b>：
     * 关掉审计时应该<b>根本没有这个 Bean</b>，而不是"有了但什么都不做" ——
     * 后者会让 {@code AgentPipeline} 拿到一个假存储、以为自己写的审计真的落库了。
     * 与阶段 6 那条"配置未实现的实现 → 启动期失败，不静默降级"同一个立场。
     *
     * <p>★ 关掉时管线侧看到的是"没有审计存储"（构造器收 {@code ObjectProvider}），
     * 于是它明确记一条 WARN 说"审计已关闭"，而不是静默地不写。
     *
     * @param dataSource <b>业务主库</b>数据源 —— 必须按名字取。
     *                   理由见 {@code MysqlToolInvocationStore} 的类注释（踩坑 29：
     *                   {@code @Primary} 会让按类型注入静默拿到向量库）。
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
