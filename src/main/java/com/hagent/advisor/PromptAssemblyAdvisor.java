package com.hagent.advisor;

import com.hagent.config.AdvisorProperties;
import com.hagent.config.PromptProperties;
import com.hagent.retrieval.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词拼装切面 —— <b>全链路唯一重建消息列表的人</b>，并记录"模型真正收到的 prompt"。
 *
 * <h2>★ 它解决什么问题</h2>
 *
 * <p>到这一步为止，链上已经攒了三类"要进 prompt 的东西"，但它们<b>都还没进 prompt</b>：
 * <ul>
 *   <li>{@code MemoryReadAdvisor} → 历史消息（在 context 里）；</li>
 *   <li>{@code RagRetrievalAdvisor} → 检索片段（在 context 里）；</li>
 *   <li>回答约束提示词 → 资源文件（本切面启动时读入）。</li>
 * </ul>
 * 本切面把它们与本次用户消息<b>拼成一份</b> prompt。不做它：
 * 前面两个切面的产出<b>没有任何消费者</b>，检索与记忆全部白做。
 *
 * <h2>★ "唯一重建者"是硬要求，不是风格</h2>
 *
 * <p>{@code AdvisorChainMessageSemanticsTest} 实验 2 用一个断言证明了：
 * 两个切面各自重建消息列表时，<b>先跑的那个注入的内容会被后跑的静默丢弃</b>
 * （当时代码 {@code assertThat(texts).doesNotContain(RAG_TEXT)} 就是这条）。
 *
 * <p>所以本项目的分工是刻意的：
 * <pre>
 *   前面几层：只产"片段"，放进 context，一个字节都不动 prompt
 *   本切面  ：唯一的"重建者"，把片段拼进去
 * </pre>
 * 这样"谁重建消息列表"从"靠约定不互相干涉"变成<b>结构上当且仅当一处</b> ——
 * 与 {@code WORKING-AGREEMENT} 第 10 条"靠结构，不靠记得先检查"同源。
 *
 * <h2>★ 拼装结果的形状（顺序不能变）</h2>
 *
 * <pre>
 *   [1] SystemMessage  = 原有 system（若有）+ 回答约束 + 【知识库片段 N／来源：…／相关度：…】
 *   [2] 历史消息        按时间正序（来自 MemoryReadAdvisor）
 *   [3] 未知消息        原 prompt 里既不是 system 也不是本次用户消息的那些，原样保留
 *   [4] UserMessage     本次用户提问，永远最后
 * </pre>
 *
 * <p><b>为什么把约束与片段合并进同一条 SystemMessage</b>：
 * 多家模型提供方对"多条 system 消息"的处理不一致（有的合并、有的只认第一条、
 * 有的直接报错）。合成一条就没有这个不确定性。
 * <b>为什么片段在 system 而不是 user</b>：它是"背景资料 + 如何使用资料的规则"，
 * 不是用户说的话。放进 user 会让模型以为用户在念一段文档。
 * <b>为什么用户消息必须最后</b>：模型据此判断"现在要回答什么"。
 *
 * <h2>★ 为什么 [3] 要保留"未知消息"</h2>
 *
 * <p>这是从 {@code ConversationMemoryAdvisor} 的旧写法里学到的教训。
 * 旧写法只挑 system / history / user 三类，其余<b>全部丢弃</b> ——
 * 当时的注释还写着"顺序不能变"，读起来一切正常，问题在于
 * <b>它丢的是别人的东西</b>。
 *
 * <p>本切面既然已经是唯一重建者，"未知消息"在当下的链上不会出现
 * （框架的切面都在工具循环内侧，晚于本切面）。保留它是为将来留出空间：
 * 若某天有人在链上加了新切面并往 prompt 里放了东西，
 * 本切面<b>不会把它悄悄吃掉</b>，而是原样带下去。
 * 这是"重建者"应尽的义务，成本只有几行。
 *
 * <h2>★ 日志：这里才是"模型真正收到的 prompt"</h2>
 *
 * <p>{@code PromptTraceAdvisor} 在 {@code MIN+100}（最外层），它记到的 prompt 是
 * <b>注入前</b>的。原因是切面链<b>单向转发</b>：外层先把 request 交给内层，
 * 内层改完不会回流（{@code DefaultAroundAdvisorChain.nextCall} 从队列里 pop 出下一个
 * 切面，把它交给同一个 request 的<b>新版本</b>继续往下）。
 *
 * <p>所以想看"模型收到了什么"，必须看本切面打的这条日志 —— 它是最后一个改 prompt 的人。
 * 两条日志的分工：
 * <pre>
 *   [PromptTraceAdvisor]     请求进链时的样子（≈ 用户原始输入）  ← 用来确认"入口是干净的"
 *   [PromptAssemblyAdvisor]  模型真正收到的 prompt               ← 用来确认"注入生效了"
 * </pre>
 */
