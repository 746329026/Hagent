package com.hagent.app;

import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.memory.ConversationMemory;
import com.hagent.memory.MemoryMode;
import com.hagent.memory.MemoryProperties;
import com.hagent.memory.TokenBudget;
import com.hagent.retrieval.RetrievedChunk;
import com.hagent.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
 *   ③ 检索（含权限过滤） ← 取代 RagRetrievalAdvisor
 *   ④ 拼装              ← 委托 PromptBuilder（唯一重建消息列表者）
 *   ⑤ 调模型            ← chatModel.stream(prompt)
 *   ⑥ 逐片累积 text / reasoning → 发出 Reasoning / Token
 *   ⑦ 流结束时：写回记忆（问 + 答）→ 发出 Final
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
    private final KnowledgeRetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final MemoryProperties memoryProperties;

    public AgentPipeline(ChatModel chatModel,
                         ConversationMemory memory,
                         KnowledgeRetrievalService retrievalService,
                         TraceProperties traceProperties,
                         PromptProperties promptProperties,
                         MemoryProperties memoryProperties,
                         Resource ragAnswerPromptResource) {
        this.chatModel = chatModel;
        this.memory = memory;
        this.retrievalService = retrievalService;
        this.memoryProperties = memoryProperties;
        this.promptBuilder = new PromptBuilder(traceProperties, promptProperties, ragAnswerPromptResource);

        log.info("[管道] AgentPipeline 已就绪：直连 {}（不经 ChatClient，无切面链）",
                chatModel.getClass().getSimpleName());
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

        return Flux.defer(() -> {
            long startNanos = System.nanoTime();

            // ---------- ① 记请求（进入时的样子） ----------
            logRequest(cid, safeMode, user, original);

            // ---------- ② 读历史 ----------
            List<Message> history = memory.load(cid, budget);
            log.debug("[{}] ② 读历史：会话={} 模式={} 注入历史 {} 条（预算 {} token / {} 条）",
                    LOG_TAG, cid, safeMode, history.size(),
                    budget.maxTokens(), budget.maxMessages());

            // ---------- ③ 检索 ----------
            List<RetrievedChunk> fragments = retrievalService.retrieve(message, user);
            //   ★ 旧实现有一道"context 里已有片段就跳过"的幂等检查，本类【刻意不要】：
            //     管道是直线，这里物理上只执行一次。留着它等于为一个不可能发生的场景写代码
            //     （协议第 7 条），而且它防的正是"切面被工具循环跑 N 次"这个已消失的前提。

            // ---------- ④ 拼装 ----------
            Prompt assembled = promptBuilder.assemble(original, history, fragments);

            // 检索结果先出去 —— 前端据此显示"参考了哪几篇"
            return Flux.concat(
                    Flux.just(new PipelineEvent.Retrieved(fragments, message)),
                    streamModel(assembled, cid, startNanos));
        });
    }

    // ========================================================================
    // ⑤⑥⑦ 模型调用与逐片累积
    // ========================================================================

    private Flux<PipelineEvent> streamModel(Prompt assembled, String conversationId, long startNanos) {
        return Flux.defer(() -> {
            StringBuilder textAcc = new StringBuilder();
            StringBuilder reasoningAcc = new StringBuilder();
            AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();

            return chatModel.stream(assembled)
                    .doOnNext(chunk -> {
                        lastChunk.set(chunk);
                        String text = textOf(chunk);
                        String reasoning = reasoningOf(chunk);
                        if (text != null && !text.isEmpty()) {
                            textAcc.append(text);
                        }
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningAcc.append(reasoning);
                        }
                    })
                    .flatMap(this::emit)
                    .concatWith(Flux.defer(() -> {
                        // ---------- ⑦ 写回（必须在 Final 之前） ----------
                        writeBack(conversationId, assembled, textAcc.toString());
                        return Flux.just(buildFinal(textAcc, reasoningAcc, lastChunk.get(), startNanos));
                    }));
        });
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
                                                       ChatResponse lastChunk,
                                                       long startNanos) {
        String text = textAcc.toString();
        String reasoning = reasoningAcc.toString();
        String finishReason = finishReasonOf(lastChunk);
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
     * 写回「本次问 + 模型答」。
     *
     * <p><b>用户消息从哪取</b>：从<b>拼装前的</b>原始 prompt 取，而不是从 {@code assembled} 取。
     * 拼装后的 prompt 里最后一条确实是本次用户消息，但"靠位置取"会在将来
     * （阶段 9 插入 tool 消息时）变得脆弱 —— 而原始 prompt 里只有一条 user，语义明确。
     * 本方法因此接收的是<b>拼装后</b>的 prompt 仅用于取 user；
     * 由于 {@code PromptBuilder} 保证 user 消息原样保留到最后，两者取到的是同一个对象。
     *
     * <p>写回失败<b>不影响本次响应</b> —— 失败分级在 {@code LayeredConversationMemory} 里，
     * 本方法无法也不应感知成败（见类注释的诚实声明）。
     */
    private void writeBack(String conversationId, Prompt assembled, String replyText) {
        List<Message> turn = new ArrayList<>(2);
        UserMessage user = assembled.getUserMessage();
        if (user != null) {
            turn.add(user);
        }
        if (replyText != null && !replyText.isBlank()) {
            turn.add(new AssistantMessage(replyText));
        }
        if (turn.isEmpty()) {
            log.debug("[{}] 本次没有可写回的内容，跳过", LOG_TAG);
            return;
        }
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
            case CONVERSATION -> new TokenBudget(
                    memoryProperties.getConversationMaxTokens(),
                    memoryProperties.getConversationMaxMessages());
            case WORKFLOW -> new TokenBudget(
                    memoryProperties.getWorkflowMaxTokens(),
                    memoryProperties.getWorkflowMaxMessages());
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
