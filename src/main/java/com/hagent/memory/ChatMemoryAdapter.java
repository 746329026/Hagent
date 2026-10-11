package com.hagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口适配器 —— 把本项目的 {@link ConversationMemory} 适配成框架的 {@link ChatMemory}。
 *
 * <h2>为什么需要适配层</h2>
 *
 * <pre>
 *   框架 ChatMemory              : add / get / clear                （无预算概念、无模式概念）
 *   本项目 ConversationMemory    : append / load(budget) / clear / delete
 * </pre>
 *
 * <p>框架接口的 {@code get(conversationId)} <b>既没有预算参数、也没有模式参数</b>
 * （已用 {@code javap} 核对 2.0.1 的全部方法）。若把预算藏在实现内部，则：
 * <ul>
 *   <li>"这次带了多少历史"在<b>调用点不可见</b> → 成本不可控；</li>
 *   <li>会话模式（5000）与工作流模式（50000）的差异<b>无法在调用时表达</b>。</li>
 * </ul>
 * 所以本项目<b>以自己的接口承载设计</b>，适配器只负责两个方向：
 * <ol>
 *   <li><b>向内</b>：把框架的调用翻译成本项目的调用；</li>
 *   <li><b>向外</b>：把"模式"这个框架没有的概念，显式地接进来。</li>
 * </ol>
 *
 * <h2>★ 模式是怎么处理的（一次设计修正的记录）</h2>
 *
 * <p><b>修正前的写法</b>：适配器只持有一个预算，取的是
 * {@code workflowMaxTokens}，注释写着"用工作流预算作为适配器层的上限"。
 * 那是<b>用数量级掩盖歧义</b> —— "反正它是最大的，读不丢"。
 * 问题在于：<b>它没有回答"这次调用属于哪种模式"</b>，
 * 只是让这个问题的后果看起来不明显。
 *
 * <p><b>修正后的设计</b>：适配器持有<b>完整的模式→预算表</b>，
 * 并由调用方<b>显式告知模式</b>：
 * <pre>
 *   loadFor(conversationId, MemoryMode.WORKFLOW)  -> 读 workflow-max-* 两项配置
 *   loadFor(conversationId, MemoryMode.CONVERSATION) -> 读 conversation-max-* 两项配置
 * </pre>
 * 没有任何"猜"、没有任何"取最大的那个"，<b>读的是哪个配置项完全由模式决定</b>。
 *
 * <h2>★ 一个必须说清的遗留：框架路径的模式从哪来</h2>
 *
 * <p>框架的 {@code ChatMemory.get(String)} <b>没有地方传模式</b>。
 * 当框架自带组件（如将来的框架记忆切面）走这条路径时，
 * 模式只能靠<b>本适配器记住</b>：在 {@link #add} / {@link #remember} 时记下
 * "这个会话是哪种模式"，{@link #get} 时查出来。
 *
 * <p>这是一张 {@code 会话ID -> 模式} 的旁路表，属于"框架接口表达力不足"被迫留下的状态。
 * 因此：
 * <ul>
 *   <li>它是 {@link ConcurrentHashMap}（并发调用安全）；</li>
 *   <li><b>查不到时不猜</b>，落到 {@link MemoryMode#DEFAULT} 并<b>记 WARN</b> ——
 *       因为"猜模式"正是本次修正要消灭的东西；</li>
 *   <li>本项目自己的主链路<b>不走这张表</b>：
 *       {@code MemoryReadAdvisor} 用 {@link MemoryBudgets} 取到预算后<b>直接调</b>
 *       {@link ConversationMemory#load}，路径清晰且无状态。</li>
 * </ul>
 *
 * <h2>★★ 它现在"没有调用方"，但绝不能删 —— 它是框架边界的【闸门 + 占位】</h2>
 *
 * <p>本类当前不在主链路上、也没有任何外部调用方。这种"零调用方的 Bean"
 * 很容易被当成"备而不用的预留"顺手删掉 —— <b>本项目的文档一度就是这么写的
 * （"仅作为 Bean 预留"），并因此导致一次错误的删除建议。</b>
 * 不能删的原因是一条实测出来的框架事实：
 *
 * <pre>
 *   javap -v org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration
 *     @AutoConfiguration
 *     @ConditionalOnClass({ChatMemory.class, ChatMemoryRepository.class})
 *     class ChatMemoryAutoConfiguration {
 *         &#64;Bean &#64;ConditionalOnMissingBean          ← ★
 *         ChatMemoryRepository chatMemoryRepository() { return new InMemoryChatMemoryRepository(); }
 *         &#64;Bean &#64;ConditionalOnMissingBean          ← ★
 *         ChatMemory chatMemory(ChatMemoryRepository repo) {
 *             return MessageWindowChatMemory.builder().chatMemoryRepository(repo).build();
 *         }
 *     }
 * </pre>
 *
 * <p>两个 {@code @ConditionalOnMissingBean} 的含义是：
 * <b>我们的 Bean 在，框架的就不建；我们的 Bean 一删，框架立刻装一个自己的。</b>
 * 而框架那个是：
 *
 * <table border="1">
 *   <caption>删掉本类之后，容器里会剩下什么</caption>
 *   <tr><th>维度</th><th>框架默认的 ChatMemory</th></tr>
 *   <tr><td>存储</td><td>{@code InMemoryChatMemoryRepository} —— <b>堆内存，重启即丢</b></td></tr>
 *   <tr><td>预算</td><td><b>没有任何 token 预算</b>（只有 {@code MessageWindow} 的条数窗口）</td></tr>
 *   <tr><td>模式</td><td><b>没有模式概念</b></td></tr>
 * </table>
 *
 * <p>★ 这刚好命中 {@link MemoryProperties#afterPropertiesSet()} 自己警告过的失败模式：
 * <b>"你以为在用 MySQL 存，实际历史在堆里、重启就丢，且不报错"。</b>
 * 所以本类的作用有两半，缺一不可：
 * <ol>
 *   <li><b>闸门</b>：把预算绑在框架边界上。框架的 {@code ChatMemory.get(String)}
 *       <b>没有预算参数</b> —— 不在这里绑一个已配置的预算，
 *       接框架组件时就会变成<b>无界读取历史</b>，成本失控且没有任何报错；</li>
 *   <li><b>占位</b>：只要它在容器里，框架那个"无预算 + 纯内存"的实现就进不来。</li>
 * </ol>
 *
 * <p><b>可迁移的判据</b>：判断一个零调用方的类能不能删，不能只看"谁调它"，
 * 还要看<b>"没有它会发生什么"</b> —— 有些零调用方的 Bean 是在<b>占位</b>，
 * 挡住的是框架的默认实现。
 */
@Slf4j
public class ChatMemoryAdapter implements ChatMemory {

    private final ConversationMemory delegate;

    /**
     * 模式 → 预算。★ <b>与 {@code MemoryReadAdvisor} 共用同一份</b>
     * （{@link MemoryBudgets}），来自 {@code hagent.memory.chat.*} 配置。
     *
     * <p>早先这里与切面各抄了一份 {@code Map.of(...)}，靠手写列举保证"每个模式都有预算"
     * —— <b>那没有任何机制支撑</b>。现在 {@link MemoryBudgets} 用穷尽 switch
     * 遍历 {@code MemoryMode.values()} 建表：新增模式时<b>编译期</b>就会失败，
     * 而不是在 {@code forMode()} 上抛 NPE。
     */
    private final MemoryBudgets budgets;

    /**
     * 旁路表：会话 ID → 模式。仅用于补足框架接口"无法传模式"的缺口。
     * 见类注释里的"遗留"一节。
     */
    private final Map<String, MemoryMode> modes = new ConcurrentHashMap<>();

    public ChatMemoryAdapter(ConversationMemory delegate, MemoryProperties properties) {
        this.delegate = delegate;
        // ★ 每个模式各自绑定它在配置文件里的那两项 —— 不存在"共用一个大上限"
        this.budgets = MemoryBudgets.from(properties);
        log.info("[记忆] 适配器已就绪（框架边界的预算闸门）：delegate={} 模式预算={}",
                delegate.getClass().getSimpleName(), budgets.byMode());
    }

    // ========================================================================
    // 本项目内部使用的 API —— 模式【显式传入】，无歧义
    // ========================================================================

    /**
     * 按指定模式加载历史。
     *
     * <p><b>这是框架组件应该用的方法</b>（主链路不走这里，见类注释）：模式由调用方给出，
     * 适配器据此读取对应的两项配置，不做任何猜测。
     *
     * @param conversationId 会话标识
     * @param mode           记忆模式（决定用哪一组预算配置）
     */
    public List<Message> loadFor(String conversationId, MemoryMode mode) {
        return delegate.load(conversationId, budgets.forMode(mode));
    }

    /**
     * 按指定模式追加消息，并<b>记下该会话的模式</b>，供框架路径的 {@link #get} 使用。
     *
     * <p>为什么要"写上时记住"：框架接口读的时候没地方传模式，
     * 只能在写的时候把模式存下来。这是一条<b>被动</b>的补偿措施。
     */
    public void appendFor(String conversationId, List<Message> messages, MemoryMode mode) {
        modes.put(conversationId, mode);
        delegate.append(conversationId, messages);
    }

    /**
     * 只登记模式，不追加消息。
     *
     * <p>用途：某些路径只需要"让适配器知道这个会话的模式"（例如框架组件先读后写，
     * 或我们的切面已经通过 {@link ConversationMemory} 直接写过了），
     * 此时不必也不能重复写一遍消息。
     *
     * <p>★ 这也让"模式映射"这件事<b>可以被单独测试</b>，
     * 不必为了测它去构造一次完整的模型调用。
     */
    public void remember(String conversationId, MemoryMode mode) {
        modes.put(conversationId, mode);
    }

    /** 会话被删除时，清理旁路表，避免它无限增长。 */
    public void forget(String conversationId) {
        modes.remove(conversationId);
    }

    // ========================================================================
    // 框架 ChatMemory 接口 —— 模式只能靠查旁路表（接口限制，非设计选择）
    // ========================================================================

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 框架调用没有模式信息：按默认模式记下（不猜"是工作流"，因为猜大的会更费 token）
        modes.putIfAbsent(conversationId, MemoryMode.DEFAULT);
        delegate.append(conversationId, messages);
    }

    /**
     * 框架接口的读取。
     *
     * <p>模式从旁路表取；<b>取不到时明确回退并告警</b>，而不是静默用某个上限 ——
     * "静默用错预算"正是本项目全篇在防的一类问题。
     */
    @Override
    public List<Message> get(String conversationId) {
        MemoryMode mode = modes.get(conversationId);
        if (mode == null) {
            mode = MemoryMode.DEFAULT;
            log.warn("[记忆] 适配器未登记会话 {} 的模式，已回退为 {}（预算 {} token）。"
                            + "若该会话属于工作流，请先调用 remember/appendFor 登记模式，"
                            + "否则读过来的历史会偏少。",
                    conversationId, mode, budgets.forMode(mode).maxTokens());
        }
        return loadFor(conversationId, mode);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }
}
