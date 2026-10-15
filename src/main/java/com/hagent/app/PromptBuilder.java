package com.hagent.app;

import com.hagent.config.PromptProperties;
import com.hagent.config.TraceProperties;
import com.hagent.retrieval.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词拼装 —— <b>全链路唯一重建消息列表的人</b>，并记录"模型真正收到的 prompt"。
 *
 * <p><b>阶段 8 的来源</b>：本类由 {@code PromptAssemblyAdvisor} 搬来，逻辑等价，
 * 但<b>参数从"切面请求 + context 取值"变成了普通方法参数</b> ——
 * 于是"片段从哪来"由编译期保证，不再需要那道"context 类型不符就告警"的运行期检查。
 *
 * <h2>★ 它解决什么问题（协议第 7 条三问）</h2>
 *
 * <p>到这一步为止，链上已经攒了"要进 prompt 的东西"，但它们<b>都还没进 prompt</b>：
 * <ul>
 *   <li>{@code AgentPipeline} 读出的<b>历史消息</b>；</li>
 *   <li>{@code AgentPipeline} 检索出的<b>片段</b>；</li>
 *   <li><b>回答约束</b>提示词（本类构造时读入）。</li>
 * </ul>
 * 本类把它们与本次用户消息<b>拼成一份</b> prompt。不做它：
 * 前面的产出<b>没有任何消费者</b>，检索与记忆全部白做。
 *
 * <h2>★ "唯一重建者"是硬要求，不是风格</h2>
 *
 * <p>已经用断言证明过：<b>两个组件各自重建消息列表时，先跑的那个注入的内容会被后跑的静默丢弃。</b>
 * 所以本项目的分工是刻意的：
 * <pre>
 *   前面几步：只产"片段"（历史 / 检索结果），一个字节都不动 prompt
 *   本类    ：唯一的"重建者"，把它们拼进去
 * </pre>
 * 这样"谁重建消息列表"从"靠约定不互相干涉"变成<b>结构上当且仅当一处</b>。
 *
 * <h2>★ 拼装结果的形状（顺序不能变）</h2>
 *
 * <pre>
 *   [1] SystemMessage  = 原有 system + 回答约束 + 【知识库片段 …】
 *   [2] 历史消息        按时间正序（来自 AgentPipeline 的记忆读取）
 *   [3] 未知消息        原 prompt 里既不是 system 也不是本次用户消息的那些，原样保留
 *   [4] UserMessage     本次用户提问，永远最后
 * </pre>
 *
 * <p><b>为什么把约束与片段合并进同一条 SystemMessage</b>：
 * 多家模型提供方对"多条 system 消息"的处理不一致（有的合并、有的只认第一条、有的直接报错）。
 * 合成一条就没有这个不确定性。
 *
 * <p><b>为什么片段在 system 而不是 user</b>：它是"背景资料 + 如何使用资料的规则"，
 * 不是用户说的话。放进 user 会让模型以为用户在念一段文档。
 *
 * <p><b>为什么用户消息必须最后</b>：模型据此判断"现在要回答什么"。
 *
 * <h2>★★ 阶段 8 的一处语义修正：合并【全部】system，而不是只取第一条</h2>
 *
 * <p>实测框架的 {@code Prompt.getSystemMessage()} 返回的是<b>第一条</b> system 消息
 * （字节码：遍历到第一个 {@code SystemMessage} 就 {@code areturn}）。
 * 而旧实现只调它一次，于是：
 *
 * <pre>
 *   输入 [system A, system B, user]
 *     旧行为：SystemMessage = A + 约束 + 片段；B 掉进"未知消息"，被原样放到历史之后
 *             ⇒ 后来加的那条 system 被<b>静默挤到 prompt 中段</b>，失去 system 的地位
 *     新行为：SystemMessage = A + B + 约束 + 片段
 * </pre>
 *
 * <p>阶段 9 的工具说明、人格设定都属于"后来要加的 system"，所以这个修正必须在<b>加之前</b>做掉。
 * 多条 system 已经确定是风险（提供方处理不一致），合并正是为了消除它。
 *
 * <h2>★ 为什么 [3] 要保留"未知消息"</h2>
 *
 * <p>这是从旧实现的教训里保留下来的一条：曾经的重建者只挑 system / history / user 三类，
 * 其余<b>全部丢弃</b> —— 当时的注释还写着"顺序不能变"，读起来一切正常，
 * 问题在于<b>它丢的是别人的东西</b>。
 *
 * <p>本类既然已经是唯一重建者，保留"未知消息"是"重建者"应尽的义务，成本只有几行。
 *
 * <h2>★ 日志：这里才是"模型真正收到的 prompt"</h2>
 *
 * <p>{@code AgentPipeline} 在<b>最外层</b>记的是"请求进来时的样子"（≈ 用户原始输入）；
 * 本类在<b>拼装之后</b>记的才是"模型收到的"。两条日志的分工：
 * <pre>
 *   [AgentPipeline]  请求进入       → 用来确认"入口是干净的"
 *   [PromptBuilder]  模型收到的 prompt → 用来确认"注入生效了"
 * </pre>
 */
