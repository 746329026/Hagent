package com.hagent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话记忆 —— 本项目核心抽象之一，也是"换实现只改配置"这句话的落点。
 *
 * <p>管<b>一次会话内</b>的对话历史（"刚才聊了什么"），由管线自动存取（每轮读一次、写一次，
 * 模型不参与决定）。<b>不是长期记忆</b>（跨会话的事实与偏好由模型经工具自主读写），
 * <b>不做摘要</b>（有损派生数据会破坏可审计性）。
 *
 * <p><b>为什么不用框架的 {@code ChatMemory}</b>：它只有 {@code add / get / clear}，
 * <b>没有预算概念、没有删除语义</b>；预算藏进实现会让"这次带多少历史"在调用点不可见、成本不可控。
 * 本接口因此把预算做成参数（{@link #load(String, TokenBudget)}）。
 * 框架实现还有一层风险：纯堆内存、无预算，一旦有人接回那条路径，
 * 历史会悄悄进堆内存，<b>重启即丢且不报错</b>。
 *
 * <h2>它是"门面"，背后固定两层，不是三种可切换的实现</h2>
 *
 * <pre>
 *   ConversationMemory（本接口 —— {@code AgentPipeline} 唯一依赖的记忆抽象）
 *        └── {@link LayeredConversationMemory}（唯一实现：组合两层 + 时序 + 失败分级 + 熔断）
 *                ├── {@link com.hagent.memory.record.MessageStore}
 *                │      【记录层】用户可见的对话记录：权威·完整·永久（mysql | file）
 *                └── {@link com.hagent.memory.context.ConversationContextCache}
 *                       【上下文层】模型这一次要用的上下文：有损·TTL·可重建（redis | none）
 * </pre>
 *
 * <p><b>为什么必须是两层而不是二选一</b>：两者需求正好相反 —— 记录层<b>一轮都不能少</b>
 * （丢了就是数据丢失事故），上下文层<b>可以有损</b>（丢了只是模型少看几轮，且能从记录层重建）。
 * 做成"同一份数据的两种存法"会导出一个<b>命中率≈0</b>的无意义策略：
 * 会话历史是 1 读 : 1 写、且写的正是读的那份，"写库后删缓存"会让每轮读都 miss。
 *
 * <p><b>可替换性</b>：{@code AgentPipeline} 只依赖本接口（读一次、写一次），不碰任何一层实现，
 * 所以换 mysql/file（记录层）或 redis/none（上下文层）只改配置，管线里的调用一行都不用动。
 *
 * <h2>命名约定（三个接口一起看才完整）</h2>
 *
 * <p><b>① 三个读方法的差异是刻意的</b>，判据是「给谁用 + 能不能失败」：
 * <ul>
 *   <li>{@code load}（门面，收 {@link TokenBudget}）：必定非 null、<b>已按预算裁剪</b>，给模型这一次用的那一份；</li>
 *   <li>{@code readLatest}（记录层，收 {@code int}）：非 null、<b>未裁剪</b>，库里最近的 N 条（存储视角）；</li>
 *   <li>{@code get}（上下文层，收 {@code int}）：<b>可为 null</b> —— 未命中是它的正常状态，不是异常。</li>
 * </ul>
 * 落到一条：<b>收 {@code TokenBudget} 的地方会裁剪，收 {@code int} 的地方不会</b>，参数类型本身就是给调用点的提示。
 *
 * <p><b>② 写三层同名 {@code append}</b>：语义相同，但<b>失败强度不同</b> ——
 * 记录层必须成功且整批原子（不能只写"问"不写"答"）；缓存层允许失败（下次可从记录层重建）。
 *
 * <p><b>③ 删要分名</b>，判据是「<b>是否承载会话身份</b>」：记录层承载身份，故有
 * {@code clear}（清空内容、会话还在）与 {@code delete}（整个会话删掉）—— 这两件事对用户语义不同
 * （"换个话题" vs "删掉我的数据"）；缓存层不承载身份，只有一个 {@code evict}（丢 key 就是丢 key）。
 * <b>这个不对称本身就是信息。</b>
 *
 * <p><b>④ {@code replace} 只属于可丢的那一层</b>：重建语义是"本层现在应当等于记录层说的内容"，
 * 用 {@code append} 会把残留旧内容<b>叠加</b>上去。记录层<b>故意没有</b>它 ——
 * 整体替换权威对话记录等于数据丢失，<b>缺席是特性，不是遗漏</b>。
 */
public interface ConversationMemory {

    /**
     * 追加消息。
     *
     * <p>参数是"列表"而不是单条：一次交互天然是"问 + 答"，分开调两次会暴露
     * "只写了问、还没写答"的中间态。
     *
     * @param conversationId 会话标识（服务端生成的 UUID —— 不可预测，防越权遍历）
     * @param messages       本次新增的消息，按时间正序
     */
    void append(String conversationId, List<Message> messages);

    /**
     * 加载要送给模型的历史。
     *
     * <p><b>预算口径</b>：{@code budget} 只约束<b>历史消息</b>，不含 system 提示词与本次用户问题 ——
     * 这样"截断把当前问题丢掉"在结构上就不可能发生。
     *
     * <p><b>截断规则</b>：从<b>最旧的一端</b>丢弃，直到 token 与条数两个上限都满足；
     * 保留部分按时间正序返回（早 → 晚），可直接拼进 prompt。
     * 从最旧端丢是因为最近几轮与当前任务最相关，最早的寒暄与被覆盖的旧结论价值最低。
     *
     * @param conversationId 会话标识
     * @param budget         本次愿意为历史付出的预算
     * @return 按时间正序的历史消息；新会话返回空列表（不返回 null）
     */
    List<Message> load(String conversationId, TokenBudget budget);

    /**
     * 清空会话内容，但会话本身仍然存在（用途：用户点"新开一个话题"，身份不变、历史归零）。
     */
    void clear(String conversationId);

    /**
     * 删除整个会话（用途：合规要求 —— 用户可以要求删除自己的数据）。
     *
     * <p>与 {@link #clear} 的区别是<b>语义</b>：clear 是"内容清空"，delete 是"数据删除"。
     */
    void delete(String conversationId);
}