@Slf4j
public class PromptAssemblyAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "PromptAssemblyAdvisor";

    /**
     * 片段块的起始标记 —— 提示词里的约束 1/3/5 都引用它。
     *
     * <p><b>⚠️ 一处真实的耦合</b>：{@code prompts/rag-answer-prompt.md} 里写的是
     * "优先依据【知识库片段】回答"。改这里的字样必须同步改提示词，
     * 否则约束会指向一个不存在的标记，而<b>不会有任何报错</b> ——
     * 模型只会觉得那句约束莫名其妙。
     */
    private static final String FRAGMENT_HEADER = "【知识库片段】";

    private final AdvisorProperties advisorProperties;
    private final PromptProperties promptProperties;

    /** 回答约束的正文（启动时读入一次）。 */
    private final String constraintText;

    /**
     * @param constraintResource 回答约束资源；由 {@code RagConfig} 保证<b>存在且可读</b>
     *                           （不存在时启动失败，不会走到这里）
     */
    public PromptAssemblyAdvisor(AdvisorProperties advisorProperties,
                                 PromptProperties promptProperties,
                                 Resource constraintResource) {
        this.advisorProperties = advisorProperties;
        this.promptProperties = promptProperties;
        this.constraintText = readConstraint(constraintResource);
        log.info("[拼装] 已加载回答约束：{} 字符（includeConstraint={}）",
                constraintText.length(), promptProperties.isIncludeConstraint());
    }

    // ========================================================================
    // 非流式与流式：行为相同（都是 before 侧）
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(assemble(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(assemble(request));
    }

    // ========================================================================
    // 核心：拼装
    // ========================================================================

    private ChatClientRequest assemble(ChatClientRequest request) {
        Prompt original = request.prompt();

        List<Message> memoryMessages = memoryOf(request);
        List<RetrievedChunk> fragments = fragmentsOf(request);

        SystemMessage originalSystem = original.getSystemMessage();
        UserMessage userMessage = original.getUserMessage();
        List<Message> unknown = collectUnknown(original, originalSystem, userMessage);

        String systemText = buildSystemText(originalSystem, fragments);

        List<Message> assembled = new ArrayList<>();
        if (!systemText.isBlank()) {
            assembled.add(new SystemMessage(systemText));
        }
        assembled.addAll(memoryMessages);
        assembled.addAll(unknown);
        if (userMessage != null) {
            assembled.add(userMessage);
        }

        logAssembled(assembled, memoryMessages.size(), fragments.size(), unknown.size());

        // ★ 用构造器而不是 mutate().messages(...)：
        //   Prompt 的 Builder.messages(...) 是【替换】语义，而它是否带上原有 ChatOptions
        //   属于"依赖 Builder 内部行为"。用构造器显式把 options 传过去，
        //   保证模型名 / reasoning-effort 这些请求级选项不丢
        //   （丢了的表现是"配置写了却没生效"，正是本项目一直在防的静默失效）。
        return request.mutate()
                .prompt(new Prompt(assembled, original.getOptions()))
                .build();
    }

    /**
     * 组装 system 正文：原有 system → 回答约束 → 检索片段。
     *
     * <p>顺序的理由：先立"角色与规则"，再给"资料"。反过来会让模型在读规则之前
     * 先看到一堆文档，更容易把文档里的语气当成指令。
     */
    private String buildSystemText(SystemMessage originalSystem, List<RetrievedChunk> fragments) {
        StringBuilder sb = new StringBuilder();

        if (originalSystem != null && originalSystem.getText() != null) {
            sb.append(originalSystem.getText().trim());
        }
        if (promptProperties.isIncludeConstraint() && !constraintText.isBlank()) {
            appendBlock(sb, constraintText.trim());
        }
        if (!fragments.isEmpty()) {
            appendBlock(sb, renderFragments(fragments));
        }
        return sb.toString();
    }

    /**
     * 把片段渲染成提示词里的文本块。
     *
     * <p>格式（与提示词约束里的措辞一致）：
     * <pre>
     *   【知识库片段 1／来源：员工手册 第二章 假期管理／相关度：高】
     *   员工每年享有五天带薪年假……
     * </pre>
     *
     * <p>三处取值都来自 {@link RetrievedChunk}，且都<b>不允许为空</b>：
     * 来源走 {@code displaySource()}（section 为空时降级为"第 N 块"），
     * 档位走 {@code band().displayName()}。
     * 这样模型手里始终有"这条出自哪里、有多可信"两项信息 ——
     * 而提示词约束 3/5 正是建立在它们之上的（没有元数据时"说明出处"只能靠编造）。
     */
    private String renderFragments(List<RetrievedChunk> fragments) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRAGMENT_HEADER).append('\n');
        for (int i = 0; i < fragments.size(); i++) {
            RetrievedChunk chunk = fragments.get(i);
            sb.append("【知识库片段 ").append(i + 1)
                    .append("／来源：").append(chunk.displaySource())
                    .append("／相关度：").append(chunk.band().displayName())
                    .append("】\n");
            sb.append(chunk.content() == null ? "" : chunk.content().trim()).append('\n');
        }
        return sb.toString().trim();
    }

    private void appendBlock(StringBuilder sb, String block) {
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(block);
    }

    /**
     * 收集"既不是 system、也不是本次用户消息"的那些消息，原样保留下游。
     *
     * <p>用 {@code ==} 引用比较而不是 {@code equals}：
     * 我们要排除的是"就是这个对象"，而不是"内容相同的另一个对象"。
     * 若用 {@code equals}，一条内容恰好相同的消息会被误删 ——
     * 那正是"静默丢内容"的另一种形态。
     */
    private List<Message> collectUnknown(Prompt original, SystemMessage system, UserMessage user) {
        List<Message> unknown = new ArrayList<>();
        boolean systemSkipped = false;
        boolean userSkipped = false;
        for (Message message : original.getInstructions()) {
            if (!systemSkipped && message == system) {
                systemSkipped = true;
                continue;
            }
            if (!userSkipped && message == user) {
                userSkipped = true;
                continue;
            }
            unknown.add(message);
        }
        return unknown;
    }

    // ========================================================================
    // context 取值 —— 类型不符时明确告警，不静默当空
    // ========================================================================

    /**
     * 读记忆历史。
     *
     * <p><b>key 不存在</b> → 返回空列表。<b>这是正常状态</b>：
     * 有些调用路径可能没有挂记忆切面（或记忆为空），此时不该告警。
     *
     * <p><b>key 存在但类型不符</b> → 告警并返回空列表。这是<b>错误状态</b>：
     * 说明有人往这个 key 里放了别的东西，或者 {@code PromptFragmentKeys}
     * 的常量被改过而读写两侧不同步。静默当空会让"历史永远不注入"变成一个谜。
     */
    @SuppressWarnings("unchecked")
    private List<Message> memoryOf(ChatClientRequest request) {
        Object value = request.context().get(PromptFragmentKeys.MEMORY_MESSAGES);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return (List<Message>) list;
        }
        log.warn("[拼装] context 里的 {} 类型不是 List<Message> 而是 {}，已按【无历史】处理",
                PromptFragmentKeys.MEMORY_MESSAGES, value.getClass().getName());
        return List.of();
    }

    /**
     * 读检索片段，并区分<b>三种</b>状态（见 {@link PromptFragmentKeys#RAG_FRAGMENTS}）：
     *
     * <ul>
     *   <li><b>key 不存在</b> → 检索切面没跑（例如 {@code enabled=false}）：
     *       不告警，这是配置使然；</li>
     *   <li><b>key 存在但为空</b> → 检索跑了、没命中：记 <b>WARN</b>。
     *       这是本项目特有的一条重要信号 —— 用户问的东西不在知识库里，
     *       回答质量会因此下降，但除了这里没有别的地方能看出来
     *       （模型仍会给出一个通顺的回答）；</li>
     *   <li><b>key 存在且有内容</b> → 正常，记 DEBUG。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> fragmentsOf(ChatClientRequest request) {
        Object value = request.context().get(PromptFragmentKeys.RAG_FRAGMENTS);
        if (value == null) {
            log.debug("[拼装] context 中没有 {} —— 本次未做知识库检索（可能是配置关闭或该路径未挂链）",
                    PromptFragmentKeys.RAG_FRAGMENTS);
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            log.warn("[拼装] context 里的 {} 类型不是 List<RetrievedChunk> 而是 {}，已按【无片段】处理",
                    PromptFragmentKeys.RAG_FRAGMENTS, value.getClass().getName());
            return List.of();
        }
        List<RetrievedChunk> chunks = (List<RetrievedChunk>) list;
        if (chunks.isEmpty()) {
            log.warn("[拼装] 检索已执行但【一条都没命中】—— 本次回答没有企业资料可依据，"
                    + "模型只能给出通用回答或回答'知识库中未找到相关内容'。"
                    + "【可能原因】① 知识库里确实没有相关内容；② 粗筛阈值过严；"
                    + "③ 权限过滤把候选全部挡掉了（身份可见范围过小）。");
        }
        return chunks;
    }

    // ========================================================================
    // 日志
    // ========================================================================

    /**
     * 记"模型真正收到的 prompt"。
     *
     * <p>同时打出各部分的条数：排查"注入没生效"时，
     * "历史 0 条 / 片段 0 条"比读一长串文本更快定位。
     */
    private void logAssembled(List<Message> assembled, int memoryCount, int fragmentCount, int unknownCount) {
        if (!advisorProperties.isEnabled()) {
            return;
        }
        String contents = assembled.stream()
                .map(Message::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        log.info("[{}] ← 模型收到的 prompt | 消息数={}（system={} 历史={} 未知={} 用户={}）| 片段={} | 内容={}",
                ADVISOR_NAME,
                assembled.size(),
                assembled.isEmpty() ? 0 : 1,
                memoryCount,
                unknownCount,
                assembled.isEmpty() ? 0 : 1,
                fragmentCount,
                PromptLogFormat.truncate(contents, advisorProperties));
    }

    // ========================================================================
    // 资源读取
    // ========================================================================

    /**
     * 启动时把回答约束读进来；读不到就<b>直接失败</b>。
     *
     * <p>为什么不"读不到就用空字符串"：约束里有"知识库中没有相关内容时明确说明，
     * 不要凭常识编造"与"不要自己创造文件名"。缺失时模型会<b>照常给出通顺的回答</b>，
     * 只是偶尔编一个来源 —— 这种表现没人会往"提示词没加载"上想。
     * 所以宁可起不来（决策 8：不静默降级）。
     *
     * <p>也是为什么只在构造器里读一次：约束内容随启动固定，
     * 每次调用都读盘是纯开销；改提示词需要重启，这一点写在
     * {@link PromptProperties#getConstraintResource()} 的注释里。
     */
    private String readConstraint(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "回答约束提示词读取失败：" + resource.getDescription()
                            + "。【怎么修】检查 hagent.rag.prompt.constraint-resource 指向的资源是否存在，"
                            + "以及文件是否为 UTF-8。", e);
        }
    }

    // ========================================================================
    // Advisor 元信息
    // ========================================================================

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    @Override
    public int getOrder() {
        return AdvisorOrders.PROMPT_ASSEMBLY;
    }
}
