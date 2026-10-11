package com.hagent.config;

import com.hagent.advisor.AdvisorOrders;
import com.hagent.advisor.MemoryReadAdvisor;
import com.hagent.advisor.MemoryWritebackAdvisor;
import com.hagent.advisor.PromptAssemblyAdvisor;
import com.hagent.advisor.PromptTraceAdvisor;
import com.hagent.advisor.RagRetrievalAdvisor;
import com.hagent.app.KnowledgeRetrievalService;
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
import org.springframework.core.io.Resource;

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
 * <p>后续所有能力（切面、工具调用、会话记忆、结构化输出）<b>都挂在 ChatClient 这一层</b>。
 *
 * <h2>★ 切面链的完整顺序（阶段 6 后半定型）</h2>
 *
 * <pre>
 *   order = MIN+100  PromptTraceAdvisor        最外层：记"请求进链时的样子" + 回复 + 全链耗时
 *   order = MIN+150  MemoryWritebackAdvisor    after 侧：一次请求恰好把"问 + 答"写回一次
 *   order = MIN+200  MemoryReadAdvisor         before 侧：读历史 → 写 context
 *   order = MIN+220  RagRetrievalAdvisor       before 侧：检索（含权限过滤）→ 片段写 context
 *   order = MIN+250  PromptAssemblyAdvisor     ★ 唯一重建消息列表者 + 记"模型收到的 prompt"
 *   ──────────────────────────────────────── 以上全部在工具循环【之外】
 *   order = MIN+300  ToolCallingAdvisor（框架） 工具调用循环（N 轮，自理内部历史）
 *   order = MAX      ChatModelCallAdvisor（框架）真正调模型
 * </pre>
 *
 * <h2>★ 三条不能弄错的顺序约束</h2>
 *
 * <ol>
 *   <li><b>数字小的在外层。</b>外层先执行、后收尾。
 *       已实测：push(10) 后 push(-5)，执行顺序是 -5 再到 10。</li>
 *   <li><b>{@code after} 的执行顺序与 {@code before} 相反。</b>
 *       链是洋葱式的 around 包裹：内层先返回，所以内层的 after 先跑。
 *       因此 {@code MIN+100} 的 after 最后执行（量到全链耗时），
 *       而 {@code MIN+150} 的 after 落在它之前（写回结果能被日志记到）。</li>
 *   <li><b>全部自定义切面必须在 {@code MIN+300} 之前。</b>
 *       框架的工具循环在 {@code MIN+300}，它内层的切面会被跑 N 次。
 *       详见 {@link AdvisorOrders}。</li>
 * </ol>
 *
 * <h2>★ 这里没有做什么（刻意的，不是遗漏）</h2>
 *
 * <p><b>没有配置默认 system prompt。</b>system 的内容由
 * {@link PromptAssemblyAdvisor} 从 {@code prompts/rag-answer-prompt.md}
 * 拼出来（原有 system + 回答约束 + 检索片段）。
 * 旧注释说"等工具就位后用 ToolCapabilityAdvisor 做一致性校验时再一起确定"——
 * 那个切面已随"前置意图提取层"一起撤回，属于红线，不再提及。
 *
 * <p><b>没有配置工具。</b>工具属于阶段 8。届时要面对"关掉框架自动工具执行、
 * 自己驱动循环"的问题。
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
     * 意味着所有通过这个 ChatClient 发起的调用都会带上它们 ——
     * 业务代码不需要在每个调用点重复声明。这是"能力挂在 client 上"的体现，
     * 也是本项目选择切面而不是在 app 层写顺序调用的核心理由之一。
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 AdvisorProperties advisorProperties,
                                 PromptProperties promptProperties,
                                 ConversationMemory memory,
                                 MemoryProperties memoryProperties,
                                 KnowledgeRetrievalService retrievalService,
                                 Resource ragAnswerPromptResource) {
        return ChatClient.builder(chatModel)
                // 参数顺序不影响结果（框架按 order 排序），但按 order 声明可读性最好。
                // 已实测：框架按 order 分发，与 push 的先后无关。
                .defaultAdvisors(
                        traceAdvisor(advisorProperties),
                        memoryWritebackAdvisor(memory),
                        memoryReadAdvisor(memory, memoryProperties),
                        ragRetrievalAdvisor(retrievalService),
                        promptAssemblyAdvisor(advisorProperties, promptProperties, ragAnswerPromptResource))
                .build();
    }

    /**
     * 调用链日志切面（最外层）。单独声明成 Bean 而不是内联 new，
     * 是为了可以在别处按类型引用它（例如校验切面顺序的测试）。
     */
    @Bean
    public Advisor traceAdvisor(AdvisorProperties advisorProperties) {
        return new PromptTraceAdvisor(advisorProperties);
    }

    /**
     * 记忆写回切面 —— <b>只有 after</b>，一次请求恰好写一次。
     *
     * <p>与读取拆成两个 Bean 是本轮最重要的重构：两者的位置要求相反
     * （读取要在拼装之前，写回要在整条链收尾），一个 order 装不下。
     */
    @Bean
    public Advisor memoryWritebackAdvisor(ConversationMemory memory) {
        return new MemoryWritebackAdvisor(memory);
    }

    /**
     * 记忆读取切面 —— <b>只有 before</b>，把历史写进 context，不碰 prompt。
     *
     * <p>它依赖本项目的 {@code ConversationMemory} 接口 ——
     * <b>具体用 file / redis / mysql 由配置决定，本类完全无感</b>。
     * 这就是"换实现只改配置，不改代码"在装配层的体现。
     */
    @Bean
    public Advisor memoryReadAdvisor(ConversationMemory memory, MemoryProperties memoryProperties) {
        return new MemoryReadAdvisor(memory, memoryProperties);
    }

    /** 知识库检索切面 —— 只产片段、写 context，不碰 prompt。 */
    @Bean
    public Advisor ragRetrievalAdvisor(KnowledgeRetrievalService retrievalService) {
        return new RagRetrievalAdvisor(retrievalService);
    }

    /**
     * 提示词拼装切面 —— 全链路唯一重建消息列表的人。
     *
     * <p>它需要三样东西：截断配置（打日志）、提示词配置（是否注入约束）、
     * 以及约束正文本身（{@link Resource}，由 {@code RagConfig} 保证存在）。
     */
    @Bean
    public Advisor promptAssemblyAdvisor(AdvisorProperties advisorProperties,
                                         PromptProperties promptProperties,
                                         Resource ragAnswerPromptResource) {
        return new PromptAssemblyAdvisor(advisorProperties, promptProperties, ragAnswerPromptResource);
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
    //   阶段 6 后半的 ragAnswerPromptResource Bean（在 RagConfig 里）就是这个模式。
    // ========================================================================
}
