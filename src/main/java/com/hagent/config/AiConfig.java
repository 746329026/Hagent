package com.hagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 能力的装配声明 —— 目前只负责注册配置属性。
 *
 * <p>{@code ChatClient} 与自定义 advisor 链已由 {@link com.hagent.app.AgentPipeline} 取代：
 * 管道直连 {@code ChatModel}，记忆读写与检索都是管道里的步骤。
 *
 * <p>★ {@code ChatModel} 之所以唯一，靠的是 {@code application.yml} 里的能力归属声明：
 * <pre>
 *   spring.ai.model.chat=deepseek        # 对话能力只由 DeepSeek 提供
 *   spring.ai.model.embedding=ollama     # 向量化能力只由 Ollama 提供
 * </pre>
 * 少了这两行，两个 starter 会各建一个 {@code ChatModel} Bean，
 * 启动直接报 {@code expected single matching bean but found 2}。
 */
@Configuration
@EnableConfigurationProperties({TraceProperties.class, PromptProperties.class, AgentLoopProperties.class})
public class AiConfig {

    // 已删除的 Bean 去向：chatClient → AgentPipeline（直连 ChatModel）；
    // 5 个 advisor → 管道的对应步骤；chatMemory 占位 → 删除，
    // 改由启动自检保证"框架默认 ChatMemory 出现即失败"。
}
