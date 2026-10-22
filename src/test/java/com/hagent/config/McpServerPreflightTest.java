package com.hagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>MCP 前置检查</b>（{@link McpServerPreflight}）。
 *
 * <p>它防的是一个实测到的具体失败：
 * <pre>
 *   忘了构建 MCP server
 *   → 启动照走，20 秒后报 "TimeoutException: Did not observe any item ... in 'map'"
 *   → 这句话里没有一个字提到"jar 不存在"
 * </pre>
 * 所以这里断言两件事：<b>该失败时立刻失败、且话说得清</b>；
 * <b>没配 MCP 时一个字都不能干涉</b>（否则所有既有启动都会被一个无关检查拖住）。
 */
class McpServerPreflightTest {

    private final McpServerPreflight preflight = new McpServerPreflight();
    private final SpringApplication application = new SpringApplication(Object.class);

    @Test
    @DisplayName("★ 没有 mcp 配置（未激活 profile）→ 完全不干涉")
    void noConnectionsIsNoOp() {
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> preflight.postProcessEnvironment(environment, application))
                .as("默认启动（不激活 mcp profile）走的就是这条路 —— 它必须什么都不做")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ jar 不存在 → 启动期立刻失败，且报错里带可执行的修法")
    void missingJarFailsFastWithActionableMessage() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.client.stdio.connections.time.command", "java")
                .withProperty("spring.ai.mcp.client.stdio.connections.time.args[0]", "-jar")
                .withProperty("spring.ai.mcp.client.stdio.connections.time.args[1]",
                        "MCP/time-server/target/not-built-yet.jar");

        assertThatThrownBy(() -> preflight.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-built-yet.jar")
                .as("★ 必须给出可执行的修法，而不是只说'失败'")
                .hasMessageContaining("MCP/time-server/pom.xml package")
                .hasMessageContaining("TimeoutException");
    }

    @Test
    @DisplayName("jar 存在 → 放行")
    void existingJarPasses(@TempDir Path tempDir) throws Exception {
        Path jar = Files.createFile(tempDir.resolve("time-server.jar"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.client.stdio.connections.time.command", "java")
                .withProperty("spring.ai.mcp.client.stdio.connections.time.args[0]", "-jar")
                .withProperty("spring.ai.mcp.client.stdio.connections.time.args[1]", jar.toString());

        assertThatCode(() -> preflight.postProcessEnvironment(environment, application))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("没有 -jar 形态的连接（例如将来的 HTTP server）→ 不误判为失败")
    void connectionsWithoutJarAreIgnored() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.client.stdio.connections.other.command", "node")
                .withProperty("spring.ai.mcp.client.stdio.connections.other.args[0]", "server.mjs");

        assertThatCode(() -> preflight.postProcessEnvironment(environment, application))
                .as("这条检查只认识 java -jar；形态不同就交还给启动链路自己报错，"
                        + "免得把一个合法的连接误判成'jar 不存在'")
                .doesNotThrowAnyException();
        assertThat(McpServerPreflight.class.getDeclaredMethods())
                .as("守卫自检：类里真的有一个 postProcessEnvironment 方法（防止重命名后测试空转）")
                .anyMatch(m -> m.getName().equals("postProcessEnvironment"));
    }
}