@Slf4j
public class PromptBuilder {

    private static final String LOG_TAG = "PromptBuilder";

    /**
     * 片段块的起始标记 —— 提示词里的约束 1/3/5 都引用它。
     *
     * <p><b>⚠️ 一处真实的耦合</b>：{@code prompts/rag-answer-prompt.md} 里写的是
     * "优先依据【知识库片段】回答"。改这里的字样必须同步改提示词，
     * 否则约束会指向一个不存在的标记，而<b>不会有任何报错</b> ——
     * 模型只会觉得那句约束莫名其妙。
     */
    private static final String FRAGMENT_HEADER = "【知识库片段】";

    private final TraceProperties traceProperties;
    private final PromptProperties promptProperties;

    /** 回答约束的正文（启动时读入一次）。 */
    private final String constraintText;

    /**
     * @param constraintResource 回答约束资源；由 {@code RagConfig} 保证<b>存在且可读</b>
     *                           （不存在时启动失败，不会走到这里）
     */
    public PromptBuilder(TraceProperties traceProperties,
                         PromptProperties promptProperties,
                         Resource constraintResource) {
        this.traceProperties = traceProperties;
        this.promptProperties = promptProperties;
        this.constraintText = readConstraint(constraintResource);
        log.info("[拼装] 已加载回答约束：{} 字符（includeConstraint={}）",
                constraintText.length(), promptProperties.isIncludeConstraint());
    }

    /**
     * 拼装模型最终收到的 prompt。
     *
     * <p><b>不传 {@code ChatOptions}</b>：{@code new Prompt(messages)} 会让框架在调用模型时
     * <b>自动填入已配置的默认 options</b>（{@code reasoning-effort}、模型名等）。
     * 实测字节码：{@code DeepSeekChatModel.buildRequestPrompt} 里
     * {@code if (prompt.getOptions() == null) → prompt.mutate().chatOptions(this.getOptions())}。
     * <b>若在此显式传 options，反而会覆盖掉配置</b> —— 表现为"配置写了却没生效"。
     *
     * @param original 进入 pipeline 时的原始 prompt（含 system 与本次用户消息）
     * @param history  历史消息，时间正序
     * @param fragments 检索片段；可为空列表
     * @return 拼装后的 prompt
     */
    public Prompt assemble(Prompt original, List<Message> history, List<RetrievedChunk> fragments) {
        List<Message> originalMessages = original.getInstructions();
        List<SystemMessage> systems = collectSystemMessages(originalMessages);
        UserMessage userMessage = original.getUserMessage();
        List<Message> unknown = collectUnknown(originalMessages, systems, userMessage);

        List<Message> safeHistory = history == null ? List.of() : history;
        List<RetrievedChunk> safeFragments = fragments == null ? List.of() : fragments;

        String systemText = buildSystemText(systems, safeFragments);

        List<Message> assembled = new ArrayList<>();
        if (!systemText.isBlank()) {
            assembled.add(new SystemMessage(systemText));
        }
        assembled.addAll(safeHistory);
        assembled.addAll(unknown);
        if (userMessage != null) {
            assembled.add(userMessage);
        }

        logAssembled(assembled, systems.size(), safeHistory, safeFragments);

        // ★ 用构造器显式重建 Prompt，但【不带 options】—— 理由见方法注释。
        return new Prompt(assembled);
    }

