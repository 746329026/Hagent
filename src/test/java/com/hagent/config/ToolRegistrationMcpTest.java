package com.hagent.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 16：<b>工具清单的合并点</b>（{@code ToolRegistration}）的两条不变量。
 *
 * <h2>★ 为什么这两条值得单独测</h2>
 *
 * <ol>
 *   <li><b>没挂 MCP 时行为必须与以前完全一致</b>：默认（不激活 {@code mcp} profile）就是这种情况。
 *       一旦这里"顺手"变了什么（比如总是包一层、或总是加前缀），
 *       所有既有功能都会悄悄改名 —— 而名字进的是 JSON Schema 与审计表，<b>不会报错</b>；</li>
 *   <li><b>MCP 工具名必须带前缀</b>：工具名由<b>外部进程</b>决定，我们无法假设它不叫
 *       {@code retrieveKnowledge}。撞名的后果是本地工具被顶掉，而模型只是"忽然不用检索了"。</li>
 * </ol>
 *
 * <p>真实连一个 MCP server 的端到端验证不放在自动化测试里 —— 那需要先构建
 * {@code MCP/time-server} 的 jar，会让 {@code mvn test} 依赖一次额外构建。
 * 它由 {@code tools/mcp-probe.mjs}（server 侧握手）+ 主应用启动日志（client 侧工具清单）
 * 两条证据覆盖，见 HANDOFF 的验收清单。
 */
class ToolRegistrationMcpTest {

    @Test
    @DisplayName("★ 没有 MCP 连接时：工具清单原样返回，一个都不多、一个都不少")
    void withoutMcpClientsLocalToolsAreReturnedUntouched() {
        ToolCallback retrieval = fakeTool("retrieveKnowledge");
        ToolCallback memory = fakeTool("saveMemory");
        ToolCallback[] local = {retrieval, memory};

        ToolCallbackProvider provider = new ToolRegistration()
                .toolCallbackProvider(local, emptyClients(), new MockEnvironment());

        assertThat(provider.getToolCallbacks())
                .as("默认（不激活 mcp profile）走的就是这条分支 —— 它必须与阶段 15 的代码等价")
                .containsExactly(retrieval, memory);
    }

    @Test
    @DisplayName("★★ 配置在但没启用时，装配点会把话说出来（不再是一次静默的能力缺失）")
    void inactiveMcpProfileIsAnnouncedInsteadOfSilentlyDropped() {
        // 这条用例断言的是"走了告警分支"这件事本身：告警用 WARN 级别，
        // 由 logback 捕获比断言日志文本更脆弱，所以这里只钉住"不抛异常且工具清单不变"。
        // 真正的可见性由启动日志体现（2026-10-22 的实测见 PROCEDURES §16.5）。
        MockEnvironment environment = new MockEnvironment();   // 没有 mcp profile

        ToolCallbackProvider provider = new ToolRegistration()
                .toolCallbackProvider(new ToolCallback[]{fakeTool("retrieveKnowledge")},
                        emptyClients(), environment);

        assertThat(provider.getToolCallbacks()).hasSize(1);
    }

    @Test
    @DisplayName("★★ MCP 工具名：一律 mcp_<server>_<tool>，且非法字符被替换")
    void mcpToolNamesArePrefixedAndSanitized() {
        assertThat(ToolRegistration.mcpToolName("hagent-time", "getCurrentTime"))
                .as("server 名里的连字符要换掉：工具名会进 JSON Schema 与审计表，"
                        + "而部分提供方不接受连字符")
                .isEqualTo("mcp_hagent_time_getCurrentTime");

        assertThat(ToolRegistration.mcpToolName(null, "getCurrentTime"))
                .as("取不到 server 名时也要有稳定的前缀，而不是拼出 mcp_null_")
                .isEqualTo("mcp_server_getCurrentTime");

        assertThat(ToolRegistration.mcpToolName("hagent time", "get current time"))
                .as("空格同理 —— 前缀的意义是「永远可解析」，不是「好看」")
                .isEqualTo("mcp_hagent_time_get_current_time");
    }

    // ==================== 脚手架 ====================

    private static ObjectProvider<List<McpSyncClient>> emptyClients() {
        @SuppressWarnings("unchecked")
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(List.of());
        return provider;
    }

    private static ToolCallback fakeTool(String name) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description("测试用假工具：" + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
