package com.hagent.memory;

import com.hagent.config.MemoryProperties;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话记忆 —— 本项目的<b>核心抽象之一</b>，也是"换实现只改配置"这句话的落点。
 *
 * <p><b>它管什么</b>：一次会话内的对话历史（"刚才聊了什么"）。
 *
 * <p><b>它不管什么（边界必须清楚）</b>：
 * <ul>
 *   <li><b>不是长期记忆</b>。跨会话沉淀的事实与偏好属于长期记忆（阶段 12，
 *       用 `.md` + 索引，由<b>模型自主</b>决定写什么）。
 *       两者的区别是：会话记忆由对话管线自动存取（每轮的②读 / ⑦写，
 *       <b>模型不参与决定</b>），长期记忆由模型通过工具读写。</li>
 *   <li><b>不做摘要</b>。摘要是"有损派生数据"，会破坏可审计性；
 *       真要做，也应作为派生数据存在权威存储里（详见设计讨论），而不是在这里。</li>
 * </ul>
 *
 * <p><b>为什么要有这个接口</b>（而不是直接用框架的 {@code ChatMemory}）：
 * 框架的 {@code ChatMemory} 只有 {@code add / get / clear} 三个方法，
 * <b>没有预算概念、没有删除语义</b>。
 * 把预算藏在实现内部会让"这次带多少历史"在调用点不可见，成本也就不可控。
 * 本接口因此承载"<b>带预算的读取</b>"这个设计：{@link #load(String, TokenBudget)}
 * 收一个 {@link TokenBudget}，"这次愿意为历史付多少"在调用点就是显式的。
 *
 * <p><b>★ 框架的 {@code ChatMemory} 本项目不再使用</b>（阶段 8 起）：
 * 管线直连 {@code ChatModel}，框架边界的适配路径（{@code ChatMemoryAdapter}）
 * 已随之删除；同时 {@code StartupProbe} 有一条<b>启动自检</b> ——
 * Bean 创建期发现容器里存在框架的 {@code ChatMemory}（纯堆内存、无 token 预算）
 * 就直接失败。理由是那种实现"看起来能用、实际不能用"：
 * 一旦有人接回框架记忆路径，历史会悄悄进堆内存，<b>重启即丢且不报错</b>。
 *
 * <p><b>★ 阶段 7 起：它是"门面"，背后是两层，不是三种可切换的实现</b>
 * （阶段 5 的"一个 type 三选一"已被取代，见 {@link MemoryProperties} 的类注释）：
 * <pre>
 *   ConversationMemory（本接口 —— {@code AgentPipeline} 唯一依赖的记忆抽象，形状不变）
 *        └── {@link LayeredConversationMemory}（唯一实现：组合两层 + 时序 + 失败分级 + 熔断）
 *                ├── {@link com.hagent.memory.record.ConversationRecordStore}
 *                │      【记录层】用户可见的对话记录：权威·完整·永久（mysql | file）
 *                └── {@link com.hagent.memory.context.ConversationContextCache}
 *                       【上下文层】模型这一次要用的上下文：有损·TTL·可重建（redis | none）
 * </pre>
 *
 * <p><b>为什么必须是两层而不是二选一</b>：这两个角色的需求正好相反 ——
 * 记录层<b>一轮都不能少</b>（丢了就是数据丢失事故），上下文层<b>可以有损</b>
 * （丢了只是模型少看几轮，且能从记录层重建）。
 * 把它们做成"同一份数据的两种存法"，会导出一个<b>命中率≈0</b>的无意义策略：
 * 会话历史是 1 读 : 1 写、且写的正是读的那份，"写库后删缓存"会让每轮读都 miss。
 *
 * <p><b>为什么接口形状不变</b>：{@code AgentPipeline} 依赖本接口 ——
 * 读在第②步、写在第⑦步，且除此之外不碰任何一层实现，
 * 因此记忆两层的实现可以自由替换而不影响管线：
 * 换 mysql / file（记录层）或 redis / none（上下文层）只改配置，
 * 管线里的调用一行都不用动。
 *
 * <h2>★ 命名约定（三个接口一起看才完整）</h2>
 *
 * <p>记忆有三个接口：本接口（门面）、
 * {@link com.hagent.memory.record.ConversationRecordStore}（记录层）、
 * {@link com.hagent.memory.context.ConversationContextCache}（上下文层）。
 * 它们的方法名<b>不是同一个模子刻的</b> —— 差别是刻意的，四条判据如下。
 *
 * <p><b>① 读为什么有三个名字</b>：
 *
 * <table border="1">
 *   <caption>三个读方法的契约差异</caption>
 *   <tr><th>方法</th><th>在哪层</th><th>参数</th><th>返回</th><th>语义</th></tr>
 *   <tr><td>{@code load}</td><td>门面</td><td>{@link TokenBudget}</td>
 *       <td>必定非 null，<b>已按预算裁剪</b></td><td>给模型<b>这一次要用的那一份</b></td></tr>
 *   <tr><td>{@code readLatest}</td><td>记录层</td><td>{@code int} 条数上限</td>
 *       <td>非 null，<b>未裁剪</b></td><td>库里最近的 N 条（<b>存储视角</b>）</td></tr>
 *   <tr><td>{@code get}</td><td>上下文层</td><td>{@code int} 条数上限</td>
 *       <td><b>可为 null</b></td><td>缓存里有没有（<b>缓存视角</b>）</td></tr>
 * </table>
 *
 * <p>判据是「<b>给谁用 + 能不能失败</b>」：
 * <b>收 {@code TokenBudget} 的地方会裁剪，收 {@code int} 的地方不会</b> ——
 * 参数类型本身就是给调用点的提示；而<b>只有缓存层能返回 {@code null}</b>，
 * 因为"未命中"是它的正常状态，不是异常。
 *
 * <p><b>② 写为什么三层同名 {@code append}</b>：语义确实是同一个（"把这次新增的消息加进去"），
 * 但<b>失败强度不同</b>且各自写明 ——
 * 记录层必须成功、且整批原子（不能只写"问"不写"答"）；缓存层允许失败（下次可从记录层重建）。
 * <b>名字表达"做什么"，强度由各层注释与门面的失败分级表达。</b>
 *
 * <p><b>③ 删为什么要分两种名</b>：判据是「<b>是否承载会话身份</b>」——
 * 记录层用 {@code clear}（清空内容、会话还在）与 {@code delete}（整个会话删掉），
 * 因为它<b>承载"会话"这个身份</b>，且这两件事对用户是不同的语义（"换个话题" vs "删掉我的数据"）；
 * 缓存层只有一个 {@code evict}，因为它<b>不承载身份</b> —— 丢 key 就是丢 key。
 * ★ <b>这个不对称本身就是信息</b>：它告诉你"这一层不管会话身份"。
 *
 * <p><b>④ {@code replace} 为什么只属于可丢的那一层</b>：
 * 重建路径的语义是"本层现在应当等于记录层说的内容"，用 {@code append} 会把残留旧内容<b>叠加</b>上去；
 * 而记录层<b>故意没有</b> {@code replace} —— 整体替换权威对话记录<b>没有语义</b>（那就是数据丢失）。
 * <b>它的缺席是特性，不是遗漏。</b>
 */
public interface ConversationMemory {

    /**
     * 追加消息。
     *
     * <p><b>为什么参数是"列表"而不是单条</b>：一次交互天然是"问 + 答"两条，
     * 分开调两次会暴露一个中间态（只写了问、还没写答）。
     * 一次性传入两条，语义上更完整。
     *
     * @param conversationId 会话标识（服务端生成的 UUID —— 不可预测，防越权遍历）
     * @param messages       本次新增的消息，按时间正序
     */
    void append(String conversationId, List<Message> messages);

    /**
     * 加载要送给模型的历史。
     *
     * <p><b>预算口径（重要）</b>：{@code budget} 只约束<b>历史消息</b>，
     * <b>不含</b> system 提示词与本次用户问题。
     * 因此本方法<b>不会</b>、也不应该把"当前问题"考虑进来 ——
     * 这样"截断把当前问题丢掉"这种事在结构上就不可能发生。
     *
     * <p><b>截断规则</b>：从<b>最旧的一端</b>开始丢弃，直到满足预算（token 与条数同时满足）。
     * 保留的部分按<b>时间正序</b>返回（早 → 晚），可直接拼进 prompt。
     *
     * <p><b>为什么从最旧的一端丢</b>：对话的"最近几轮"与当前任务最相关；
     * 而最早的寒暄、已被覆盖的旧结论价值最低。
     *
     * @param conversationId 会话标识
     * @param budget         本次愿意为历史付出的预算
     * @return 按时间正序的历史消息；新会话返回空列表（不返回 null）
     */
    List<Message> load(String conversationId, TokenBudget budget);

    /**
     * 清空会话内容，但会话本身仍然存在。
     *
     * <p>用途：用户点"新开一个话题" —— 会话身份不变，历史归零。
     */
    void clear(String conversationId);

    /**
     * 删除整个会话。
     *
     * <p>用途：合规要求 —— 用户可以要求删除自己的数据。
     * 与 {@link #clear} 的区别是<b>语义</b>：clear 是"内容清空"，delete 是"数据删除"。
     */
    void delete(String conversationId);
}
