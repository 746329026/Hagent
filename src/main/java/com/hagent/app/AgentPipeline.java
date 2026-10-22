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
import com.hagent.observability.TurnRecord;
import com.hagent.observability.TurnStats;
import com.hagent.observability.TurnStatus;
import com.hagent.observability.TurnStore;
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
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 管道 —— <b>本项目唯一的编排者</b>：把"一次对话"拆成固定顺序的步骤，直连 {@link ChatModel}。
 *
 * <p>参数与状态都是<b>方法参数 / 对象字段</b>，不用切面之间"字符串 key 传数据"那套约定（key 拼错
 * 的表现是"注入永远为空，且不报错"）。直连 {@code ChatModel} 还意味着框架自动装配的切面不参与 ——
 * 工具循环的重复调用是我们显式写的，不是隐式的。
 *
 * <h2>固定顺序（顺序就是代码行序）</h2>
 *
 * <pre>
 *   ① 记请求            ← 注入前的样子，用来确认"入口是干净的"
 *   ② 读历史            ← 按模式的 token / 条数预算
 *   ③ 无"无条件检索"    ← 检索是 retrieveKnowledge 工具，由模型决定何时调
 *   ④ 拼装              ← 委托 PromptBuilder（唯一重建消息列表者）
 *   ⑤ 发 StepStarted(N) ← 调模型【之前】先发"第 N 步开始"
 *   ⑥ 调模型            ← chatModel.stream(prompt)，逐片累积 text / reasoning
 *   ⑦ 有工具 → 执行并回灌 → 回到 ⑤（N+1）；没有工具 → 收尾
 *   ⑧ 收尾：写回记忆（问 + 答 + 思考）→ 发出 Final
 *   ⑨ 本轮统计          ← TurnFinished 事件 + doFinally 落账（成功/失败/取消三出口都记）
 * </pre>
 *
 * <h2>★ 四条来自实测的硬约束（改本类前必读）</h2>
 *
 * <ol>
 *   <li><b>每片是增量，必须自己拼接</b> —— 实测 184 片中最后一片 text 长度为 0，取最后一片会得到空回答。</li>
 *   <li><b>空片必须过滤</b> —— 实测首片是 {@code text=null, reasoning=空串}，不过滤会往 SSE 里发空事件。</li>
 *   <li><b>不传 {@code ChatOptions}</b> —— 传了会覆盖配置里那份（{@code reasoning-effort}、模型名
 *       静默丢失）。<b>带工具的那一步是例外</b>，见 {@code optionsFor}。</li>
 *   <li><b>写回必须在模型成功之后</b> —— 失败调用不留半条记录，预算也天然不含当前问题。</li>
 * </ol>
 *
 * <h2>★ 为什么返回 {@code Flux}、操作符怎么选（每条都有具体理由，不要随手改）</h2>
 *
 * <ul>
 *   <li>两条入口（阻塞 / SSE）消费<b>同一份冷流</b>：不订阅不发生任何调用，读历史 / 拼装只写一遍；</li>
 *   <li>{@code Flux.defer} 包住整个流程 —— 让<b>前置步骤抛出的异常</b>也走 {@code onError} 通道，
 *       调用方因此只需处理一条错误路径；</li>
 *   <li>累积器建在 {@code defer} <b>内部</b> —— 建在方法里，并发两条流会往同一个 {@code StringBuilder}
 *       写，表现为"回答互相串台"且极难复现；</li>
 *   <li>写回用 {@code concatWith(Flux.defer(...))} 而不是 {@code doOnComplete} —— 必须保证写回发生在
 *       Final 发出之前，否则前端收到 Final 时历史还没落库。</li>
 * </ul>
 *
 * <p><b>★ 写回成败不可观测（诚实声明）</b>：{@link ConversationMemory#append} 返回 void 且内部吞掉
 * 异常（只打 ERROR），所以 {@link PipelineEvent.ModelOutput.Final} 里<b>刻意不带</b>"写回是否成功"
 * 之类字段（带了就是编造）。写回成败的唯一可观测渠道是日志。
 */
@Slf4j
@Service
public class AgentPipeline {

    private static final String LOG_TAG = "AgentPipeline";

    /**
     * 调用方没传会话 ID 时的兜底值。
     *
     * <p>读与写都出自本类，所以"读的兜底值必须等于写的兜底值"这条跨组件的约定不再需要。
     */
    public static final String FALLBACK_CONVERSATION_ID = "default";

    private final ChatModel chatModel;
    private final ConversationMemory memory;
    private final PromptBuilder promptBuilder;
    private final MemoryProperties memoryProperties;

    /**
     * 步数上限 —— 按模式两档。
     *
     * <p>独立于 {@link #memoryProperties}：那里装的是"<b>历史能有多长</b>"（token / 条数），这里装的是
     * "<b>循环跑几轮</b>"，两者是不同轴的量；合并会让 {@code MemoryTrimmer} 拿到一个它永不读的字段。
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
     * <p>用 {@code ObjectProvider} 取：审计是<b>可关闭的</b>，而"关掉"在装配上的正确表现是"这个 Bean
     * 不存在"；直接按类型注入会让"关掉审计"变成启动失败。
     */
    private final ToolInvocationStore auditStore;

    /**
     * 长期记忆 —— <b>只用来读索引</b>；写由模型通过工具完成。
     *
     * <p>为什么在管线里读：索引必须进 system，而拼装只发生在 {@code PromptBuilder}。管线在"读历史"
     * 之后、"拼装"之前把索引读好交给它，以保持 PromptBuilder 是<b>纯函数</b>。
     */
    private final LongTermMemoryStore longTermMemory;

    /**
     * 回合账的存储 —— <b>必填</b>，不是 {@code ObjectProvider}。
     *
     * <p>与 {@link #auditStore} 刻意不同：工具审计<b>可关闭</b>（表增长快），所以"关掉"在装配上的
     * 正确表现是"Bean 不存在"；而回合账<b>没有开关</b>，它应当一定存在 —— 用 {@code ObjectProvider}
     * 只会让"忘了装配"退化成"静默不记"，而那种缺失是看不见的。
     */
    private final TurnStore turnStore;

    public AgentPipeline(ChatModel chatModel,
                         ConversationMemory memory,
                         ToolCallbackProvider toolCallbackProvider,
                         ObjectProvider<ToolInvocationStore> auditStoreProvider,
                         TraceProperties traceProperties,
                         PromptProperties promptProperties,
                         MemoryProperties memoryProperties,
                         AgentLoopProperties agentLoopProperties,
                         Resource ragAnswerPromptResource,
                         LongTermMemoryStore longTermMemory,
                         TurnStore turnStore) {
        this.chatModel = chatModel;
        this.memory = memory;
        this.memoryProperties = memoryProperties;
        this.agentLoopProperties = agentLoopProperties;
        this.promptBuilder = new PromptBuilder(traceProperties, promptProperties, ragAnswerPromptResource);
        this.toolCallbacks = toolCallbackProvider.getToolCallbacks();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.auditStore = auditStoreProvider.getIfAvailable();
        this.longTermMemory = longTermMemory;
        this.turnStore = turnStore;

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

    // ---- 对外唯一入口 ----

    /**
     * 跑一次完整的对话流程。
     *
     * <p><b>身份为什么是方法参数</b>：它来自 HTTP 层的拦截器，经 Controller 作为参数传进来 ——
     * <b>能编译期检查的那一段就留在编译期</b>。旧实现塞进 {@code context} 的字符串 key，拼错不报错、
     * 只在运行期表现为"检索莫名只看得到 PUBLIC"。
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
        // ★ 步数上限按模式取（chat 10 / workflow 20）。在 run() 里解析一次就融进本次运行状态 ——
        //   而不是循环里每步再 switch 一次。
        int maxSteps = maxStepsOf(safeMode);

        return Flux.defer(() -> {
            // ★ 一轮的账本。建在 defer 内部（与 LoopState 同理）——
            //   建在方法里会让并发两条流往同一组计数器写，表现为"耗时 / token 互相串台"。
            TurnStats stats = new TurnStats(cid, user.userId(), user.role().name(),
                    safeMode.wireName());

            // ---------- ① 记请求（进入时的样子） ----------
            logRequest(cid, safeMode, user, original);

            // ---------- ② 读历史 ----------
            List<Message> history = memory.load(cid, budget);
            log.debug("[{}] ② 读历史：会话={} 模式={} 注入历史 {} 条（预算 {} token / {} 条，步数上限 {}）",
                    LOG_TAG, cid, safeMode, history.size(),
                    budget.maxTokens(), budget.maxMessages(), maxSteps);

            // ---------- ③ 无"无条件检索" ----------
            //   ★ 检索【不再】在管线里无条件执行，它现在是 retrieveKnowledge 工具，由【模型决定】
            //     何时调用 —— "这次要不要查知识库"从代码决定变成模型决定，这正是 RAG 与 agent 的
            //     实质差别（见 KnowledgeRetrievalTool 的类注释）。

            // ---------- ④ 拼装（system + 历史 + 长期记忆索引 + 本次提问） ----------
            //   ★ 长期记忆的索引在这一步注入。读失败不能影响对话 ——
            //     最坏只是"模型不知道用户的偏好"，而不是这轮请求失败。
            String memoryDigest = readMemoryDigest(user);
            Prompt assembled = promptBuilder.assemble(original, history, memoryDigest);

            // 本轮循环状态：从拼装好的 prompt 开始，随工具结果不断增长
            LoopState state = new LoopState(new ArrayList<>(assembled.getInstructions()),
                    original.getUserMessage(), maxSteps, 0, stats);
            // ★ workflow 走"先拆解 → 逐步执行"；chat 的路径完全相同，只是不拆解。
            Flux<PipelineEvent> turn = (safeMode == MemoryMode.WORKFLOW)
                    ? runWorkflow(state, history, cid, user)
                    : loop(state, cid, user, LoopScope.CHAT);

            return turn
                    // ---------- ★ 正常收尾时把这一轮的账外发 ----------
                    //   位置很关键：必须在 Final 之后（前端先有回答，再有数字），
                    //   且必须在流完成之前（doFinally 发不出元素）。
                    .concatWith(Flux.defer(() -> Flux.just(
                            new PipelineEvent.TurnFinished(stats.finish(TurnStatus.SUCCESS)))))
                    // ---------- ★ 唯一的收尾点：成功 / 失败 / 取消都写一次账 ----------
                    //   doOnError 先记下原因，doFinally 再判定结局 —— 顺序不能换
                    //   （doFinally 里读的是 noteError 写进去的那条 message）。
                    .doOnError(stats::noteError)
                    .doFinally(signal -> recordTurn(stats, signal));
        });
    }

    /**
     * 把这一轮的账写进 {@code chat_turn} —— <b>全项目唯一的落账点</b>。
     *
     * <h3>★ 为什么是 {@code doFinally}</h3>
     *
     * <p>一轮有<b>三种</b>收尾方式，走三条不同的通道：正常完成（onComplete，有 Final）、上游异常
     * （onError，连 {@code chat_message} 都没有）、客户端断开（cancel）。若只在成功路径打点，
     * <b>成功率永远是 100%</b> —— 而"失败与正常同形"正是本项目反复在防的那类错误。{@code doFinally}
     * 是 Reactor 里<b>唯一</b>能同时看到这三条通道的位置。
     *
     * <h3>★ 为什么<b>同步</b>落库（不在 boundedElastic 上异步发）</h3>
     *
     * <p>到这里时信号已在 {@code publishOn(boundedElastic)} 的下游（见 {@code loop}），与同一条链里的
     * {@code recordAudit} / {@code writeBack} 同一处境：<b>整条管道允许阻塞，所以整条管道都不该在事件
     * 循环上跑</b>。异步发会让"账是否写完"变得不确定 —— 而测试与排查都需要它确定。
     */
    private void recordTurn(TurnStats stats, SignalType signal) {
        TurnStatus observed = switch (signal) {
            case CANCEL -> TurnStatus.CANCELLED;
            case ON_ERROR -> TurnStatus.FAILED;
            default -> TurnStatus.SUCCESS;
        };
        TurnRecord record = stats.finish(observed);
        turnStore.record(record);
        if (record.status() == TurnStatus.SUCCESS) {
            log.debug("[{}] 回合账：轮次={} 状态={} 耗时={}ms 首字={}ms 模型={}次 工具={}次/{}ms token={}"
                            + "（缺 {} 次）",
                    LOG_TAG, record.turnId(), record.status(), record.elapsedMs(),
                    record.firstTokenMs(), record.modelCalls(), record.toolCalls(),
                    record.toolMs(), record.totalTokens(), record.usageMissingCalls());
        } else {
            // ★ 非成功必须可见：它是成功率的分子之一，也是"用户说卡住了"的第一条线索
            log.warn("[{}] 回合账：轮次={} 状态={} 耗时={}ms 模型={}次 token={}（缺 {} 次）原因={}",
                    LOG_TAG, record.turnId(), record.status(), record.elapsedMs(),
                    record.modelCalls(), record.totalTokens(), record.usageMissingCalls(),
                    record.errorMessage());
        }
    }

    /**
     * 读长期记忆的索引块 —— <b>失败只降级成本轮不注入</b>。
     *
     * <p>记忆是"让回答更好"的东西，不是"让回答成立"的东西 —— 目录读不到、文件坏了、权限问题都不该
     * 让对话 500。<b>但必须 WARN</b>：否则"记忆忽然不生效"会被当成模型变笨，而真正原因是磁盘/权限。
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

    // ---- ⑤⑥⑦ 工具循环 ----
    /**
     * 工具循环的可变状态。
     *
     * <p><b>为什么单独一个类，而不是几个局部变量</b>：循环是<b>递归</b>的（每一步用
     * {@code concatWith(Flux.defer(...))} 接下一步），而 Java 的 lambda 只能捕获 effectively-final
     * 变量 —— 累积器必须"活"在 lambda 之外。
     *
     * <p>★ 每订阅一次新建一个实例（在 {@code Flux.defer} 内部创建），否则并发两条流会往同一组累积器
     * 里写，表现为"回答互相串台"且极难复现。
     */
    private static final class LoopState {
        /** 交给模型的完整消息列表：system + 历史 + 提问，之后不断追加 assistant / tool 消息。 */
        private final List<Message> messages;
        /** 整轮拼接后的回答（跨所有步骤）。 */
        private final StringBuilder text = new StringBuilder();
        /** 整轮拼接后的思考（跨所有步骤）。 */
        private final StringBuilder reasoning = new StringBuilder();
        /**
         * 每一步开始时 {@link #reasoning} 的<b>长度</b>。
         *
         * <p><b>为什么记偏移量而不是"每步攒一个 StringBuilder"</b>：思考是一条连续追加的流，步与步之间
         * 没有天然的分割动作，记下起点后两个出口都不需要任何收尾 —— 落库时按相邻偏移量切片即可。
         */
        private final List<Integer> reasoningOffsets = new ArrayList<>();
        /**
         * 步号（从 1 开始）→ 该步<b>之后</b>请求的工具名。
         *
         * <p>落库的思考标记要带上它（{@code @@step:2 tool:retrieveKnowledge}）；只存工具名，参数与
         * 结果在审计表里。
         */
        private final Map<Integer, List<String>> stepTools = new LinkedHashMap<>();
        /** 已执行的步数（= 已发生的模型调用次数）。 */
        private int steps;
        /**
         * 最后一片响应 —— {@code Final} 的 <b>finishReason</b> 取自它。
         *
         * <p>★ 它<b>不</b>提供 {@code usage}：那个数由 {@link #stats} 整轮累加（取最后一片等于只记
         * 最后一次调用 —— 实测一轮 workflow 调 7 次、只记了最后一次）。
         */
        private ChatResponse lastChunk;
        /** 本次的用户消息。<b>只有这一条会写进会话记忆</b>（工具活动不写）。 */
        private final Message userMessage;

        /**
         * 这一轮的账 —— <b>工作流的每一步共享同一个实例</b>。
         *
         * <p>★ 必须由 {@code run()} 创建后一路传下来，不能在 {@code newStepState} 里新建：那样每步都从
         * 0 开始计，一次 3 步的工作流只会统计到最后一步。
         */
        private final TurnStats stats;

        /**
         * 本次运行的步数上限 —— 由 {@code run()} 按模式解析一次（chat 10 / workflow 20）。
         *
         * <p>放在运行状态里而不是每步重新取：它在一次运行内<b>不可能变</b>（模式已落库、请求内不可改）。
         */
        private final int maxSteps;

        /**
         * 审计里的步号起点：chat 是 0（回合内第 N 次模型调用），工作流每步传"此前已用掉的模型调用数"
         * —— 这样两种模式在 {@code chat_tool_invocation.step_index} 上是<b>同一个口径</b>，
         * 而不是每个计划步各从 1 开始（那会让一次工作流里出现多个 {@code step_index=1}，事后无法定位）。
         */
        private final int stepIndexBase;

        private LoopState(List<Message> messages, Message userMessage,
                          int maxSteps, int stepIndexBase, TurnStats stats) {
            this.messages = messages;
            this.userMessage = userMessage;
            this.maxSteps = maxSteps;
            this.stepIndexBase = stepIndexBase;
            this.stats = stats;
        }
    }

    /**
     * 循环的"角色" —— 把模式差异<b>从状态里挪到参数里</b>。
     *
     * <p>为什么不放进 {@link LoopState}：那两个布尔只为工作流存在，挂在 chat 的循环状态上就是
     * "模式知识渗进共享状态" —— 再加一种模式，这里会变成一串布尔开关。
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
     * <p>★ <b>只靠①是危险的</b>：那是"由模型控制的出口"，模型若绕圈（反复请求同一个工具、或永远
     * 不停），循环不会自己结束，而<b>每一次循环都是一次计费的模型调用</b> —— 所以③必须有。
     *
     * <h2>★ 为什么要"克隆配置好的 options 再挂工具"（而不是新建一个）</h2>
     *
     * <p>框架只在 {@code prompt.getOptions() == null} 时填入配置里那份 options，而<b>要带工具就
     * 必须显式传 options</b>。若新建一个裸 options，配置里的 {@code reasoning-effort: HIGH} 与模型名
     * <b>会静默丢失</b>，表现为"思考过程忽然没了"而没有任何报错。
     *
     * <p><b>解法</b>：{@code getOptions().mutate().toolCallbacks(...).build()} ——
     * {@code DeepSeekChatOptions.mutate()} 返回的 builder 继承泛型自引用的
     * {@code DefaultToolCallingChatOptions.Builder}，所以链式 {@code .toolCallbacks(...)} 之后
     * {@code .build()} 仍返回 {@code DeepSeekChatOptions}，原有字段全部保留。构造器里已做启动自检。
     */
    private Flux<PipelineEvent> loop(LoopState state,
                                     String conversationId,
                                     UserContext user,
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
                        ? Flux.just(finish(state, conversationId, "maxSteps"))
                        : Flux.empty();
            }
            state.steps++;

            // ---------- ★ 先发"第 N 步开始" ----------
            //   顺序必须在调模型【之前】：它是"这一步已经开始"的唯一信号。若等首片响应回来再发，
            //   模型思考的那十几秒里前端一个事件都收不到 —— 而那正是用户最需要反馈的时间段。
            int step = state.steps;
            //   记下这一步的思考起点：落库时按相邻偏移量切出每一步的思考（见 LoopState 注释）
            state.reasoningOffsets.add(state.reasoning.length());

            // ---------- 用"克隆配置 + 挂工具 + 带身份"的 options 调模型 ----------
            Prompt prompt = new Prompt(state.messages, optionsFor(user, null));

            // ★ 在"发起调用"时就计数 —— 失败的那次同样花了钱、同样让用户等了
            state.stats.noteModelCallStart();

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
                                // ★ 首个 token 的到达时刻（幂等，只有第一次生效）：它是"慢在等首字"与
                                //   "慢在后半段"的分界 —— 只记总耗时的话，这两种慢看起来一模一样。
                                if ((text != null && !text.isEmpty())
                                        || (reasoning != null && !reasoning.isEmpty())) {
                                    state.stats.noteFirstToken();
                                }
                            })
                            // ★ 这一次模型调用的用量在【这一次流完成时】结算：流式路径上 usage 只出现在
                            //   收尾片，每片都记会把同一个数重复累加 N 次。
                            .doOnComplete(() -> state.stats.noteModelUsage(usageOf(state.lastChunk)))
                            // ★ 过滤空片：实测首片是 text=null / reasoning=空串。不过滤会往 SSE 里发空事件
                            .flatMap(this::emit)
                            // ★★ 把阻塞段搬到 boundedElastic —— 这条链里【有阻塞调用】。
                            //
                            //   实测事故（接真实 WebClient 时必现，mock 模型时永远看不到）：模型流的
                            //   元素在 reactor-http-nio-*（Netty 事件循环）上发出 → 上游 onComplete 也
                            //   在那条线程上触发 → 下面的 concatWith(Flux.defer(handleToolCalls)) 就在
                            //   那里被订阅 → 工具里的阻塞调用（embedding 客户端底层要 Mono.block()）
                            //   被 Reactor 直接拒绝："block()/blockFirst()/blockLast() are blocking,
                            //   which is not supported in thread reactor-http-nio-4" → 每次检索抛异常
                            //   → 降级成"没命中" → 模型回答"知识库中未找到相关内容"。
                            //
                            //   ★ 位置必须【在这里】，不能加在 run() 的返回链上：publishOn 只影响它
                            //     【下游】的操作符，而 handleToolCalls 是由本方法的 concatWith 订阅的。
                            //   ★ 一条 publishOn 覆盖三处阻塞：工具执行、writeBack（JDBC）、recordAudit
                            //     （JDBC）都在 handleToolCalls 的同步段里，而且每一轮递归都走到这里。
                            //     JDBC 那两处【不抛错】（普通阻塞驱动），只静默堵事件循环 —— 更隐蔽。
                            //   ★ 为什么是 boundedElastic：它专给"会阻塞的任务"（有上限、可复用）；
                            //     parallel 只适合纯 CPU 且不许阻塞。
                            //   ★ 判据：**整条管道允许阻塞，所以整条管道都不该在事件循环上跑。**
                            //     逐点包 subscribeOn 也能修，但将来再加一个阻塞调用又会忘。
                            .publishOn(Schedulers.boundedElastic())
                            // ★ 本步的完整响应（含 toolCalls）决定"继续还是收尾"。
                            //
                            //   ⚠️ 这里【必须】用 concatWith，不能用 then：前者先发完上游事件
                            //     （Reasoning / Token）再订阅下一步，事件保留；后者丢弃上游所有元素、
                            //     只保留完成信号 —— 实测踩过，前端只会收到一个 Final，而断言会以
                            //     "Expected size: 3 but was: 1" 的形式暴露它。
                            .concatWith(Flux.defer(() -> handleToolCalls(state, conversationId, user, scope))));
        });
    }

    /**
     * 本步结束后：有工具请求就执行并回灌、递归下一步；否则收尾。
     *
     * <p><b>为什么返回 {@code Flux} 而不是 {@code Mono}</b>：这条路径可能产出<b>整条下一步的流</b>
     * （递归），而不是单个元素。
     */
    private Flux<PipelineEvent> handleToolCalls(LoopState state,
                                                String conversationId,
                                                UserContext user,
                                                LoopScope scope) {
        return Flux.defer(() -> {
            ChatResponse response = state.lastChunk;
            if (response == null || !response.hasToolCalls()) {
                // ---------- ① 正常出口 ----------
                return scope.terminal()
                        ? Flux.just(finish(state, conversationId,
                                response == null ? null : finishReasonOf(response)))
                        : Flux.empty();
            }

            // ---------- ② 执行工具并把结果回灌 ----------
            //   两者都交给框架的 ToolCallingManager：它按 JSON Schema 解析参数、反射调用、异常 → 文本，
            //   并把 assistant(toolCalls) 与 tool(结果) 两条消息拼进 conversationHistory。
            //   ★ "结果回流"不是框架自动做的 —— 是我们把 conversationHistory() 交回给模型的结果。
            List<AssistantMessage.ToolCall> requested = response.getResult().getOutput().getToolCalls();

            // ---------- 记下"这一步之后调了什么工具" ----------
            //   落库的思考标记要带它（@@step:2 tool:retrieveKnowledge），前端历史回放的"执行轨迹"
            //   面板也只靠这一项（参数与结果在审计表里）。
            state.stepTools.put(state.steps,
                    requested.stream().map(AssistantMessage.ToolCall::name).toList());

            // ---------- 先发 ToolCall 事件（用户要立刻看到"正在查…"）----------
            //   ★ 顺序很重要：执行工具可能很慢，先发"开始"才有反馈；等执行完再一起发，前端在
            //     调用与结果之间没有任何可显示的东西。
            Flux<PipelineEvent> callEvents = Flux.fromIterable(requested)
                    .map(call -> (PipelineEvent) new PipelineEvent.ModelOutput.ToolCall(
                            call.id(), call.name(), call.arguments()));

            // ---------- 同步执行工具（框架的阻塞 API）----------
            //   ★ 这一步【必须阻塞】：框架的 executeToolCalls 是同步方法，而且"先发 ToolCall、
            //     再执行、再发 ToolResult"这个顺序需要它。工具内部若要发事件（如检索发出
            //     Retrieved），走 ToolContext 里的 ToolEventSink —— 它也是同步的。
            List<PipelineEvent> toolPhaseEvents = new ArrayList<>();
            Prompt prompt = new Prompt(state.messages, optionsFor(user, toolPhaseEvents::add));
            // ★ 工具段计时。粒度只能到"这一批" —— 框架的 executeToolCalls 是一次整体调用，
            //   没有单工具钩子（要单工具耗时要装饰 ToolCallback）。
            long toolStartNanos = System.nanoTime();
            ToolExecutionResult execution =
                    toolCallingManager.executeToolCalls(prompt, response);
            state.stats.noteToolPhase(System.nanoTime() - toolStartNanos, requested.size());

            state.messages.clear();
            state.messages.addAll(execution.conversationHistory());

            // ---------- 再发"工具执行期间产生的"事件 + ToolResult ----------
            List<PipelineEvent.ModelOutput.ToolResult> results = toolResultEvents(execution, requested);
            toolPhaseEvents.addAll(results);

            // ---------- ⑧ 写审计 ----------
            //   ★ 与记忆写回一样【不在关键路径上】：实现内部吞掉异常并记 ERROR ——
            //     为"审计写不进去"丢掉已经产生的模型回复是双重损失。
            //   ★ 带上 turn_id 与 user_id —— 审计的核心是"谁在这一轮做了什么"。缺了这两列，本表只能
            //     叫"操作日志"（答不出主体），而表只会变长，越晚补越亏（历史行补不回来）。
            recordAudit(conversationId, state.stats.turnId(), user.userId(),
                    state.stepIndexBase + state.steps, requested, results);

            log.debug("[{}] 第 {} 步：执行了 {} 个工具，历史消息数 {} → {}",
                    LOG_TAG, state.steps, requested.size(),
                    prompt.getInstructions().size(), state.messages.size());

            // ---------- 递归走下一步 ----------
            //   ★ 用 Flux.defer + concatWith 保持整条链响应式；不要 block()（那会在 SSE 线程上阻塞）。
            return callEvents.concatWith(Flux.fromIterable(toolPhaseEvents))
                    .concatWith(Flux.defer(() -> loop(state, conversationId, user, scope)));
        });
    }

    /**
     * 把本步的每一次工具调用写进审计表。
     *
     * <p><b>为什么按"调用"逐条写</b>：一轮可能有 N 次调用，每条都是一次独立的事实（调了什么、拿到
     * 什么）。合成一条会逼着查询方去解析那个合并结构 —— 正是本项目反对的"结构化数据变文本、
     * 再解析回来"。
     *
     * <p><b>参数与结果按下标配对</b>：框架保证 {@code ToolResponse} 与 {@code ToolCall} 同序。
     * 若某次调用没有对应结果（理论上不该发生），{@code result} 为 null 而<b>记录照写</b> ——
     * 审计要如实反映"这次调用发生了"，而不是因为拿不到结果就整条不记。
     */
    private void recordAudit(String conversationId,
                             String turnId,
                             String userId,
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
            // ★ 与回合账同一个 turn_id → 可以按轮对账
            //   （SELECT turn.tool_calls 与 COUNT(*) 必须相等；不等说明审计写入失败过）
            auditStore.record(new ToolInvocationStore.ToolInvocation(
                    conversationId, turnId, userId, stepIndex, call.name(), call.arguments(), result));
        }
    }

    /**
     * 把工具返回的文本转成 {@link PipelineEvent.ModelOutput.ToolResult} 事件。
     *
     * <p>结果在 {@code conversationHistory} 的<b>最后一条</b> {@code ToolResponseMessage} 里
     * （框架的约定：assistant(工具请求) 之后紧跟 tool(结果)）。
     *
     * <p>★ 与请求<b>按下标</b>配对：框架保证响应与请求<b>同序</b>。若框架将来不保证顺序，配对的 id
     * 会错 —— 但正文不受影响（刻意不让它成为关键路径）。
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
     * 克隆"模型配置好的 options"，按需挂上工具、调用方身份与事件出口。
     *
     * <p>构造器已保证 {@code chatModel.getOptions()} 是 {@code DeepSeekChatOptions}（不是就在启动期
     * 失败），所以这里的转换是安全的。
     *
     * <p>★ <b>工具始终挂上</b>：曾试过"汇总步不挂工具，逼它只能合并已有材料" —— 结果模型仍然想检索，
     * 而<b>没有工具可调时它把工具调用模板（{@code <||DSML||>}）当作正文吐了出来</b>，用户看到一屏
     * 协议文本。结论：<b>"不给能力"不会让模型放弃这个意图，只会让它用错误的格式表达意图。</b>
     *
     * @param sink 工具执行期间发事件的通道；没有工具要发事件时传 null 也可以
     *             （工具侧取不到出口是正常状态，不告警 —— 见 {@code KnowledgeRetrievalTool}）
     */
    private ToolCallingChatOptions optionsFor(UserContext user, ToolEventSink sink) {
        var builder = DeepSeekChatOptions.class.cast(baseOptions)
                .mutate()
                .toolCallbacks(toolCallbacks)
                .toolContext(ToolContextSupport.USER, user);
        if (sink != null) {
            builder = builder.toolContext(ToolContextSupport.EVENT_SINK, sink);
        }
        return builder.build();
    }

    /** 收尾：写回记忆 + 产出 {@link PipelineEvent.ModelOutput.Final}。 */
    private PipelineEvent.ModelOutput.Final finish(LoopState state,
                                                   String conversationId,
                                                   String finishReason) {
        writeBack(conversationId, state);
        return buildFinal(state.text, state.reasoning, finishReason, state.stats);
    }

    /**
     * 把整个回合的思考按"步"切成段，再拼成<b>落库形态</b>（带 {@code @@step:} 标记）。
     *
     * <p>切片数据全部来自 {@link LoopState}：{@code reasoningOffsets} 给出每步的起点，
     * {@code stepTools} 给出每步<b>之后</b>调了哪些工具。本方法<b>只做组装</b>，格式与容错都在
     * {@link ReasoningTrace} 里（那里也负责反向解析）。
     *
     * <p><b>★ 工具名挂在"下一步的标记"上，而不是自己那一步</b>：工具是在第 N 步<b>结束之后</b>才被
     * 执行的（模型先请求、框架再执行），所以它属于第 N 步与第 N+1 步之间的那个接缝。反过来（把工具
     * 挂在自己那一步）会让<b>第 1 步的工具名彻底丢失</b> —— 因为第 1 步按约定不写标记。
     *
     * <p>⚠️ 已知边角：若第 N 步请求了工具、执行完正好撞上 {@code max-steps} 上限，第 N+1 步不会发生，
     * 于是那一批工具名没有标记可挂。这是<b>降级路径</b>（模型大概率在绕圈），工具本身仍记在审计表里。
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
     * <p>有思考 → {@link PipelineEvent.ModelOutput.Reasoning}；有正文 →
     * {@link PipelineEvent.ModelOutput.Token}；两者都没有（首片、收尾片）→ <b>不发事件</b>。
     * 实测两段<b>完全互斥</b>（思考段 text 恒为 null，回答段 reasoning 恒为 null），所以同一片不会同时
     * 产出两种事件。
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
                                                       TurnStats stats) {
        String text = textAcc.toString();
        String reasoning = reasoningAcc.toString();
        // ★ 这是【整轮累加】的用量，不再是"最后一片"的量（实测反例：一轮 workflow 调 7 次模型，
        //   旧实现只记了最后一次的 3419 token）。
        Usage usage = stats.totalUsage();
        // ★ 把"停止原因 + 回答"交给账本 —— 判定 status 要用（maxSteps 算成功、空回答算失败）
        stats.noteFinal(finishReason, text);

        log.info("[{}] ← 响应 | 耗时={}ms | 回答 {} 字 | 思考 {} 字 | finishReason={} | 模型 {} 次 | usage={}",
                LOG_TAG, stats.elapsedMs(), text.length(), reasoning.length(),
                finishReason, stats.modelCalls(), usage);
        if (reasoning.isEmpty()) {
            // ★ 取不到思考时明确告警，而不是静默为空：reasoningContent 是 DeepSeek 专属字段，
            //   换掉模型后这里会一直是空的，而"思考窗口一直没内容"若不报出来会被当成前端 bug 排查很久。
            log.warn("[{}] 本次【未取到思考内容】—— 若预期有思考，请确认模型是否仍为 DeepSeek"
                    + "（reasoningContent 是该提供方的独有字段，取不到时无法降级）", LOG_TAG);
        }
        return new PipelineEvent.ModelOutput.Final(text, reasoning, finishReason, usage);
    }

    /**
     * 写回「本次问 + 模型最终答（含思考）」。
     *
     * <p><b>只写这两条，不写工具活动</b>：循环过程中长出的 {@code assistant(toolCalls)} 与
     * {@code tool(结果)} 消息<b>不进会话记忆</b> —— 记录层是<b>用户可见的对话记录</b>（混进工具消息会
     * 让用户看到模型的"手机屏幕"），上下文层重放执行态既费 token 又可能在下一轮造成困惑。工具活动
     * 另有归宿：审计表 {@code chat_tool_invocation}。
     *
     * <h3>★ 思考也写进来，但走 properties 而不是 reasoningContent</h3>
     *
     * <p>思考挂在 {@link AssistantMessage} 的 <b>properties</b>（键
     * {@link MessageCodec#REASONING_PROPERTY}）上，<b>不是</b>
     * {@code DeepSeekAssistantMessage.reasoningContent}：{@code DeepSeekChatModel.createRequest} 会把
     * {@code reasoningContent} <b>重新填进发出去的请求</b>，而 {@code reasoning_content} 是<b>输出
     * 专用字段</b>，回传轻则白烧 token、重则 HTTP 400。
     *
     * <p>⇒ 用 properties 存：前端能显示，<b>模型永远看不到自己的历史思考</b>。这是刻意的（想让它记住
     * 结论，那是长期记忆的事）；有人若把它"简化"成 reasoningContent，症状会是"下一轮对话莫名失败"。
     *
     * <p><b>用户消息从哪取</b>：从<b>拼装前的原始 prompt</b>（{@code LoopState.userMessage}），而不是
     * 循环中的消息列表 —— 后者已混进 tool 消息，"靠位置找最后一条 user"会随循环增长而变脆。
     * 写回失败<b>不影响本次响应</b>，本方法无法也不应感知成败（见类注释的诚实声明）。
     */
    private void writeBack(String conversationId, LoopState state) {
        writeBack(conversationId, state.userMessage, state.text.toString(), markedReasoning(state),
                state.reasoningOffsets.size(), state.stats.turnId());
    }

    /**
     * 写回的<b>唯一实现</b>（chat 与工作流共用这一条）。
     *
     * <p>为什么参数是"文本 + 用户消息"而不是继续收 {@link LoopState}：工作流没有"一个累积整轮回答的
     * LoopState"（它每步一个），另写一份就会长出两条写回路径 —— 将来改落库格式必然只改一处。
     *
     * @param reasoningTrace 分步思考标记（{@code @@step:N tool:...}），可为 null
     * @param stepCount      日志用：chat 里是模型调用步数，工作流里是计划步数
     * @param turnId         本轮的账的标识。★ 挂在 assistant 消息的 properties 上 →
     *                       落进 {@code chat_message.turn_id} → 历史回放据此把那行统计找回来
     *                       （否则刷新页面就消失）
     */
    private void writeBack(String conversationId, Message userMessage, String replyText,
                           String reasoningTrace, int stepCount, String turnId) {
        List<Message> turn = new ArrayList<>(2);
        if (userMessage != null) {
            turn.add(userMessage);
        }
        if (replyText != null && !replyText.isBlank()) {
            // ★ 两个键都走 properties：它们都是"前端要用、模型不该看到"的元数据。
            //   尤其 turnId 绝不能进 prompt —— 模型不需要知道自己的账单编号。
            Map<String, Object> properties = new LinkedHashMap<>(2);
            if (reasoningTrace != null) {
                properties.put(MessageCodec.REASONING_PROPERTY, reasoningTrace);
            }
            if (turnId != null && !turnId.isBlank()) {
                properties.put(MessageCodec.TURN_ID_PROPERTY, turnId);
            }
            turn.add(properties.isEmpty()
                    ? new AssistantMessage(replyText)
                    : AssistantMessage.builder().content(replyText).properties(properties).build());
        }
        if (turn.isEmpty()) {
            log.debug("[{}] 本次没有可写回的内容，跳过", LOG_TAG);
            return;
        }
        log.debug("[{}] 写回记忆：会话={} 消息 {} 条，思考 {} 字（分 {} 步），轮次={}",
                LOG_TAG, conversationId, turn.size(),
                reasoningTrace == null ? 0 : reasoningTrace.length(),
                stepCount, turnId);
        memory.append(conversationId, turn);
    }

    // ---- 取值辅助 —— 供应商耦合集中在这两个方法里 ----

    /** 取文本；每片可能没有 result（例如只有 metadata 的收尾片）。 */
    private static String textOf(ChatResponse response) {
        return outputOf(response).map(AssistantMessage::getText).orElse(null);
    }

    /**
     * 取思考内容 —— ★ <b>只能靠 {@code instanceof}</b>。
     *
     * <p>实测：{@code reasoningContent} 是 {@link DeepSeekAssistantMessage} 的独有字段，Spring AI
     * 2.0.1 <b>没有</b>为它提供任何通用 metadata key。所以本方法是全项目<b>唯一</b>的 DeepSeek
     * 耦合点，刻意集中在此：换模型时这里编译失败或恒返回 null，比散落在多处要好定位得多。
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

    // ---- 工作流编排：先拆解 → 逐步执行 → 失败重试 → 重规划一次 ----

    /**
     * 工作流：<b>先拆解，再逐步执行</b>。
     *
     * <p>规划提示词在 {@code classpath:prompts/workflow-plan-prompt.md}，由
     * {@link com.hagent.prompt.WorkflowPlanPrompt} 读取。★ <b>它和 {@link WorkflowPlanParser} 是
     * 一对契约</b>：提示词承诺的 JSON 形状解析器必须能读；解析器拒绝的（步数超限、编号不连续），
     * 提示词里也要提前约束。
     *
     * <h2>★ 为什么整段跑在 boundedElastic 上，且是递归 Flux</h2>
     *
     * <p>规划是一次<b>同步</b>的模型调用，每一步又是"一次阻塞工具循环"，放在事件循环上会直接抛
     * {@code block() ... not supported in thread reactor-http-nio-*}。所以规划用
     * {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} 包一层，外层用
     * {@code Mono + Flux.concat + Flux.defer} 递归 —— 与 chat 的 {@code concatWith(Flux.defer(...))}
     * <b>同形</b>，取消与背压由 Reactor 白拿（命令式外壳要手写 {@code sink.isCancelled()}，失败也
     * 无法走 onError 通道）。
     *
     * <h2>★ 降级：规划失败 <b>不</b>让请求失败</h2>
     *
     * <p>模型写不出合法 JSON 是可预期的失败（不像配置写错），而"用户拿不到任何回答"是更坏的结果。
     * 所以退回普通循环 —— 但<b>必须发 {@link PipelineEvent.PlanSkipped}</b>，否则用户会以为工作流跑过了。
     */
    private Flux<PipelineEvent> runWorkflow(LoopState seed,
                                            List<Message> history,
                                            String conversationId,
                                            UserContext user) {
        // ★ 构建标记：日志里必须能一眼看出"这次是哪套代码在跑"。真实事故：一次测试的日志里同时出现
        //   新旧两种行为（2 步/有 usage vs 6 步/usage=null），排查时无法判断应用是否重启过。
        log.info("[{}] ★ 工作流外壳=reactive-unified / 规划提示词=P1（含最少步数与汇总约束）", LOG_TAG);
        return Mono.fromCallable(() -> draftPlan(seed, history, null, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(drafted -> {
                    if (drafted.plan() == null) {
                        log.warn("[{}] ★ 规划失败，退回普通循环（回答照常，但不保证按计划执行）：{}",
                                LOG_TAG, drafted.error());
                        return Flux.concat(
                                Flux.just(new PipelineEvent.PlanSkipped(drafted.error())),
                                loop(seed, conversationId, user, LoopScope.CHAT));
                    }
                    log.info("[{}] 工作流计划：{} 步（{}）",
                            LOG_TAG, drafted.plan().size(), drafted.plan().goal());
                    // ★ 计划步数进账（chat 没有这个概念 → 落库为 NULL）
                    seed.stats.notePlanSteps(drafted.plan().size());
                    WorkflowRun run = new WorkflowRun(drafted.plan(), seed);
                    return Flux.concat(
                            Flux.just(new PipelineEvent.PlanCreated(drafted.plan())),
                            nextStep(run, seed, history, conversationId, user));
                });
    }

    /**
     * 下一步 —— 与 chat 的 {@link #loop} <b>同形</b>：{@code Flux.defer} 里判断"还能不能继续"，
     * 能就 {@code concatWith(Flux.defer(下一步))}，不能就产出收尾事件。
     *
     * <p>★ 命令式外壳（{@code while} + {@code blockLast()}）会让"取消"必须手写检查，而递归的 Flux
     * 链天然继承取消与背压。
     */
    private Flux<PipelineEvent> nextStep(WorkflowRun run, LoopState seed, List<Message> history,
                                         String conversationId, UserContext user) {
        return Flux.defer(() -> {
            if (!run.hasNext()) {
                return Flux.just(finishWorkflow(run, seed, conversationId, null));
            }
            PlanStep step = run.current();
            LoopState stepState = newStepState(run, step, seed, history);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepStarted(step.index())),
                    loop(stepState, conversationId, user, LoopScope.STEP),
                    Flux.defer(() -> afterStep(run, step, stepState, seed, history,
                            conversationId, user)));
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
                                          String conversationId, UserContext user) {
        run.used += stepState.steps;
        String text = stripToolMarkup(stepState.text.toString()).trim();
        if (!text.isEmpty()) {
            run.recordSuccess(step, text, stepState);
            log.info("[{}] 第 {} 步完成：{} 字（累计模型调用 {} 次）",
                    LOG_TAG, step.index(), text.length(), run.used);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepFinished(step.index(), true, text)),
                    nextStep(run, seed, history, conversationId, user));
        }
        String error = "这一步没有产出任何内容（模型未给出该步的结果）";
        String failure = "第 " + step.index() + " 步（" + step.goal() + "）失败：" + error;
        if (run.attempts++ == 0) {
            log.warn("[{}] 第 {} 步失败，同步重试一次：{}", LOG_TAG, step.index(), error);
            LoopState retryState = newStepState(run, step, seed, history);
            return Flux.concat(
                    Flux.just(new PipelineEvent.StepFinished(step.index(), false, error)),
                    Flux.just(new PipelineEvent.StepStarted(step.index())),
                    loop(retryState, conversationId, user, LoopScope.STEP),
                    Flux.defer(() -> afterStep(run, step, retryState, seed, history,
                            conversationId, user)));
        }
        if (run.replanned) {
            return Flux.just(finishWorkflow(run, seed, conversationId, failure));
        }
        run.replanned = true;
        log.warn("[{}] 同步重试后仍失败，重规划一次：{}", LOG_TAG, failure);
        return Flux.concat(
                Flux.just(new PipelineEvent.StepFinished(step.index(), false, error)),
                Mono.fromCallable(() -> draftPlan(seed, history, run.plan.goal(), failure))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(drafted -> {
                            if (drafted.plan() == null) {
                                return Flux.just(finishWorkflow(run, seed, conversationId,
                                        failure + "；重规划失败：" + drafted.error()));
                            }
                            run.restart(drafted.plan());
                            return Flux.concat(
                                    Flux.just(new PipelineEvent.PlanCreated(drafted.plan())),
                                    nextStep(run, seed, history, conversationId, user));
                        }));
    }

    /**
     * 这一步发给模型的消息：system + <b>最近 2 轮历史</b> + 本步指令。
     *
     * <h2>★ 为什么是"最近 2 轮"，而不是全量历史</h2>
     *
     * <p>一次工作流里，模型会看到 <b>1 次规划提示词 + 每一步至少 1 次</b>提示词（每步都是一次独立的
     * 工具循环，工具返回后还要再问一次）。既然它<b>能看到多次</b>，就没必要每次都塞全量历史 ——
     * 8 步 × 50k token 会让成本线性膨胀。最近 2 轮足以支撑"刚才那个呢"这类指代；更早的信息走
     * "已完成摘要"。
     *
     * <p>反之，如果将来改成"一次提示词跑完全部步骤"，那就必须带全量已裁剪历史 —— 那种情况下模型
     * 只有一次机会看到它。
     */
    private LoopState newStepState(WorkflowRun run, PlanStep step, LoopState seed, List<Message> history) {
        int remaining = Math.max(1, seed.maxSteps - run.used);
        List<Message> messages = new ArrayList<>(2 + history.size());
        messages.add(new SystemMessage(systemMessageOf(seed)));
        messages.addAll(recentTurns(history));
        messages.add(new UserMessage(stepInstruction(run, step, seed)));
        return new LoopState(messages, null, remaining, run.used, seed.stats);
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
            sb.append("已完成部分的完整结论（直接用于合并，不要重新去查）：\n");
            for (CompletedStep done : run.completed) {
                sb.append("第 ").append(done.index()).append(" 步（").append(done.goal())
                        .append("）：\n").append(cap(done.summary(), 1200)).append('\n');
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
                                                           String conversationId,
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
            // ★ 成功时最终回答 = 【最后一步（汇总步）的产出】，而不是把各步小作文拼起来
            //   （实测拼接版 428+360+351 ≈ 1374 字，用户拿到的是"三小节小作文"而不是"一份说明"）。
            //   各步过程仍然实时流给了前端，不会丢。
            text = run.completed.get(run.completed.size() - 1).summary().trim();
        }
        if (text.isEmpty()) {
            text = "（工作流没有产出任何内容）";
        }
        writeBack(conversationId, seed.userMessage, text, markedReasoning(run.aggregate),
                run.completed.size(), seed.stats.turnId());
        // ★ 放弃要记进账 —— 用户拿到的是一份"未完成说明"，这一轮不能算成功
        //   （否则成功率会把"没做完"统计成"做好了"）。
        if (failure != null) {
            seed.stats.noteFailure(failure);
        }
        return buildFinal(new StringBuilder(text), run.aggregate.reasoning,
                failure == null ? "stop" : "stepFailed", seed.stats);
    }

    /**
     * 规划调用（<b>同步</b>，由调用方保证在 boundedElastic 上；<b>不带工具</b>）。
     *
     * <p>★ 带<b>全量已裁剪历史</b>：规划只发生一次或两次（重规划），让它看到上下文才可能拆对；每一步
     * 则只带最近 2 轮（见 {@link #newStepState}）。
     *
     * <p>★ 刻意<b>不带工具</b>：规划是"想清楚要做什么"，不该在执行前就调工具。于是直接
     * {@code new Prompt(messages)}，用配置里那份 options，规划阶段没有任何副作用。
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
        seed.stats.noteModelCallStart();
        ChatResponse response = chatModel.call(new Prompt(messages));
        // ★ 规划也是一次真实的模型调用（计费 + 耗时），必须进账 —— 不记它会让一次 3 步工作流的
        //   model_calls 少 1（规划 + 6 而不是 7）。
        seed.stats.noteModelUsage(usageOf(response));
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

    private static String cap(String text, int limit) {
        String value = text == null ? "" : text.trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    /**
     * 剥掉模型误吐进正文的<b>工具调用模板</b>（DeepSeek 的 {@code <||DSML||>...}）。
     *
     * <p><b>为什么需要这道防御</b>：实测汇总步被"不挂工具"之后，模型仍想检索，于是把工具调用模板
     * 当正文输出，用户屏幕上出现一屏 {@code <||DSML|| invoke name="retrieveKnowledge">}。协议文本
     * 到达用户是<b>不可接受的缺陷</b>，所以这里截断并 WARN。
     *
     * <p>为什么是"截断到底"而不是"过滤掉那几行"：模板之后的内容是这个非法意图的续写，留着只会得到
     * 半截错乱文本。宁可只保留模板之前的有效部分。
     */
    private String stripToolMarkup(String text) {
        int marker = text.indexOf("DSML");
        if (marker < 0) {
            return text;
        }
        int from = Math.max(text.lastIndexOf('<', marker), text.lastIndexOf('＜', marker));
        log.warn("[{}] ★ 模型把工具调用模板当成正文输出了，已截断（{} 字 → {} 字）。"
                        + "【排查方向】① 该步是否被阻止调工具却又确实需要；② 工具 schema 是否让模型误用",
                LOG_TAG, text.length(), Math.max(from, 0));
        return from <= 0 ? "" : text.substring(0, from);
    }

    /**
     * 一次工作流运行的可变状态：计划进度 + 思考聚合。
     *
     * <p>★ 思考聚合进一个 {@link LoopState} 而不是自己拼一份格式：落库的 {@code @@step:N tool:...}
     * 标记逻辑在 {@link #markedReasoning} 里，复用它、并把"步号"设成<b>计划步号</b>，两种模式的历史
     * 回放格式就完全一致 —— 否则会长出第二套标记格式。
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

        private WorkflowRun(WorkflowPlan plan, LoopState seed) {
            this.plan = plan;
            // ★ 聚合器与各步共享同一个账本（seed.stats）—— 否则每一步都从 0 开始计数
            this.aggregate = new LoopState(new ArrayList<>(), seed.userMessage,
                    seed.maxSteps, 0, seed.stats);
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

    // ---- 预算：模式 → 预算（唯一出处） ----

    /**
     * 模式 → 预算。
     *
     * <p><b>★ 穷尽 {@code switch} 且刻意不写 {@code default}</b>：写了 {@code default} 就等于对编译器
     * 说"任何我没列出的模式都走这里"，于是新增枚举常量时<b>编译器不再检查</b> —— 那正是这道保证失效
     * 的方式。现在新增 {@link MemoryMode} 会<b>编译失败</b>，而不是运行期在某处 NPE。
     *
     * <p><b>★ 数值来自各自那两项配置，不存在"共用一个大上限"</b>：那是"用数量级掩盖歧义"
     * （"反正取最大的不会丢"）的坑。
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
     * 模式 → 步数上限。
     *
     * <p><b>★ 为什么是两档而不是一个全局值</b>：chat 的"合理步数"与 workflow 不是一个量级 —— 前者是
     * "查两次资料 + 追问 + 汇总"，后者每一步的工具返回都可能是整篇文档。用一个值必然二选一地错：
     * 取 10 会砍掉工作流的长任务，取 20 会让普通问答多绕 10 步的账单。
     *
     * <p>与 {@link #budgetOf} 一样穷尽 switch、不写 {@code default}；但两者是<b>两条不同的轴</b>：
     * 这里管"循环跑几轮"，那里管"历史能有多长"。不要因为签名相似就合并 —— {@code MemoryTrimmer}
     * 只消费后者，合并会让它带一个永不读的字段。
     */
    private int maxStepsOf(MemoryMode mode) {
        return switch (mode) {
            case CHAT -> agentLoopProperties.getChatMaxSteps();
            case WORKFLOW -> agentLoopProperties.getWorkflowMaxSteps();
        };
    }

    // ---- 日志 ----

    /**
     * 记"请求进来时的样子"。
     *
     * <p>此处记的是<b>注入前</b>的 prompt（只有本次用户消息）—— 它与 {@code PromptBuilder} 记的
     * "模型真正收到的 prompt"是<b>两条不同的日志</b>：这里用来确认"入口是干净的"，那里确认"注入生效了"。
     */
    private void logRequest(String conversationId, MemoryMode mode, UserContext user, Prompt original) {
        log.info("[{}] → 请求 | 会话={} 模式={} 身份={}({}) | 消息数={} | 内容={}",
                LOG_TAG, conversationId, mode,
                user.userId(), user.role(),
                original.getInstructions().size(),
                original.getContents());
    }
}
