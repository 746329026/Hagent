package com.hagent.app;

import com.hagent.prompt.WorkflowPlanPrompt;
import com.hagent.workflow.InvalidPlanException;
import com.hagent.workflow.PlanStep;
import com.hagent.workflow.WorkflowPlan;
import com.hagent.workflow.WorkflowPlanParser;
import org.springframework.ai.chat.messages.SystemMessage;
import reactor.core.publisher.Mono;

import com.hagent.agent.PipelineEvent;
import com.hagent.agent.ToolEventSink;

import com.hagent.config.AgentLoopProperties;
import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.config.MemoryProperties;
import com.hagent.memory.MessageCodec;
import com.hagent.memory.ReasoningTrace;
import com.hagent.memory.TokenBudget;
import com.hagent.memory.longterm.LongTermMemoryStore;
import com.hagent.security.UserContext;
import com.hagent.tool.ToolContextSupport;
import com.hagent.tool.audit.ToolInvocationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 管道 —— <b>本项目唯一的编排者</b>：把"一次对话"拆成固定的顺序步骤，直连 {@link ChatModel}。
 *
 * <h2>★ 阶段 8 的由来（它取代了什么）</h2>
 *
 * <pre>
 *   之前：ChatAppService → ChatClient（+5 个自定义 Advisor + 框架工具循环）→ ChatModel
 *   现在：ChatAppService → AgentPipeline（显式顺序）→ ChatModel
 * </pre>
 *
 * <p>换掉的不只是"切面"这个形态，更是<b>切面之间用字符串 key 传数据的约定</b>。
 * 之前 {@code MemoryReadAdvisor} 往 {@code ChatClientRequest.context()} 里放历史、
 * {@code PromptAssemblyAdvisor} 再从同一个 key 取出来 —— 编译器管不到 key，
 * 拼错一个字母的表现是"注入永远为空，且不报错"。现在这些是<b>方法参数</b>，编译期保证。
 *
 * <p><b>★ 附带的一个结构性质</b>：直连 {@code ChatModel} 意味着框架自动装配的切面
 * （{@code ToolCallingAdvisor} 等）<b>根本不参与</b>。
 * 于是"切面会被工具循环跑 N 次、所以必须幂等"这条约束（协议第 10 条）
 * <b>在本项目里失去了对象</b> —— 阶段 9 自己驱动循环时，重复调用是我们显式写的，不是隐式的。
 *
 * <h2>★ 固定顺序（顺序就是代码行序，不再有 order 常量）</h2>
 *
 * <pre>
 *   ① 记请求日志        ← 取代 PromptTraceAdvisor 的 before
 *   ② 读历史            ← 取代 MemoryReadAdvisor
 *   ③ （已无"无条件检索"）← 阶段 9 起检索是 retrieveKnowledge 工具，由模型决定何时调
 *   ④ 拼装              ← 委托 PromptBuilder（唯一重建消息列表者）
 *   ⑤ 发 StepStarted(N) ← 阶段 10：调模型【之前】先发"第 N 步开始"
 *   ⑥ 调模型            ← chatModel.stream(prompt)，逐片累积 text / reasoning
 *   ⑦ 有工具 → 执行并回灌 → 回到 ⑤（N+1）；没有工具 → 收尾
 *   ⑧ 收尾：写回记忆（问 + 答 + 思考）→ 发出 Final
 * </pre>
 *
 * <h2>★★ 四条来自实测的硬约束（改本类前必读）</h2>
 *
 * <ol>
 *   <li><b>每片是增量，必须自己拼接</b> —— 实测 184 片中最后一片 text 长度为 0，
 *       全片拼接才是完整回答。取最后一片会得到空字符串。</li>
 *   <li><b>空片必须过滤</b> —— 实测片 0 是 {@code text=<null>, reasoning=<空串>}。
 *       不过滤就会往 SSE 里发空事件。</li>
 *   <li><b>不传 {@code ChatOptions}</b> —— {@code new Prompt(messages)} 会让框架
 *       自动填入已配置的默认 options（实测字节码：{@code buildRequestPrompt} 里
 *       {@code if (prompt.getOptions() == null) → prompt.chatOptions(this.getOptions())}）。
 *       <b>显式传反而覆盖配置</b>，表现为"配置写了却没生效"（{@code reasoning-effort} 会丢）。</li>
 *   <li><b>写回必须在模型成功之后</b> —— 失败调用不留半条记录。
 *       这也让"预算天然不含当前问题"这条既有决定继续成立。</li>
 * </ol>
 *
 * <h2>★ 为什么返回 {@code Flux} 而不是回调式</h2>
 *
 * <p>两条入口（{@code /chat} 阻塞、{@code /stream-sse} 流式）消费的是
 * <b>同一份事件流</b> —— 否则"读历史 / 检索 / 拼装"要写两遍，
 * 而它们是三处最容易各自演化出差异的地方。
 *
 * <p>流是<b>冷</b>的：不订阅不会发生任何调用。所以 {@code /chat} 用
 * {@code blockLast()} 拿到 {@link PipelineEvent.ModelOutput.Final}，
 * {@code /stream-sse} 逐事件推送。
 *
 * <h2>★ 操作符的选型（每条都有具体理由，不要随手改）</h2>
 *
 * <ul>
 *   <li>{@code Flux.defer} 包住整个流程 —— 让<b>前置步骤抛出的异常</b>也走 flux 的
 *       {@code onError} 通道，而不是在方法返回前就抛出去。调用方因此只需处理一条错误路径；</li>
 *   <li>累积器建在 {@code defer} <b>内部</b> —— 若建在方法里，同一个 pipeline 实例被并发
 *       调用时两条流会往同一个 {@code StringBuilder} 写，表现为"回答互相串台"且极难复现；</li>
 *   <li>写回用 {@code concatWith(Flux.defer(...))} 而不是 {@code doOnComplete} ——
 *       必须保证<b>写回发生在 Final 发出之前</b>，否则前端收到 Final 时历史还没落库。</li>
 * </ul>
 *
 * <h2>★ 为什么 {@code append} 的失败不可观测（诚实声明）</h2>
 *
 * <p>{@link ConversationMemory#append} 返回 void，且内部 {@code catch (Exception)}
 * 吞掉一切 —— 记录层写失败<b>只打 ERROR 日志、不抛异常</b>。
 * 所以本类<b>无法</b>从返回值判断写回成败，
 * {@link PipelineEvent.ModelOutput.Final} 里<b>刻意不带</b>"写回是否成功"之类字段
 * （带了就是编造）。写回成败的唯一可观测渠道是日志 —— 见决策 40 的失败分级。
 */
@Slf4j
@Service
public class AgentPipeline {

    private static final String LOG_TAG = "AgentPipeline";

    /**
     * 调用方没传会话 ID 时的兜底值。
     *
     * <p>与旧实现的 {@code MemoryReadAdvisor.FALLBACK_CONVERSATION_ID} 取值相同，
     * 但语义变了：<b>旧实现需要"读的兜底值 == 写的兜底值"这条约定</b>
     * （否则读和写会落到不同会话上），而本类既是读的唯一来源、也是写的唯一来源 ——
     * 那条约定随两个切面合并进本类而<b>消失</b>。
     */
    public static final String FALLBACK_CONVERSATION_ID = "default";

    private final ChatModel chatModel;
    private final ConversationMemory memory;
    private final PromptBuilder promptBuilder;
    private final MemoryProperties memoryProperties;

    /**
     * 步数上限（阶段 12 从 {@code MemoryProperties} 搬来）—— 按模式两档。
     *
     * <p>为什么它是独立的一个字段而不是并进 {@link #memoryProperties}：
     * 那里装的是"<b>历史能有多长</b>"（token / 条数），这里装的是"<b>循环跑几轮</b>" ——
     * 两者是不同轴的量。合并会让 {@code MemoryTrimmer} 拿到一个它永不读的字段（坑 #12）。
     */
    private final AgentLoopProperties agentLoopProperties;

    /** 模型配置好的 options（系统提示词、模型名、reasoning-effort 等）。每步从它克隆再加工具。 */
    private final ChatOptions baseOptions;

    /** 模型可调用的工具。关掉检索时是空数组 —— 空数组是合法状态（退化成纯对话）。 */
    private final ToolCallback[] toolCallbacks;

    /** 执行工具并把结果拼进会话历史 —— 用框架实现，不自己写参数解析与异常处理。 */
    private final ToolCallingManager toolCallingManager;

    /**
     * 工具调用审计存储。<b>可能为 null</b>（{@code hagent.tool.audit.enabled=false}）。
     *
     * <p>用 {@code ObjectProvider} 取而不是直接注入：审计是<b>可关闭的</b>，
     * 而"关掉"在装配上的正确表现是"这个 Bean 不存在"（见 {@code ToolRegistration}）。
     * 直接按类型注入会让"关掉审计"变成启动失败。
     */
    private final ToolInvocationStore auditStore;

    /**
     * 长期记忆（阶段 11）—— <b>只用来读索引</b>；写由模型通过工具完成。
     *
     * <p>为什么在管线里读：索引必须进 system，而拼装只发生在 {@code PromptBuilder}。
     * 管线在"读历史"之后、"拼装"之前把索引读好交给它，保持 PromptBuilder 是<b>纯函数</b>。
     */
    private final LongTermMemoryStore longTermMemory;

    public AgentPipeline(ChatModel chatModel,
                         ConversationMemory memory,
                         ToolCallbackProvider toolCallbackProvider,
                         ObjectProvider<ToolInvocationStore> auditStoreProvider,
                         TraceProperties traceProperties,
                         PromptProperties promptProperties,
                         MemoryProperties memoryProperties,
                         AgentLoopProperties agentLoopProperties,
                         Resource ragAnswerPromptResource,
                         LongTermMemoryStore longTermMemory) {
        this.chatModel = chatModel;
        this.memory = memory;
        this.memoryProperties = memoryProperties;
        this.agentLoopProperties = agentLoopProperties;
        this.promptBuilder = new PromptBuilder(traceProperties, promptProperties, ragAnswerPromptResource);
        this.toolCallbacks = toolCallbackProvider.getToolCallbacks();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.auditStore = auditStoreProvider.getIfAvailable();
        this.longTermMemory = longTermMemory;

        // ★★ 启动自检：模型必须是 DeepSeek，否则"克隆配置 + 加工具"这条路走不通
        //    （getOptions() 返回的不是 DeepSeekChatOptions 就无法 mutate 出带工具的 DeepSeek options）。
        //    在这里失败，而不是等运行期发现 reasoning-effort 静默丢失。
        ChatOptions options = chatModel.getOptions();
        if (!(options instanceof DeepSeekChatOptions)) {
            throw new IllegalStateException(String.format("""
                    工具循环要求模型的 ChatOptions 是 DeepSeekChatOptions（实际是 %s）。
                    【为什么这是硬要求】带工具就必须显式传 ChatOptions，而显式传会覆盖模型配置的
                    默认值 —— 若新建一个裸 options，配置里的 reasoning-effort 与模型名会【静默丢失】，
                    表现为"思考过程忽然没了"且没有任何报错。本管线的做法是
                    从模型配置好的那份 options【克隆】再加工具，因此它必须是我们能 mutate 的类型。
                    【怎么修】确认 spring.ai.model.chat=deepseek 且未替换 ChatModel 实现；
                    若确实要换模型提供方，需要为它实现同样的"克隆 + 加工具"路径。
                    """, options == null ? "null" : options.getClass().getName()));
        }
        this.baseOptions = options;

        log.info("[管道] AgentPipeline 已就绪：直连 {}（不经 ChatClient）；已注册工具 {} 个：{}",
                chatModel.getClass().getSimpleName(),
                toolCallbacks.length,
                java.util.Arrays.stream(toolCallbacks)
                        .map(t -> t.getToolDefinition().name())
                        .toList());
    }

    // ========================================================================
    // 对外唯一入口
    // ========================================================================

    /**
     * 跑一次完整的对话流程。
     *
     * <p><b>身份为什么是方法参数</b>：它来自 HTTP 层的拦截器，经 Controller
     * 用 {@code @RequestAttribute} 取出后作为参数传进来 ——
     * <b>能编译期检查的那一段就留在编译期</b>。旧实现把它塞进 {@code context} 的字符串 key，
     * 拼错不报错、只在运行期表现为"检索莫名只看得到 PUBLIC"。
     *
     * @param message        已校验过的用户消息（非空）
     * @param conversationId 会话标识；空则落到 {@link #FALLBACK_CONVERSATION_ID}
     * @param mode           记忆模式（决定本次愿意为历史付多少预算）
     * @param userContext    调用方身份（决定检索可见范围）
     * @return 事件流（<b>冷流</b>，订阅后才发生真实调用）
     */
    public Flux<PipelineEvent> run(String message,
                                   String conversationId,
                                   MemoryMode mode,
                                   UserContext userContext) {
        String cid = (conversationId == null || conversationId.isBlank())
                ? FALLBACK_CONVERSATION_ID : conversationId;
        MemoryMode safeMode = mode == null ? MemoryMode.DEFAULT : mode;
        UserContext user = userContext == null ? UserContext.ANONYMOUS : userContext;

        Prompt original = new Prompt(List.of(new UserMessage(message)));
        TokenBudget budget = budgetOf(safeMode);
        // ★ 阶段 12：步数上限按模式取（chat 10 / workflow 20）。
        //   在 run() 里解析一次就定下来，融进本次运行的状态 —— 而不是循环里每步再 switch 一次。
        int maxSteps = maxStepsOf(safeMode);

        return Flux.defer(() -> {
            long startNanos = System.nanoTime();

            // ---------- ① 记请求（进入时的样子） ----------
            logRequest(cid, safeMode, user, original);

            // ---------- ② 读历史 ----------
            List<Message> history = memory.load(cid, budget);
            log.debug("[{}] ② 读历史：会话={} 模式={} 注入历史 {} 条（预算 {} token / {} 条，步数上限 {}）",
                    LOG_TAG, cid, safeMode, history.size(),
                    budget.maxTokens(), budget.maxMessages(), maxSteps);

            // ---------- ③ 暂无"无条件检索" ----------
            //   ★ 阶段 9 的重要行为变更：检索【不再】在管线里无条件执行。
            //     它现在是 retrieveKnowledge 工具，由【模型决定】何时调用。
            //     见 KnowledgeRetrievalTool 的类注释（三问的答案在那里）。
            //     于是"这次要不要查知识库"从代码决定变成了模型决定 ——
            //     这正是 RAG 与 agent 的实质差别。

            // ---------- ④ 拼装（system + 历史 + 长期记忆索引 + 本次提问） ----------
            //   ★ 阶段 11：长期记忆的索引在这一步注入。读失败不能影响对话 ——
            //     拿不到索引最坏只是"模型不知道用户的偏好"，而不是这轮请求失败。
            String memoryDigest = readMemoryDigest(user);
            Prompt assembled = promptBuilder.assemble(original, history, memoryDigest);

            // 本轮循环状态：从拼装好的 prompt 开始，随工具结果不断增长
            LoopState state = new LoopState(new ArrayList<>(assembled.getInstructions()),
                    original.getUserMessage(), maxSteps);
            // ★ 阶段 13：workflow 走"先拆解 → 逐步执行"；chat 的路径一个字都没动。
            if (safeMode == MemoryMode.WORKFLOW) {
                return runWorkflow(state, history, cid, user, startNanos);
            }
            return loop(state, cid, user, startNanos, LoopScope.CHAT);
        });
    }

    /**
     * 读长期记忆的索引块 —— <b>失败只降级成本轮不注入</b>。
     *
     * <p>与 {@code ConversationMemory#load} 同一立场：记忆是"让回答更好"的东西，
     * 不是"让回答成立"的东西。目录读不到、文件坏了、权限问题 —— 都不该让对话 500。
     *
     * <p>但<b>必须 WARN</b>：否则"记忆忽然不生效"会被当成模型变笨，
     * 而真正原因是磁盘/权限。
     */
    private String readMemoryDigest(UserContext user) {
        try {
            String digest = longTermMemory.renderIndexFor(user);
            log.debug("[{}] ④ 长期记忆索引：{} 字（用户={}）",
                    LOG_TAG, digest == null ? 0 : digest.length(), user.userId());
            return digest == null ? "" : digest;
        } catch (Exception e) {
            log.warn("[{}] 长期记忆索引读取失败，本轮不注入任何记忆（对话继续）：{}",
                    LOG_TAG, e.toString());
            return "";
        }
    }

    // ========================================================================
    // ⑤⑥⑦ 工具循环 —— 本阶段的核心
    // ========================================================================
    /**
     * 工具循环的可变状态。
     *
     * <p><b>为什么单独一个类，而不是几个局部变量</b>：循环是<b>递归</b>的
     * （每一步用 {@code concatWith(Flux.defer(...))} 接下一步），
     * 而 Java 的 lambda 只能捕获 effectively-final 变量 ——
     * 累积器必须"活"在 lambda 之外。放进一个对象比用一堆 {@code AtomicReference} 清楚得多。
     *
     * <p>★ 每订阅一次新建一个实例（在 {@code Flux.defer} 内部创建）——
     * 否则并发调用两条流会往同一组累积器里写，表现为"回答互相串台"且极难复现。
     */
    private static final class LoopState {
        /** 交给模型的完整消息列表：system + 历史 + 提问，之后不断追加 assistant / tool 消息。 */
        private final List<Message> messages;
        /** 整轮拼接后的回答（跨所有步骤）。 */
        private final StringBuilder text = new StringBuilder();
        /** 整轮拼接后的思考（跨所有步骤）。 */
        private final StringBuilder reasoning = new StringBuilder();
        /**
         * 每一步开始时 {@link #reasoning} 的<b>长度</b>（阶段 10）。
         *
         * <p><b>为什么记偏移量，而不是"每步攒一个 StringBuilder"</b>：
         * 思考是一条连续追加的流，步与步之间并没有天然的分割动作
         * （循环是递归的，两个出口都可能结束一整步）。
         * 记下"这一步从第几个字符开始"，两个出口都不需要做任何收尾工作 ——
         * 落库时按相邻偏移量切片，就得到每一步的思考。
         * <b>一份数据、一种增长方式、两处读取</b>，比"流式追加 + 到边界再 flush"少一类 bug。
         */
        private final List<Integer> reasoningOffsets = new ArrayList<>();
        /**
         * 步号（从 1 开始）→ 该步<b>之后</b>请求的工具名（阶段 10）。
         *
         * <p>用途：落库的思考标记要带上"这一步之后调了什么工具"
         * （{@code @@step:2 tool:retrieveKnowledge}）。
         * 只存工具名、不存参数与结果 —— 参数与结果在审计表里（决策 32）。
         */
        private final Map<Integer, List<String>> stepTools = new LinkedHashMap<>();
        /** 已执行的步数（= 已发生的模型调用次数）。 */
        private int steps;
        /** 最后一片响应 —— {@code Final} 的 finishReason / usage 取自它。 */
        private ChatResponse lastChunk;
        /** 本次的用户消息。<b>只有这一条会写进会话记忆</b>（工具活动不写）。 */
        private final Message userMessage;

        /**
         * 本次运行的步数上限 —— 由 {@code run()} 按模式解析一次（chat 10 / workflow 20）。
         *
         * <p>放在运行状态里而不是每步重新取：循环是递归的，同一个值会被读 N 次，
         * 而它<b>在一次运行内不可能变</b>（模式已落库、请求内不可改，决策 53）。
         */
        private final int maxSteps;

        /**
         * 审计里的步号起点（阶段 13）：chat 是 0（回合内第 N 次模型调用），
         * 工作流的每一步传"此前已用掉的模型调用数" —— 这样两种模式在
         * {@code chat_tool_invocation.step_index} 上是<b>同一个口径</b>：
         * 回合内第 N 次模型调用，而不是每个计划步各从 1 开始（那会让一次工作流里
         * 出现多个 {@code step_index=1}，事后无法定位）。
         */
        private final int stepIndexBase;

        /**
         * 本轮是否允许调工具（阶段 13-P2）。
         *
         * <p>工作流的<b>最后一步是"汇总"</b>：它该做的只是把前几步的结论合并成最终答复。
         * 实测汇总步仍会去检索（因为它手里只有 120 字摘要），把前面两步的查询又跑了一遍 ——
         * 既费钱又费时。所以汇总步<b>不挂工具</b>：没有检索能力，就只能合并已有材料。
         */
        private final boolean toolsEnabled;

        private LoopState(List<Message> messages, Message userMessage, int maxSteps) {
            this(messages, userMessage, maxSteps, 0, true);
        }

        private LoopState(List<Message> messages, Message userMessage, int maxSteps, int stepIndexBase) {
            this(messages, userMessage, maxSteps, stepIndexBase, true);
        }

        private LoopState(List<Message> messages, Message userMessage, int maxSteps,
                          int stepIndexBase, boolean toolsEnabled) {
            this.messages = messages;
            this.userMessage = userMessage;
            this.maxSteps = maxSteps;
            this.stepIndexBase = stepIndexBase;
            this.toolsEnabled = toolsEnabled;
        }
    }

    /**
     * 循环的"角色"（阶段 13）—— 把模式差异<b>从状态里挪到参数里</b>。
     *
     * <p>为什么不放在 {@link LoopState}：那两个布尔只为工作流存在，挂在 chat 的循环状态上
     * 就是"模式知识渗进共享状态" —— 再加一种模式，这里会变成一串布尔开关。
     *
     * @param terminal       是否终点：{@code false} 表示这是工作流里"某一步"的内层循环，
     *                       结束时<b>不写回、不产出 Final</b>
     * @param emitStepEvents 是否发 {@code StepStarted}：工作流的"步"是计划步，由外层发
     */
    private record LoopScope(boolean terminal, boolean emitStepEvents) {
        /** 普通回合：发步事件、结束时写回收尾。 */
        private static final LoopScope CHAT = new LoopScope(true, true);
        /** 工作流的一步：不发步事件、不写回、不产 Final。 */
        private static final LoopScope STEP = new LoopScope(false, false);
    }

    /**
     * 循环的每一步：调模型 → 有工具就执行并回灌 → 再走一步；没有工具就收尾。
     *
     * <h2>★ 终止条件（四条，缺一不可）</h2>
     *
     * <pre>
     *   ① 模型不再请求工具              ← 正常出口（模型决定）
     *   ② 工具 returnDirect=true        ← 工具决定结束（框架翻成 finishReason=returnDirect）
     *   ③ 步数达到 max-steps            ← 防御出口（我们决定）
     *   ④ 上游异常                      ← 错误出口（走 onError）
     * </pre>
     *
     * <p>★ <b>只靠①是危险的</b>：那是"由模型控制的出口"。模型若绕圈（反复请求同一个工具、
     * 或永远不停），循环不会自己结束，而<b>每一次循环都是一次计费的模型调用</b>。
     * 所以③必须有；②由工具自己声明（框架已支持）；④由 Reactor 的 onError 通道承担。
     *
     * <h2>★ 为什么要"克隆配置好的 options 再挂工具"（而不是新建一个）</h2>
     *
     * <p>阶段 8 用字节码确认过：{@code DeepSeekChatModel.buildRequestPrompt} 里
     * <pre>
     *   if (prompt.getOptions() == null) → 填入 this.getOptions()（配置里的那份）
     *   else                            → 原样使用（★ 配置被覆盖）
     * </pre>
     * 而<b>要带工具就必须显式传 options</b>（工具挂在 options 上）。
     * 于是"显式传"与"保住 reasoning-effort"直接冲突 ——
     * 若新建一个裸 options，配置里的 {@code reasoning-effort: HIGH} 与模型名<b>会静默丢失</b>，
     * 表现为"思考过程忽然没了"，而没有任何报错。
     *
     * <p><b>解法</b>：从模型配置好的那份 options <b>克隆</b>再加工具
     * （{@code getOptions().mutate().toolCallbacks(...).build()}）。
     * 实测字节码：{@code DeepSeekChatOptions.mutate()} 返回
     * {@code DeepSeekChatOptions.Builder}，而它继承
     * {@code DefaultToolCallingChatOptions.Builder<B>}（泛型自引用），
     * 所以链式 {@code .toolCallbacks(...)} 之后 {@code .build()} 仍返回 {@code DeepSeekChatOptions} ——
     * 原有字段全部保留。
     *
     * <p>构造器里已做一次启动自检：模型不是 DeepSeek 时<b>直接失败</b>，
     * 而不是等到运行时才发现思考没了。
     */
    private Flux<PipelineEvent> loop(LoopState state,
                                     String conversationId,
                                     UserContext user,
                                     long startNanos,
                                     LoopScope scope) {
        return Flux.defer(() -> {
            if (state.steps >= state.maxSteps) {
                // ---------- ③ 防御出口：不再调用模型，用已累积的内容收尾 ----------
                log.warn("[{}] ★ 达到步数上限 {}（可能模型在绕圈）—— 用已累积的 {} 字收尾。"
                                + "【排查方向】① 工具描述是否让模型误解了用途；"
                                + "② 工具返回内容是否让模型以为【还没做完】；"
                                + "③ hagent.agent.chat-max-steps / workflow-max-steps 是否需要调整",
                        LOG_TAG, state.maxSteps, state.text.length());
                return scope.terminal()
                        ? Flux.just(finish(state, conversationId, startNanos, "maxSteps"))
                        : Flux.empty();
            }
            state.steps++;

            // ---------- ★ 阶段 10：先发"第 N 步开始" ----------
            //   顺序必须在调模型【之前】：它是"这一步已经开始"的唯一信号。
            //   若等首片响应回来再发，模型思考的那十几秒里前端一个事件都收不到 ——
            //   而那正是用户最需要反馈的时间段。（见 StreamEventType.STEP 的注释）
            int step = state.steps;
            //   记下这一步的思考起点：落库时按相邻偏移量切出每一步的思考（见 LoopState 注释）
            state.reasoningOffsets.add(state.reasoning.length());

            // ---------- 用"克隆配置 + 挂工具 + 带身份"的 options 调模型 ----------
            Prompt prompt = new Prompt(state.messages, optionsFor(user, null, state.toolsEnabled));

            Flux<PipelineEvent> stepHead = scope.emitStepEvents()
                    ? Flux.just(new PipelineEvent.StepStarted(step))
                    : Flux.empty();
            return stepHead.concatWith(chatModel.stream(prompt)
                            .doOnNext(chunk -> {
                                state.lastChunk = chunk;
                                String text = textOf(chunk);
                                String reasoning = reasoningOf(chunk);
                                if (text != null && !text.isEmpty()) {
                                    state.text.append(text);
                                }
                                if (reasoning != null && !reasoning.isEmpty()) {
                                    state.reasoning.append(reasoning);
                                }
                            })
                            // ★ 过滤空片：实测首片是 text=null / reasoning=空串。不过滤会往 SSE 里发空事件
                            .flatMap(this::emit)
                            // ★★ 阶段 10 修：把阻塞段搬到 boundedElastic —— 这条链里【有阻塞调用】。
                            //
                            //   实测事故（接真实 WebClient 时必现，mock 模型时永远看不到）：
                            //     模型流的元素在 reactor-http-nio-*（Netty 事件循环）上发出
                            //     → 上游 onComplete 也在那条线程上触发
                            //     → 下面的 concatWith(Flux.defer(handleToolCalls)) 就在那里被订阅
                            //     → 工具里的阻塞调用（Ollama embedding 的 RestClient 底层要 Mono.block()）
                            //     → Reactor 直接拒绝：
                            //         IllegalStateException: block()/blockFirst()/blockLast() are blocking,
                            //         which is not supported in thread reactor-http-nio-4
                            //     → 检索每次都抛异常 → 被降级成"没命中" → 模型回答"知识库中未找到相关内容"
                            //
                            //   ★ 位置为什么必须【在这里】，而不是加在 run() 的返回链上：
                            //     publishOn 只影响它【下游】的操作符。handleToolCalls 是由
                            //     本方法的 concatWith 订阅的 —— 把它加在外层，等于把 publishOn
                            //     加在"订阅源头"的下游，管不到这里的订阅。
                            //     （实测：加在外层时假工具仍跑在 parallel-1 上，红。）
                            //
                            //   ★ 一条 publishOn 覆盖三处阻塞：工具执行、writeBack（JDBC）、
                            //     recordAudit（JDBC）都在 handleToolCalls 的同步段里；
                            //     而且每一轮递归都会走到这里，所以下一步同样被搬走。
                            //     JDBC 那两处【不抛错】（普通阻塞驱动，不经过 Reactor 的 block 检查），
                            //     只静默堵事件循环 —— 更隐蔽，同样需要这次搬迁。
                            //
                            //   ★ 为什么是 boundedElastic：它是 Reactor 专门给"会阻塞的任务"的池
                            //     （有上限、可复用）；parallel 只适合纯 CPU 且不许阻塞。
                            //
                            //   ★ 判据：**整条管道允许阻塞，所以整条管道都不该在事件循环上跑。**
                            //     逐点包 Mono.fromCallable(...).subscribeOn(...) 也能修，但那样
                            //     将来再往里加一个阻塞调用，又会忘。
                            .publishOn(Schedulers.boundedElastic())
                            // ★ 本步的完整响应（含 toolCalls）决定"继续还是收尾"。
                            //
                            //   ⚠️ 这里【必须】用 concatWith，不能用 then：
                            //     · concatWith → 先把上游事件（Reasoning/Token）全部发出去，
                            //                    等上游完成后再订阅下一步 —— 事件保留 ✅
                            //     · then       → 丢弃上游所有元素，只保留完成信号 —— 事件全丢 ❌
                            //   实测踩过：用 then 时前端只会收到一个 Final，
                            //   而断言会以 "Expected size: 3 but was: 1" 的形式暴露它。
                            .concatWith(Flux.defer(() -> handleToolCalls(state, conversationId, user, startNanos, scope))));
        });
    }

    /**
     * 本步结束后：有工具请求就执行并回灌、递归下一步；否则收尾。
     *
     * <p><b>为什么返回 {@code Flux} 而不是 {@code Mono}</b>：这条路径可能产出
     * <b>整条下一步的流</b>（递归），而不是单个元素。
     */
    private Flux<PipelineEvent> handleToolCalls(LoopState state,
                                                String conversationId,
                                                UserContext user,
                                                long startNanos,
                                                LoopScope scope) {
        return Flux.defer(() -> {
            ChatResponse response = state.lastChunk;
            if (response == null || !response.hasToolCalls()) {
                // ---------- ① 正常出口 ----------
                return scope.terminal()
                        ? Flux.just(finish(state, conversationId, startNanos,
                                response == null ? null : finishReasonOf(response)))
                        : Flux.empty();
            }

            // ---------- ② 执行工具并把结果回灌 ----------
            //   两者都交给框架的 ToolCallingManager：
            //   · 它负责按 JSON Schema 解析参数、反射调用、异常 → 文本
            //   · 它负责把 assistant(toolCalls) 与 tool(结果) 两条消息拼进 conversationHistory
            //   ★ "结果回流"不是框架自动做的 —— 是我们把 conversationHistory() 交回给模型的结果
            //     （阶段 9 动手前已用一次性测试实测过这一点）。
            List<AssistantMessage.ToolCall> requested = response.getResult().getOutput().getToolCalls();

            // ---------- 记下"这一步之后调了什么工具"（阶段 10）----------
            //   落库的思考标记要带它（@@step:2 tool:retrieveKnowledge），
            //   前端历史回放的"执行轨迹"面板也只靠这一项（参数与结果在审计表里）。
            state.stepTools.put(state.steps,
                    requested.stream().map(AssistantMessage.ToolCall::name).toList());

            // ---------- 先发 ToolCall 事件（用户要立刻看到"正在查…"）----------
            //   ★ 顺序很重要：执行工具可能很慢，先发"开始"才有反馈。
            //     若等执行完再一起发，前端在调用与结果之间没有任何可显示的东西。
            Flux<PipelineEvent> callEvents = Flux.fromIterable(requested)
                    .map(call -> (PipelineEvent) new PipelineEvent.ModelOutput.ToolCall(
                            call.id(), call.name(), call.arguments()));

            // ---------- 同步执行工具（框架的阻塞 API）----------
            //   ★ 这一步【必须是阻塞的】：不仅因为框架的 executeToolCalls 是同步方法，
            //     更因为"先发 ToolCall、再执行、再发 ToolResult"这个顺序需要它。
            //     而工具内部若要发事件（如检索发出 Retrieved），走 ToolContext 里的
            //     ToolEventSink —— 它也是同步的（见该类注释）。
            List<PipelineEvent> toolPhaseEvents = new ArrayList<>();
            Prompt prompt = new Prompt(state.messages, optionsFor(user, toolPhaseEvents::add, state.toolsEnabled));
            ToolExecutionResult execution =
                    toolCallingManager.executeToolCalls(prompt, response);

            state.messages.clear();
            state.messages.addAll(execution.conversationHistory());

            // ---------- 再发"工具执行期间产生的"事件 + ToolResult ----------
            List<PipelineEvent.ModelOutput.ToolResult> results = toolResultEvents(execution, requested);
            toolPhaseEvents.addAll(results);

            // ---------- ⑧ 写审计（阶段 9.3）----------
            //   ★ 与记忆写回一样：【不在关键路径上】。
            //     实现内部吞掉异常并记 ERROR（见 ToolInvocationStore 的类注释）——
            //     为"审计写不进去"丢掉已经产生的模型回复是双重损失。
            recordAudit(conversationId, state.stepIndexBase + state.steps, requested, results);

            log.debug("[{}] 第 {} 步：执行了 {} 个工具，历史消息数 {} → {}",
                    LOG_TAG, state.steps, requested.size(),
                    prompt.getInstructions().size(), state.messages.size());

            // ---------- 递归走下一步 ----------
            //   ★ 用 Flux.defer + concatWith 保持整条链响应式；不要 block()（那会在 SSE 线程上阻塞）。
            return callEvents.concatWith(Flux.fromIterable(toolPhaseEvents))
                    .concatWith(Flux.defer(() -> loop(state, conversationId, user, startNanos, scope)));
        });
    }

    /**
     * 把本步的每一次工具调用写进审计表。
     *
     * <p><b>为什么按"调用"逐条写，而不是把整步写成一条</b>：
     * 一轮可能有 N 次调用，每条都是一次独立的事实（调了什么、拿到什么）。
     * 合成一条会逼着查询方去解析那个合并结构 —— 正是本项目反对的
     * "结构化数据变文本、再解析回来"。
     *
     * <p><b>参数与结果按下标配对</b>：框架保证 {@code ToolResponse} 与 {@code ToolCall} 同序。
     * 若某次调用没有对应结果（理论上不该发生），{@code result} 为 null 而<b>记录照写</b> ——
     * 审计要如实反映"这次调用发生了"，而不是因为拿不到结果就整条不记。
     */
    private void recordAudit(String conversationId,
                             int stepIndex,
                             List<AssistantMessage.ToolCall> requested,
                             List<PipelineEvent.ModelOutput.ToolResult> results) {
        if (auditStore == null) {
            // ★ 明确告知"审计没在跑"，而不是静默不写。
            //   运维若以为审计一直在记录，出事时才会发现它是空的。
            log.warn("[{}] 审计存储不可用（hagent.tool.audit.enabled=false？）—— "
                    + "本次 {} 个工具调用【没有留下审计记录】", LOG_TAG, requested.size());
            return;
        }
        for (int i = 0; i < requested.size(); i++) {
            AssistantMessage.ToolCall call = requested.get(i);
            String result = i < results.size() ? results.get(i).result() : null;
            auditStore.record(new ToolInvocationStore.ToolInvocation(
                    conversationId, stepIndex, call.name(), call.arguments(), result));
        }
    }

    /**
     * 把工具返回的文本转成 {@link PipelineEvent.ModelOutput.ToolResult} 事件。
     *
     * <p>结果在 {@code conversationHistory} 的<b>最后一条</b> {@code ToolResponseMessage} 里
     * （框架的约定：assistant(工具请求) 之后紧跟 tool(结果)）。
     *
     * <p>★ 与请求配对：模型一次可能请求多个工具，框架保证响应与请求<b>同序</b>，
     * 所以这里按下标取 {@code id}。若框架将来不保证顺序，配对的 id 会错 ——
     * 但正文不受影响（刻意不让它成为关键路径）。
     */
    private List<PipelineEvent.ModelOutput.ToolResult> toolResultEvents(ToolExecutionResult execution,
                                                  List<AssistantMessage.ToolCall> requested) {
        List<Message> history = execution.conversationHistory();
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Message last = history.get(history.size() - 1);
        if (!(last instanceof ToolResponseMessage toolResponse)) {
            return List.of();
        }
        List<PipelineEvent.ModelOutput.ToolResult> events = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = toolResponse.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            ToolResponseMessage.ToolResponse r = responses.get(i);
            String id = (requested != null && i < requested.size()) ? requested.get(i).id() : null;
            events.add(new PipelineEvent.ModelOutput.ToolResult(id, r.name(), r.responseData()));
        }
        return events;
    }

    /**
     * 克隆"模型配置好的 options"，挂上工具、调用方身份与事件出口。
     *
     * <p>构造器已保证 {@code chatModel.getOptions()} 是 {@code DeepSeekChatOptions}
     * （不是就在启动期失败），所以这里的转换是安全的。
     *
     * @param sink 工具执行期间发事件的通道；没有工具要发事件时传 null 也可以
     *             （工具侧取不到出口是正常状态，不告警 —— 见 {@code KnowledgeRetrievalTool}）
     */
    private ToolCallingChatOptions optionsFor(UserContext user, ToolEventSink sink, boolean withTools) {
        var builder = DeepSeekChatOptions.class.cast(baseOptions)
                .mutate();
        if (withTools) {
            builder = builder.toolCallbacks(toolCallbacks);
        }
        builder = builder.toolContext(ToolContextSupport.USER, user);
        if (sink != null) {
            builder = builder.toolContext(ToolContextSupport.EVENT_SINK, sink);
        }
        return builder.build();
    }

    /** 收尾：写回记忆 + 产出 {@link PipelineEvent.ModelOutput.Final}。 */
    private PipelineEvent.ModelOutput.Final finish(LoopState state,
                                                   String conversationId,
                                                   long startNanos,
                                                   String finishReason) {
        writeBack(conversationId, state);
        return buildFinal(state.text, state.reasoning, finishReason, state.lastChunk, startNanos);
    }

    /**
     * 把整个回合的思考按"步"切成段，再拼成<b>落库形态</b>（带 {@code @@step:} 标记）。
     *
     * <p>切片数据全部来自 {@link LoopState}：{@code reasoningOffsets} 给出每步的起点，
     * {@code stepTools} 给出每步<b>之后</b>调了哪些工具。本方法<b>只做组装</b>，
     * 格式与容错都在 {@link ReasoningTrace} 里（那里也负责反向解析）。
     *
     * <p><b>★ 工具名挂在"下一步的标记"上，而不是自己那一步</b>：
     * 工具是在第 N 步<b>结束之后</b>才被执行的（模型先请求、框架再执行），
     * 所以它属于第 N 步与第 N+1 步之间的那个接缝。落库文本因此长这样：
     * <pre>
     *   我应该调用 getWeather          ← 第 1 步（无标记）
     *
     *   @@step:2 tool:getWeather       ← 上一步之后调了它，现在是第 2 步
     *   想清楚了                        ← 第 2 步
     * </pre>
     * 反过来（把工具挂在自己那一步）会让<b>第 1 步的工具名彻底丢失</b> ——
     * 因为第 1 步按约定不写标记（见 {@link ReasoningTrace}）。
     *
     * <p>⚠️ 一个已知的边角：若第 N 步请求了工具、执行完之后正好撞上
     * {@code max-steps} 上限，第 N+1 步不会发生，于是那一批工具名没有标记可挂。
     * 这是<b>降级路径</b>（模型大概率在绕圈），工具本身仍记在审计表里。
     *
     * @return 落库文本；<b>本回合根本没有思考时返回 null</b>（不写空串，见 ReasoningTrace）
     */
    private String markedReasoning(LoopState state) {
        int steps = state.reasoningOffsets.size();
        if (steps == 0 || state.reasoning.length() == 0) {
            return null;
        }
        List<ReasoningTrace.Section> sections = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            int from = state.reasoningOffsets.get(i);
            int to = (i + 1 < steps) ? state.reasoningOffsets.get(i + 1) : state.reasoning.length();
            sections.add(new ReasoningTrace.Section(
                    i + 1,
                    // ★ 取第 i 步（= 上一个 1-based 步号）请求的工具：它们是通向第 i+1 步的原因
                    state.stepTools.getOrDefault(i, List.of()),
                    state.reasoning.substring(from, to)));
        }
        return ReasoningTrace.render(sections);
    }

    /**
     * 把一片原始响应翻译成 0 或 1 个事件。
     *
     * <p>三种情况：
     * <ul>
     *   <li>有思考内容 → {@link PipelineEvent.ModelOutput.Reasoning}</li>
     *   <li>有正文内容 → {@link PipelineEvent.ModelOutput.Token}</li>
     *   <li>两者都没有（首片、收尾片）→ <b>不发事件</b></li>
     * </ul>
     *
     * <p>实测两段<b>完全互斥</b>（思考段 text 恒为 null，回答段 reasoning 恒为 null），
     * 所以同一片不会同时产出两种事件。
     */
    private Flux<PipelineEvent> emit(ChatResponse chunk) {
        String reasoning = reasoningOf(chunk);
        if (reasoning != null && !reasoning.isEmpty()) {
            return Flux.just(new PipelineEvent.ModelOutput.Reasoning(reasoning));
        }
        String text = textOf(chunk);
        if (text != null && !text.isEmpty()) {
            return Flux.just(new PipelineEvent.ModelOutput.Token(text));
        }
        return Flux.empty();
    }

    private PipelineEvent.ModelOutput.Final buildFinal(StringBuilder textAcc,
                                                       StringBuilder reasoningAcc,
                                                       String finishReason,
                                                       ChatResponse lastChunk,
                                                       long startNanos) {
        String text = textAcc.toString();
        String reasoning = reasoningAcc.toString();
        Usage usage = usageOf(lastChunk);
        long costMillis = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("[{}] ← 响应 | 耗时={}ms | 回答 {} 字 | 思考 {} 字 | finishReason={} | usage={}",
                LOG_TAG, costMillis, text.length(), reasoning.length(), finishReason, usage);
        if (reasoning.isEmpty()) {
            // ★ 取不到思考时明确告警，而不是静默为空：
            //   reasoningContent 是 DeepSeek 专属字段，换掉模型后这里会一直是空的，
            //   而"思考窗口一直没内容"若不报出来，会被当成前端 bug 排查很久。
            log.warn("[{}] 本次【未取到思考内容】—— 若预期有思考，请确认模型是否仍为 DeepSeek"
                    + "（reasoningContent 是该提供方的独有字段，取不到时无法降级）", LOG_TAG);
        }
        return new PipelineEvent.ModelOutput.Final(text, reasoning, finishReason, usage);
    }

    /**
     * 写回「本次问 + 模型最终答（含思考）」。
     *
     * <p><b>★ 阶段 9 起：只写这两条，不写工具活动</b>（决策 32 的延续）。
     * 循环过程中 {@code state.messages} 会不断长出 {@code assistant(toolCalls)} 与
     * {@code tool(结果)} 消息 —— 它们**不进会话记忆**：
     * <ul>
     *   <li>记录层是<b>用户可见的对话记录</b>；混进工具消息会让用户看到模型的"手机屏幕"
     *       （将来"给用户的完整历史"API 就得额外过滤）；</li>
     *   <li>上下文层是<b>模型这次要用的上下文</b>；工具结果是执行态，重放它既费 token
     *       又可能在下一轮造成困惑。</li>
     * </ul>
     * 工具活动另有归宿：审计表 {@code chat_tool_invocation}（阶段 9.3）。
     *
     * <h3>★ 阶段 10：思考也写进来了，但走的是 properties 而不是 reasoningContent</h3>
     *
     * <p>思考挂在 {@link AssistantMessage} 的 <b>properties</b>（键
     * {@link MessageCodec#REASONING_PROPERTY}）上，<b>不是</b>
     * {@code DeepSeekAssistantMessage.reasoningContent}。理由是一条实测：
     * {@code DeepSeekChatModel.createRequest} 里会
     * {@code instanceof DeepSeekAssistantMessage → getReasoningContent()}，
     * 把它<b>重新填进发出去的请求</b>；而 {@code reasoning_content} 是
     * <b>输出专用字段</b>，回传轻则白烧 token、重则 HTTP 400。
     *
     * <p>⇒ 用 properties 存：前端能显示，<b>模型永远看不到自己的历史思考</b>。
     * 这不是"暂时没做"，是刻意的（想让它记住结论，那是长期记忆的事，阶段 11）。
     * 有人若把它"简化"成 reasoningContent，症状会是"下一轮对话莫名失败"。
     *
     * <p><b>用户消息从哪取</b>：从<b>拼装前的原始 prompt</b> 取（{@code LoopState.userMessage}），
     * 而不是从循环中的消息列表取 —— 后者已经混进了 tool 消息，
     * "靠位置找最后一条 user" 会随循环增长而变脆。
     *
     * <p>写回失败<b>不影响本次响应</b> —— 失败分级在 {@code LayeredConversationMemory} 里，
     * 本方法无法也不应感知成败（见类注释的诚实声明）。
     */
    private void writeBack(String conversationId, LoopState state) {
        writeBack(conversationId, state.userMessage, state.text.toString(), markedReasoning(state),
                state.reasoningOffsets.size());
    }

    /**
     * 写回的<b>唯一实现</b>（阶段 13 起 chat 与工作流共用这一条）。
     *
     * <p>为什么参数是"文本 + 用户消息"而不是继续收 {@link LoopState}：工作流没有
     * "一个累积整轮回答的 LoopState"（它每步一个），第一版因此另写了一份
     * {@code writeBackTurn} —— 两条写回路径，将来改落库格式必然只改一处。
     *
     * @param reasoningTrace 分步思考标记（{@code @@step:N tool:...}），可为 null
     * @param stepCount      日志用：chat 里是模型调用步数，工作流里是计划步数
     */
    private void writeBack(String conversationId, Message userMessage, String replyText,
                           String reasoningTrace, int stepCount) {
        List<Message> turn = new ArrayList<>(2);
        if (userMessage != null) {
            turn.add(userMessage);
        }
        if (replyText != null && !replyText.isBlank()) {
            turn.add(reasoningTrace == null
                    ? new AssistantMessage(replyText)
                    : AssistantMessage.builder()
                            .content(replyText)
                            .properties(Map.of(MessageCodec.REASONING_PROPERTY, reasoningTrace))
                            .build());
        }
        if (turn.isEmpty()) {
            log.debug("[{}] 本次没有可写回的内容，跳过", LOG_TAG);
            return;
        }
        log.debug("[{}] 写回记忆：会话={} 消息 {} 条，思考 {} 字（分 {} 步）",
                LOG_TAG, conversationId, turn.size(),
                reasoningTrace == null ? 0 : reasoningTrace.length(),
                stepCount);
        memory.append(conversationId, turn);
    }

    // ========================================================================
    // 取值辅助 —— 供应商耦合集中在这两个方法里
    // ========================================================================

    /** 取文本；每片可能没有 result（例如只有 metadata 的收尾片）。 */
    private static String textOf(ChatResponse response) {
        return outputOf(response).map(AssistantMessage::getText).orElse(null);
    }

    /**
     * 取思考内容 —— ★ <b>只能靠 {@code instanceof}</b>。
     *
     * <p>实测：{@code reasoningContent} 是 {@link DeepSeekAssistantMessage} 的独有字段，
     * Spring AI 2.0.1 <b>没有</b>为它提供任何通用 metadata key
     * （{@code ChatGenerationMetadata} 有泛型 {@code get(String)}，但没有对应常量）。
     *
     * <p>所以本方法是全项目<b>唯一</b>的 DeepSeek 耦合点，刻意集中在此：
     * 换模型时这里编译失败或恒返回 null，比散落在多处要好定位得多。
     * 取不到返回 null（<b>不抛异常</b>）—— "没有思考"是合法状态，由 {@code buildFinal} 记 WARN。
     */
    private static String reasoningOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output instanceof DeepSeekAssistantMessage deepSeek) {
            return deepSeek.getReasoningContent();
        }
        return null;
    }

    private static java.util.Optional<AssistantMessage> outputOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(response.getResult().getOutput());
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /** 取使用量。框架用 {@code EmptyUsage} 表示"没有数据"，所以这里不返回 null。 */
    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    // ========================================================================
    // 阶段 13：工作流编排 —— 先拆解 → 逐步执行 → 失败重试 → 重规划一次
    // ========================================================================

    /**
     * 规划提示词在 <b>classpath:prompts/workflow-plan-prompt.md</b>（阶段 13 起），
     * 由 {@link com.hagent.prompt.WorkflowPlanPrompt} 读取。
     *
     * <p>为什么不再写成 Java 常量：本项目所有"给模型看的长文本"都是资源文件
     * （{@code rag-answer-prompt.md} 等），改提示词不该要求改 Java 并重新理解缩进。
     *
     * <p>★ <b>它和 {@link WorkflowPlanParser} 是一对契约</b>：提示词承诺的 JSON 形状，
     * 解析器必须能读；解析器拒绝的（步数超限、编号不连续），提示词里也要提前约束。
     */

    /**
     * 工作流：<b>先拆解，再逐步执行</b>（阶段 13 的核心）。
     *
     * <h2>★ 为什么整段是"阻塞跑在一个 boundedElastic 线程上"</h2>
     *
     * <p>规划是一次<b>同步</b>的模型调用，每一步又是"D 一次阻塞工具循环"。
     * Reactor 里把阻塞调用放在事件循环上会直接抛
     * {@code block() ... not supported in thread reactor-http-nio-*}（阶段 9 的真实事故）。
     * 所以规划调用用 {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} 包一层 ——
     * 与 chat 的 {@code publishOn(boundedElastic())} 同一个思路，且<b>取消/背压由 Reactor 负责</b>。
     *
     * <h2>★ 为什么不是 {@code Flux.create + blockLast}</h2>
     *
     * <p>第一版就是那样写的，代价是：断线取消要手写 {@code sink.isCancelled()}、失败要走返回值
     * 而不是 onError 通道、外层与 chat 的递归风格完全两样。阶段结束复盘时改成
     * {@code Mono + Flux.concat + Flux.defer} 递归 —— 与 chat 的
     * {@code concatWith(Flux.defer(...))} <b>同形</b>，取消与背压白拿。
     *
     * <h2>★ 降级：规划失败 <b>不</b>让请求失败</h2>
     *
     * <p>模型写不出合法 JSON 是可预期的失败（不像配置写错），而"用户拿不到任何回答"
     * 是更坏的结果。所以退回普通循环 —— 但<b>必须发 {@link PipelineEvent.PlanSkipped}</b>，
     * 否则用户会以为工作流跑过了。
     */
    private Flux<PipelineEvent> runWorkflow(LoopState seed,
                                            List<Message> history,
                                            String conversationId,
                                            UserContext user,
                                            long startNanos) {
        // ★ 构建标记：日志里必须能一眼看出"这次是哪套代码在跑"。
        //   真实事故：一次测试的日志里同时出现新旧两种行为（2 步/有 usage vs 6 步/usage=null），
        //   排查时无法判断应用是否重启过。这行是为此加的（验证期保留，可以随时删）。
        log.info("[{}] ★ 工作流外壳=reactive-unified / 规划提示词=P1（含最少步数与汇总约束）", LOG_TAG);
        return Mono.fromCallable(() -> draftPlan(seed, history, null, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(drafted -> {
                    if (drafted.plan() == null) {
                        log.warn("[{}] ★ 规划失败，退回普通循环（回答照常，但不保证按计划执行）：{}",
                                LOG_TAG, drafted.error());
                        return Flux.concat(
                                Flux.just(new PipelineEvent.PlanSkipped(drafted.error())),
                                loop(seed, conversationId, user, startNanos, LoopScope.CHAT));
                    }
                    log.info("[{}] 工作流计划：{} 步（{}）",
                            LOG_TAG, drafted.plan().size(), drafted.plan().goal());
                    WorkflowRun run = new WorkflowRun(drafted.plan(), seed);
                    return Flux.concat(
                            Flux.just(new PipelineEvent.PlanCreated(drafted.plan())),
                            nextStep(run, seed, history, conversationId, user, startNanos));
                });
    }

    /**
     * 下一步 —— 与 chat 的 {@link #loop} <b>同形</b>：{@code Flux.defer} 里判断"还能不能继续"，
     * 能就 {@code concatWith(Flux.defer(下一步))}，不能就产出收尾事件。
     *
     * <p>★ 它替代了第一版的 {@code while} + {@code blockLast()}：命令式外壳会让
     * "取消"必须手写检查，而递归的 Flux 链天然继承取消与背压。
     */
    private Flux<PipelineEvent> nextStep(WorkflowRun run, LoopState seed, List<Message> history,
                                         String conversationId, UserContext user, long startNanos) {
        return Flux.defer(() -> {
            if (!run.hasNext()) {
                return Flux.just(finishWorkflow(run, seed, conversationId, startNanos, null));
            }
            PlanStep step = run.current();
            LoopState stepState = newStepState(run, step, seed, history);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepStarted(step.index())),
                    loop(stepState, conversationId, user, startNanos, LoopScope.STEP),
                    Flux.defer(() -> afterStep(run, step, stepState, seed, history,
                            conversationId, user, startNanos)));
        });
    }

    /**
     * 一步结束后：成功 → 下一步；第一次失败 → <b>同步重试</b>；第二次失败 → <b>重规划一次</b>；
     * 再失败 → 放弃（把失败原因写进最终回答）。
     *
     * <p>★ 失败判定是<b>"有没有产出"</b>，不是"调了几次模型"：模型可能正常收尾却什么都不说
     * （工具失败后最常见的形态），那一步等于没做成。
     */
    private Flux<PipelineEvent> afterStep(WorkflowRun run, PlanStep step, LoopState stepState,
                                          LoopState seed, List<Message> history,
                                          String conversationId, UserContext user, long startNanos) {
        run.used += stepState.steps;
        run.lastChunk = stepState.lastChunk;
        String text = stepState.text.toString().trim();
        if (!text.isEmpty()) {
            run.recordSuccess(step, text, stepState);
            log.info("[{}] 第 {} 步完成：{} 字（累计模型调用 {} 次）",
                    LOG_TAG, step.index(), text.length(), run.used);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepFinished(step.index(), true, text)),
                    nextStep(run, seed, history, conversationId, user, startNanos));
        }
        String error = "这一步没有产出任何内容（模型未给出该步的结果）";
        String failure = "第 " + step.index() + " 步（" + step.goal() + "）失败：" + error;
        if (run.attempts++ == 0) {
            log.warn("[{}] 第 {} 步失败，同步重试一次：{}", LOG_TAG, step.index(), error);
            LoopState retryState = newStepState(run, step, seed, history);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepFinished(step.index(), false, error)),
                    Flux.just(new PipelineEvent.StepStarted(step.index())),
                    loop(retryState, conversationId, user, startNanos, LoopScope.STEP),
                    Flux.defer(() -> afterStep(run, step, retryState, seed, history,
                            conversationId, user, startNanos)));
        }
        if (run.replanned) {
            return Flux.just(finishWorkflow(run, seed, conversationId, startNanos, failure));
        }
        run.replanned = true;
        log.warn("[{}] 同步重试后仍失败，重规划一次：{}", LOG_TAG, failure);
        return Flux.concat(
                Flux.just(new PipelineEvent.StepFinished(step.index(), false, error)),
                Mono.fromCallable(() -> draftPlan(seed, history, run.plan.goal(), failure))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(drafted -> {
                            if (drafted.plan() == null) {
                                return Flux.just(finishWorkflow(run, seed, conversationId, startNanos,
                                        failure + "；重规划失败：" + drafted.error()));
                            }
                            run.restart(drafted.plan());
                            return Flux.concat(
                                    Flux.just(new PipelineEvent.PlanCreated(drafted.plan())),
                                    nextStep(run, seed, history, conversationId, user, startNanos));
                        }));
    }

    /**
     * 这一步发给模型的消息：system + <b>最近 2 轮历史</b> + 本步指令。
     *
     * <h2>★ 为什么是"最近 2 轮"，而不是全量历史</h2>
     *
     * <p>一次工作流里，模型会看到 <b>1 次规划提示词 + 每一步至少 1 次</b>提示词
     * （每步都是一次独立的工具循环，工具返回后还要再问一次）。既然它<b>能看到多次</b>，
     * 就没必要每次都塞全量历史 —— 8 步 × 50k token 的历史会让成本线性膨胀。
     * 最近 2 轮足以支撑"刚才那个呢"这类指代；更早的信息走"已完成摘要"。
     *
     * <p>反之，如果将来改成"一次提示词跑完全部步骤"，那就必须带全量已裁剪历史 ——
     * 那种情况下模型只有一次机会看到它。
     */
    private LoopState newStepState(WorkflowRun run, PlanStep step, LoopState seed, List<Message> history) {
        int remaining = Math.max(1, seed.maxSteps - run.used);
        List<Message> messages = new ArrayList<>(2 + history.size());
        messages.add(new SystemMessage(systemMessageOf(seed)));
        messages.addAll(recentTurns(history));
        messages.add(new UserMessage(stepInstruction(run, step, seed)));
        // ★ 汇总步（最后一步）不挂工具：它只能合并已有材料，不能再去检索 ——
        //   实测它会把前面几步的查询重跑一遍（同一 query、同一批片段）。
        boolean withTools = step.index() < run.plan.size();
        return new LoopState(messages, null, remaining, run.used, withTools);
    }

    /** 取最近 2 轮对话（一问一答算一轮，所以是末尾 4 条）。 */
    private static List<Message> recentTurns(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int keep = Math.min(history.size(), 4);
        return history.subList(history.size() - keep, history.size());
    }

    /** 该步的指令：原始问题 + 总目标 + 第 k/N 步 + 完成判据 + 已完成的部分。 */
    private String stepInstruction(WorkflowRun run, PlanStep step, LoopState seed) {
        StringBuilder sb = new StringBuilder();
        String question = questionOf(seed);
        if (!question.isEmpty()) {
            // ★ 必须带原始问题：只给规划器改写后的 goal，会丢掉用户原话里的细节与限定条件
            sb.append("用户的问题：\n").append(question).append("\n\n");
        }
        sb.append("总目标：").append(run.plan.goal()).append('\n');
        sb.append("当前第 ").append(step.index()).append('/').append(run.plan.size())
                .append(" 步：").append(step.goal()).append('\n');
        if (!step.doneWhen().isEmpty()) {
            sb.append("完成判据：").append(step.doneWhen()).append('\n');
        }
        if (!run.completed.isEmpty()) {
            sb.append("已完成的部分：\n");
            for (CompletedStep done : run.completed) {
                sb.append("- 第 ").append(done.index()).append(" 步（").append(done.goal())
                        .append("）：").append(brief(done.summary())).append('\n');
            }
        }
        sb.append("只做当前这一步，做完直接给出这一步的结果，不要提前做后面的步骤。\n");
        sb.append("输出要求：只给要点，不超过 300 字；不要写成完整文章，不要重复前面步骤已经说过的内容。");
        if (step.index() == run.plan.size()) {
            // ★ 最后一步必须是"汇总"：否则各步小作文会被原样拼起来（实测 6 步拼出 12187 字）
            sb.append("\n这是最后一步：请把前面各步的要点**合并**成给用户的最终答复 ——"
                    + "去掉重复、按用户能直接照着做的顺序组织，并保留每条的出处。");
        }
        return sb.toString();
    }

    /**
     * 汇总并收尾：<b>与 chat 走同一条写回路径</b>（{@link #writeBack}），
     * 失败时把"停在哪一步"写进回答。
     */
    private PipelineEvent.ModelOutput.Final finishWorkflow(WorkflowRun run, LoopState seed,
                                                           String conversationId, long startNanos,
                                                           String failure) {
        StringBuilder reply = new StringBuilder(run.narrative);
        if (failure != null) {
            reply.append("### 未完成\n\n").append(failure).append("\n\n已完成的步骤：\n");
            if (run.completed.isEmpty()) {
                reply.append("- 无\n");
            } else {
                for (CompletedStep done : run.completed) {
                    reply.append("- 第 ").append(done.index()).append(" 步：")
                            .append(done.goal()).append('\n');
                }
            }
        }
        String text = reply.toString().trim();
        if (failure == null && !run.completed.isEmpty()) {
            // ★ 成功时最终回答 = 【最后一步（汇总步）的产出】，而不是把各步小作文拼起来。
            //   实测拼接版：428+360+351 ≈ 1374 字，用户拿到的是"三小节小作文"，
            //   而他要的是"一份说明"。各步过程仍然实时流给了前端，不会丢。
            text = run.completed.get(run.completed.size() - 1).summary().trim();
        }
        if (text.isEmpty()) {
            text = "（工作流没有产出任何内容）";
        }
        writeBack(conversationId, seed.userMessage, text, markedReasoning(run.aggregate),
                run.completed.size());
        // ★ 阶段 13 修：原来这里传 null，导致工作流的 Final 永远 usage=null
        //   （实测日志：chat 有 usage、workflow 没有）—— 而阶段 14 的成本统计要靠它。
        return buildFinal(new StringBuilder(text), run.aggregate.reasoning,
                failure == null ? "stop" : "stepFailed", run.lastChunk, startNanos);
    }

    /**
     * 规划调用（<b>同步</b>，由调用方保证在 boundedElastic 上；<b>不带工具</b>）。
     *
     * <p>★ 带<b>全量已裁剪历史</b>：规划只发生一次或两次（重规划），让它看到上下文才可能拆对。
     * 每一步则只带最近 2 轮（见 {@link #newStepState}）—— 那是"能看到多次提示词"这个事实换来的省法。
     *
     * <p>★ 刻意<b>不带工具</b>：规划是"想清楚要做什么"，不该在执行前就调工具。于是直接
     * {@code new Prompt(messages)}，模型用配置里那份 options，规划阶段没有任何副作用。
     */
    private String draftPlanText(LoopState seed, List<Message> history,
                                 String previousGoal, String failure) {
        StringBuilder ask = new StringBuilder("用户的问题：\n").append(questionOf(seed));
        if (failure != null) {
            ask.append("\n\n上一次计划的执行情况：\n总目标：")
                    .append(previousGoal == null ? "" : previousGoal)
                    .append("\n失败原因：").append(failure)
                    .append("\n请重新规划：已完成的部分不要再安排，只安排剩下的步骤。");
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(WorkflowPlanPrompt.text()));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(ask.toString()));
        ChatResponse response = chatModel.call(new Prompt(messages));
        String text = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new InvalidPlanException("规划调用返回了空内容");
        }
        return text;
    }

    /** 规划：把 {@link InvalidPlanException} 收成"失败原因"，让上游只剩一条降级分支。 */
    private Drafted draftPlan(LoopState seed, List<Message> history,
                              String previousGoal, String failure) {
        try {
            return Drafted.ok(WorkflowPlanParser.parse(
                    draftPlanText(seed, history, previousGoal, failure)));
        } catch (InvalidPlanException e) {
            return Drafted.failed(e.getMessage());
        }
    }

    private static String questionOf(LoopState seed) {
        return seed.userMessage == null || seed.userMessage.getText() == null
                ? "" : seed.userMessage.getText().trim();
    }

    /** 规划结果：{@code plan} 与 {@code error} 恰有一个非 null。 */
    private record Drafted(com.hagent.workflow.WorkflowPlan plan, String error) {

        private static Drafted ok(com.hagent.workflow.WorkflowPlan plan) {
            return new Drafted(plan, null);
        }

        private static Drafted failed(String error) {
            return new Drafted(null, error);
        }
    }

    /** 从拼装好的消息里取回系统提示词 —— 工作流的每一步都要带着它，否则模型会忘掉人设与规则。 */
    private String systemMessageOf(LoopState state) {
        for (Message message : state.messages) {
            if (message instanceof SystemMessage system) {
                return system.getText() == null ? "" : system.getText();
            }
        }
        return "";
    }

    private static String brief(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    /**
     * 一次工作流运行的可变状态：计划进度 + 思考聚合。
     *
     * <p>★ 思考为什么聚合进一个 {@link LoopState}，而不是自己拼一份格式：
     * 落库的 {@code @@step:N tool:...} 标记逻辑在 {@link #markedReasoning} 里，
     * 复用它、并把"步号"设成<b>计划步号</b>，两种模式的历史回放格式就完全一致 ——
     * 否则会长出第二套标记格式，而这正是阶段 12 花整个阶段在清的那类问题。
     */
    private static final class WorkflowRun {
        private WorkflowPlan plan;
        private final List<CompletedStep> completed = new ArrayList<>();
        private final StringBuilder narrative = new StringBuilder();
        /** 只用它的 reasoning / reasoningOffsets / stepTools / steps 四个字段。 */
        private final LoopState aggregate;
        private int index = 1;
        private int used;
        private int attempts;
        private boolean replanned;
        /** 最后一步的收尾片 —— 汇总 {@code Final} 的 usage 从这里取（阶段 13 修：原先是 null）。 */
        private ChatResponse lastChunk;

        private WorkflowRun(WorkflowPlan plan, LoopState seed) {
            this.plan = plan;
            this.aggregate = new LoopState(new ArrayList<>(), seed.userMessage, seed.maxSteps);
        }

        private boolean hasNext() {
            return index <= plan.size();
        }

        private PlanStep current() {
            return plan.step(index);
        }

        private void restart(WorkflowPlan next) {
            this.plan = next;
            this.index = 1;
            this.attempts = 0;
        }

        private void recordSuccess(PlanStep step, String text, LoopState stepState) {
            completed.add(new CompletedStep(step.index(), step.goal(), text));
            narrative.append("### 第 ").append(step.index()).append(" 步：")
                    .append(step.goal()).append("\n\n").append(text).append("\n\n");
            aggregate.reasoningOffsets.add(aggregate.reasoning.length());
            aggregate.reasoning.append(stepState.reasoning);
            aggregate.stepTools.put(step.index(), stepState.stepTools.values().stream()
                    .flatMap(List::stream).toList());
            aggregate.steps = Math.max(aggregate.steps, step.index());
            index++;
            attempts = 0;
        }
    }

    private record CompletedStep(int index, String goal, String summary) {
    }

    // ========================================================================
    // 预算：模式 → 预算（唯一出处）
    // ========================================================================

    /**
     * 模式 → 预算。
     *
     * <p><b>★ 为什么用穷尽的 {@code switch} 且刻意不写 {@code default}</b>：
     * 不写 {@code default} 就等于对编译器说"任何我没列出的模式都走这里"，
     * 于是新增枚举常量时<b>编译器不再检查</b> —— 那正是这道保证失效的方式。
     * 现在新增 {@link MemoryMode} 会<b>编译失败</b>，而不是运行期在某处 NPE。
     *
     * <p><b>历史</b>：这段映射原本在 {@code MemoryBudgets} 里（阶段 6 后半抽出），
     * 当时的理由是"两个调用点（主链路切面 + 框架边界适配器）必须共用同一份"。
     * 阶段 8 删掉框架边界后<b>只剩一个调用点</b>，那个理由不成立了 ——
     * 而"新增模式会编译失败"这条保证<b>移到这里反而更强</b>（构造器里直接可读）。
     * 于是删掉 {@code MemoryBudgets} 这个"没有第二使用者的抽象"。
     *
     * <p><b>★ 数值来自各自那两项配置，不存在"共用一个大上限"</b>：
     * 那是阶段 5 修正过的坑 —— 用数量级掩盖歧义（"反正取最大的不会丢"）。
     */
    private TokenBudget budgetOf(MemoryMode mode) {
        return switch (mode) {
            case CHAT -> new TokenBudget(
                    memoryProperties.getChatMaxTokens(),
                    memoryProperties.getChatMaxMessages());
            case WORKFLOW -> new TokenBudget(
                    memoryProperties.getWorkflowMaxTokens(),
                    memoryProperties.getWorkflowMaxMessages());
        };
    }

    /**
     * 模式 → 步数上限（阶段 12：从 {@code MemoryProperties.maxSteps} 搬来，并按模式分档）。
     *
     * <p><b>★ 为什么是两档而不是一个全局值</b>：chat 的"合理步数"与 workflow 不是一个量级 ——
     * 前者是"查两次资料 + 追问 + 汇总"，后者每一步的工具返回都可能是整篇文档。
     * 用一个值必然二选一地错：取 10 会砍掉工作流的长任务，取 20 会让普通问答多绕 10 步的账单。
     *
     * <p>与 {@link #budgetOf} 一样用<b>穷尽 switch 且刻意不写 {@code default}</b>：
     * 新增 {@link MemoryMode} 时编译器会报错，而不是运行期落到某个默认值。
     *
     * <p>★ 与 {@code budgetOf} 是<b>两条不同的轴</b>：这里管"循环跑几轮"，
     * 那里管"历史能有多长"。不要因为签名相似就合并成一个对象 ——
     * {@code MemoryTrimmer} 只消费后者，合并会让它带一个永不读的字段（坑 #12）。
     */
    private int maxStepsOf(MemoryMode mode) {
        return switch (mode) {
            case CHAT -> agentLoopProperties.getChatMaxSteps();
            case WORKFLOW -> agentLoopProperties.getWorkflowMaxSteps();
        };
    }

    // ========================================================================
    // 日志
    // ========================================================================

    /**
     * 记"请求进来时的样子"。
     *
     * <p>此处记的是<b>注入前</b>的 prompt（只有本次用户消息）—— 它与
     * {@code PromptBuilder} 记的"模型真正收到的 prompt"是<b>两条不同的日志</b>，
     * 分工是刻意的：这里用来确认"入口是干净的"。
     *
     * <p>旧实现在最外层切面做这件事，因为切面链<b>单向转发</b>，
     * 外层看不到内层的注入。管道没有这个问题（顺序是代码行序），
     * 但"分两条日志"的分工保留 —— 它仍然是有用的对照。
     */
    private void logRequest(String conversationId, MemoryMode mode, UserContext user, Prompt original) {
        log.info("[{}] → 请求 | 会话={} 模式={} 身份={}({}) | 消息数={} | 内容={}",
                LOG_TAG, conversationId, mode,
                user.userId(), user.role(),
                original.getInstructions().size(),
                original.getContents());
    }
}
