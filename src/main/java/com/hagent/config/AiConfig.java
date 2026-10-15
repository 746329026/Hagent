package com.hagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 能力的装配声明。
 *
 * <h2>★ 阶段 8 之后这里几乎空了 —— 这是设计的结果，不是遗漏</h2>
 *
 * <p>本类原先承担"把 {@code ChatModel} 包装成 {@code ChatClient}、并挂上 5 个自定义切面"
 * 的职责。阶段 8 把 advisor 链换成 {@link com.hagent.app.AgentPipeline} 之后：
 *
 * <pre>
 *   ChatClient              —— 不再使用（管道直连 ChatModel）
 *   5 个自定义切面           —— 已删除，职责并入管线
 *   chatMemory Bean         —— 已删除（框架边界的占位不再需要，改由启动自检守着）
 *   AdvisorProperties       —— 改名 TraceProperties，前缀 hagent.trace
 * </pre>
 *
 * <p>于是本类只剩两件事：<b>注册配置属性</b>，以及保留下面这段
 * "为什么 ChatModel 是唯一的"的说明 —— 它是<b>排障时最需要的一条事实</b>，
 * 所以搬家到这里而不是删掉。
 *
 * <h2>★ 为什么 {@code ChatModel} 是唯一的（这条说明必须留住）</h2>
 *
 * <p>它之所以唯一，靠的是 {@code application.yml} 里的<b>能力归属声明</b>：
 * <pre>
 *   spring.ai.model.chat=deepseek        # 对话能力只由 DeepSeek 提供
 *   spring.ai.model.embedding=ollama     # 向量化能力只由 Ollama 提供
 * </pre>
 * 少了这两行，两个 starter 会各建一个 {@code ChatModel} Bean，
 * 启动直接报 {@code expected single matching bean but found 2}（<b>已实测</b>）。
 *
 * <p>阶段 8 之前，这条事实藏在 {@code chatClient(...)} 的 javadoc 里；
 * 现在它由 {@code AgentPipeline} 的构造器真实依赖 —— 依赖点变了，事实没变。
 *
 * <h2>★ 阶段 9 会在这里加什么</h2>
 *
 * <p>工具（{@code ToolCallback}）的注册。那时要面对"工具从哪来、如何分组（最小权限）"，
 * 而<b>工具循环由谁驱动</b>这个前提问题已经在本阶段定下：
 * 由 {@code AgentPipeline} 自己驱动（框架的 {@code ToolCallingAdvisor} 已不参与）。
 */
@Configuration
@EnableConfigurationProperties({TraceProperties.class, PromptProperties.class})
public class AiConfig {

    // ========================================================================
    // ⚠️ 已删除的 Bean 与它们的去向（留档，避免后来者以为丢了什么）
    //
    //   chatClient(ChatModel, ...)          → AgentPipeline（app 包，直连 ChatModel）
    //   traceAdvisor                        → AgentPipeline#logRequest + #buildFinal
    //   memoryWritebackAdvisor              → AgentPipeline#writeBack
    //   memoryReadAdvisor                   → AgentPipeline#run 的第 ② 步
    //   ragRetrievalAdvisor                 → AgentPipeline#run 的第 ③ 步
    //   promptAssemblyAdvisor               → PromptBuilder（app 包）
    //   chatMemory（ChatMemoryAdapter）     → 删除；由启动自检守住框架默认实现
    //
    // ★ 为什么"删掉一个占位 Bean"这次是对的（与决策 34/26 的结论相反）：
    //   那两次是"保留一个零调用方但在起作用的占位"；而阶段 8 之后
    //   框架边界<b>整个不存在了</b>（没人再走 ChatClient / 框架记忆切面），
    //   所以占位失去了保护对象，改由"出现即失败"的启动自检来守住 ——
    //   从"靠保留一个没人调的类"升级为"靠一条会报错的检查"。
    // ========================================================================
}
