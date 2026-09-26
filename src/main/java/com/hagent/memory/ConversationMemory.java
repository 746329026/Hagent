package com.hagent.memory;

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
 *       两者的区别是：会话记忆由框架自动存取，长期记忆由模型通过工具读写。</li>
 *   <li><b>不做摘要</b>。摘要是"有损派生数据"，会破坏可审计性；
 *       真要做，也应作为派生数据存在权威存储里（详见设计讨论），而不是在这里。</li>
 * </ul>
 *
 * <p><b>为什么要有这个接口</b>（而不是直接用框架的 {@code ChatMemory}）：
 * 框架的 {@code ChatMemory} 只有 {@code add / get / clear} 三个方法，
 * <b>没有预算概念、没有删除语义</b>。
 * 把预算藏在实现内部会让"这次带多少历史"在调用点不可见，成本也就不可控。
 * 所以这里定义带预算的接口，再由
 * {@link ChatMemoryAdapter} 适配到框架的 {@code ChatMemory} 上。
 *
 * <p><b>实现体</b>（靠配置 {@code hagent.memory.chat.type} 切换）：
 * <ul>
 *   <li>{@code file} —— 文件（零基础设施、人可读）</li>
 *   <li>{@code redis} —— 缓存（带 TTL，承担"活跃会话加速"角色）</li>
 *   <li>{@code mysql} —— 阶段 7 提供（它才是本项目的<b>权威</b>会话存储，Redis 是它的缓存）</li>
 * </ul>
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
