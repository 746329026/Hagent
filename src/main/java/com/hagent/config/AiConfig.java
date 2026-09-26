package com.hagent.config;

import com.hagent.advisor.ConversationMemoryAdvisor;
import com.hagent.advisor.PromptTraceAdvisor;
import com.hagent.memory.ChatMemoryAdapter;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 能力装配 —— 把底层模型（{@link ChatModel}）装配成业务可用的 {@link ChatClient}。
 *
 * <h2>★ 为什么要从 ChatModel 升级到 ChatClient</h2>
 *
 * <p>两者的关系是<b>包装</b>，不是"两个并列的东西"。实测接口定义：
 * <pre>
 *   ChatClient.create(ChatModel chatModel)      ← 这一行说明了一切
 * </pre>
 *
 * <table border="1">
 *   <tr><th></th><th>ChatModel</th><th>ChatClient</th></tr>
 *   <tr><td>职责</td><td>把 prompt 发给模型、拿回响应</td>
 *       <td>在此之上：拼 prompt、挂切面、挂工具、挂记忆、结构化输出</td></tr>
 *   <tr><td>谁包装谁</td><td>被 ChatClient 包装</td><td>由 ChatModel 构造</td></tr>
 * </table>
 *
 * <p><b>阶段 1 就必须用 ChatClient 的理由</b>：后续所有能力（切面、工具调用、
 * 会话记忆、结构化输出）<b>都挂在 ChatClient 这一层，不挂在 ChatModel 上</b>。
 * 如果现在用 ChatModel 写业务接口，等阶段 4 加切面时整条调用层都要重写。
 *
 * <p>注意：启动探针（StartupProbe）继续用 ChatModel 是正确的 ——
 * 它要验证的是"模型连通性"这个最底层机制，不需要切面与工具。
 *
 * <h2>★ 这里没有做什么（刻意的，不是遗漏）</h2>
 *
 * <p><b>没有配置 ChatMemory</b>。会话记忆属于阶段 6，届时要做的关键决策是
 * "接口与实现分离"：{@code ChatMemory} 接口在高处、
 * file/redis/mysql 三种实现独立，靠条件装配切换，做到"换实现只改配置不改代码"。
 * 现在提前挂一个记忆实现只会让那一步的对比实验做不了，所以留到阶段 6。
 *
 * <p><b>没有配置工具</b>。工具属于阶段 8，同理留到那时再加 ——
 * 那时才需要面对"关掉框架自动工具执行、自己驱动循环"的问题。
 */
@Configuration
@EnableConfigurationProperties(AdvisorProperties.class)
public class AiConfig {

    /**
     * 业务侧统一使用的对话客户端。
     *
     * <p>参数 {@code chatModel} 是 Spring AI 自动装配出来的唯一 ChatModel Bean。
     * 它之所以唯一，靠的是 {@code application.yml} 里的能力归属声明：
     * <pre>
     *   spring.ai.model.chat=deepseek        # 对话能力只由 DeepSeek 提供
     *   spring.ai.model.embedding=ollama     # 向量化能力只由 Ollama 提供
     * </pre>
     * 少了这两行，两个 starter 会各建一个 ChatModel Bean，
     * 启动直接报 "expected single matching bean but found 2"（已实测）。
     *
     * <p>切面用 {@code defaultAdvisors(...)} 挂在<b>默认</b>位置，
     * 意味着所有通过这个 ChatClient 发起的调用都会带上它 ——
     * 业务代码不需要在每个调用点重复声明。
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 AdvisorProperties advisorProperties,
                                 ConversationMemory memory,
                                 MemoryProperties memoryProperties) {
        return ChatClient.builder(chatModel)
                // ★ 两个切面的顺序由数据依赖决定，不能随意：
                //   order 越小越靠外层（先执行、后收尾）
                //   traceAdvisor        order=0 -> 最外层：记录【注入后】的完整 prompt
                //   memoryAdvisor       order=1 -> 内层  ：负责把历史注入 prompt
                // 若顺序反了，日志将看不到历史注入的效果，排查记忆问题时会误判为"没生效"。
                .defaultAdvisors(
                        traceAdvisor(advisorProperties),
                        conversationMemoryAdvisor(memory, memoryProperties))
                // 默认 system prompt 留到阶段 1 下一步再定：
                // 它必须与"实际挂载的工具清单"保持一致（否则会出现
                // "提示词里写了某个工具、但工具没注册"这类静默失效），
                // 所以等工具就位后，用 ToolCapabilityAdvisor 做一致性校验时再一起确定。
                .build();
    }

    /**
     * 调用链日志切面。单独声明成 Bean 而不是内联 new，
     * 是为了将来可以在别处按类型引用它（例如校验切面顺序的测试）。
     */
    @Bean
    public Advisor traceAdvisor(AdvisorProperties advisorProperties) {
        return new PromptTraceAdvisor(advisorProperties);
    }

    /**
     * 会话记忆切面（order=1，在日志切面内层）。
     *
     * <p>它依赖的是本项目的 {@code ConversationMemory} 接口 ——
     * <b>具体用 file / redis / mysql 由配置决定，本类完全无感</b>。
     * 这就是"换实现只改配置，不改代码"在装配层的体现。
     */
    @Bean
    public Advisor conversationMemoryAdvisor(ConversationMemory memory,
                                             MemoryProperties memoryProperties) {
        return new ConversationMemoryAdvisor(memory, memoryProperties);
    }

    /**
     * 把本项目的 {@link ConversationMemory} 适配成框架的 {@link ChatMemory}。
     *
     * <p>需要框架能力的地方（例如将来挂框架自带的 Advisor）用这个适配后的类型；
     * 需要预算控制的地方用原生接口。两个接口的关注点不同，不能互相替代 ——
     * 详见 {@link ChatMemoryAdapter} 的类注释。
     */
    @Bean
    public ChatMemory chatMemory(ConversationMemory memory, MemoryProperties memoryProperties) {
        return new ChatMemoryAdapter(memory, memoryProperties);
    }

    // ========================================================================
    // ⚠️ 已删除：taskIntentPrompt Bean
    //
    // 它曾把 "任务意图理解" 的提示词以 Resource Bean 的形式暴露给
    // TaskIntentAppService。整个意图理解链路（DTO / Service / 提示词 / /chat/intent 端点）
    // 已随"前置意图提取层"这一设计一并撤回，理由见 docs/PROCEDURES.md。
    //
    // ★ 但这一节留下的【提示词外置】做法被后续阶段沿用：
    //   提示词是业务语义的载体、调整频繁，而调整不该要求改 Java、重新编译。
    //   放在 prompts/*.md 还能用 git 直接 diff 出"提示词改了什么"。
    //   阶段 7（RAG 的评判提示词）与阶段 9（Agent 的系统提示词）会继续用这个模式。
    // ========================================================================
}