    // ========================================================================
    // 收集
    // ========================================================================

    /**
     * 取出<b>全部</b> system 消息（不是只取第一条，见类注释的语义修正一节）。
     *
     * <p>用 {@code instanceof} 判断而与位置无关：system 消息在调用方那里可能出现在任意位置。
     */
    private List<SystemMessage> collectSystemMessages(List<Message> messages) {
        List<SystemMessage> systems = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage system) {
                systems.add(system);
            }
        }
        return systems;
    }

    /**
     * 收集"既不是 system、也不是本次用户消息"的那些消息，原样保留下游。
     *
     * <p>用 {@code ==} 引用比较而不是 {@code equals}：
     * 我们要排除的是"就是这个对象"，而不是"内容相同的另一个对象"。
     * 若用 {@code equals}，一条内容恰好相同的消息会被误删 ——
     * 那正是"静默丢内容"的另一种形态。
     *
     * <p>★ 注意传进来的是<b>全部</b> system 对象（可能是多条）：
     * 逐条按引用排除，才能既合并全部 system、又不把它们重复留在"未知消息"里。
     */
    private List<Message> collectUnknown(List<Message> messages,
                                         List<SystemMessage> systems,
                                         UserMessage user) {
        List<Message> unknown = new ArrayList<>();
        boolean userSkipped = false;
        for (Message message : messages) {
            if (containsReference(systems, message)) {
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

    private boolean containsReference(List<SystemMessage> systems, Message candidate) {
        for (SystemMessage system : systems) {
            if (system == candidate) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // system 正文
    // ========================================================================

    /**
     * 组装 system 正文：原有 system（全部）→ 回答约束 → 检索片段。
     *
     * <p>顺序的理由：先立"角色与规则"，再给"资料"。反过来会让模型在读规则之前
     * 先看到一堆文档，更容易把文档里的语气当成指令。
     */
    private String buildSystemText(List<SystemMessage> systems, List<RetrievedChunk> fragments) {
        StringBuilder sb = new StringBuilder();

        for (SystemMessage system : systems) {
            String text = system.getText();
            if (text != null && !text.isBlank()) {
                appendBlock(sb, text.trim());
            }
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
     * 而提示词约束正是建立在它们之上的（没有元数据时"说明出处"只能靠编造）。
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

    // ========================================================================
    // 日志
    // ========================================================================

    /**
     * 记"模型真正收到的 prompt"。
     *
     * <p>同时打出各部分的条数：排查"注入没生效"时，
     * "历史 0 条 / 片段 0 条"比读一长串文本更快定位。
     *
     * <p>★ 刻意把"没有 system"与"有 system 但内容为空"分开报：
     * 两者都会让模型看不到约束，但原因完全不同（前者是调用方没给，
     * 后者是约束资源为空）—— 合成一条日志就没法区分了。
     */
    private void logAssembled(List<Message> assembled,
                              int systemCount,
                              List<Message> history,
                              List<RetrievedChunk> fragments) {
        if (!traceProperties.isEnabled()) {
            return;
        }
        String contents = assembled.stream()
                .map(Message::getText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        boolean hasSystem = !assembled.isEmpty() && assembled.get(0) instanceof SystemMessage;

        log.info("[{}] ← 模型收到的 prompt | 总条数={} | system={}（合并了 {} 条原有 system）"
                        + " | 历史={} 条 | 片段={} 条 | 内容={}",
                LOG_TAG,
                assembled.size(),
                hasSystem ? "有" : "无",
                systemCount,
                history.size(),
                fragments.size(),
                PromptLogFormat.truncate(contents, traceProperties));
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
     * 每次调用都读盘是纯开销；改提示词需要重启。
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
}
