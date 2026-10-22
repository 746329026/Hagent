package com.hagent.memory.record;

import com.hagent.memory.LayerStatus;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 【记录层】—— 用户可见的<b>对话记录</b>，权威、完整、永久。
 *
 * <p>它与上下文层（{@code ConversationContextCache}）不是"同一份数据的两种存法"，而是<b>派生</b>关系：
 * <b>本层</b>面向用户、必须完整、永久、丢了是数据丢失事故、且它就是原数据（无从重建）；
 * <b>上下文层</b>面向模型、可以有损、易失、丢了只是模型少看几轮、且能从本层重建。
 *
 * <p>所以写路径是"<b>两层各自写入</b>"，而不是"写库后删缓存"—— 后者会导出一个<b>命中率≈0</b>
 * 的无意义策略（会话历史是 1 读 : 1 写，且写的正是读的那份）。
 *
 * <h2>本层<b>不做</b>预算裁剪</h2>
 *
 * <p>{@link #readLatest} 只按<b>条数</b>截取"最近的 N 条"。原因有两个：完整性（记录就是记录，
 * 裁剪属于"给模型看的那一份"，在上下文层/门面做）；以及"给用户的历史读 API"要的是完整、可分页的数据。
 *
 * <h2>命名约定（本层的关键差异）</h2>
 *
 * <p>完整判据见 {@link com.hagent.memory.ConversationMemory} 的「命名约定」，落到本层就两条：
 * {@code readLatest} 收的是<b>条数上限</b>而不是 {@code TokenBudget}（本层不裁剪，名字必须与门面的
 * {@code load} 区分）；{@code clear} 与 {@code delete} 两个都要有（本层<b>承载会话身份</b>，
 * "清空内容"与"删除数据"是用户可感知的两种语义）。
 *
 * <p>{@link #readAll} 是"消费者出现才加抽象"的记录：早期刻意没有它（无消费者），
 * 前端历史回放出现后加上，<b>数据模型一行都没改</b>。
 * ⇒ "暂时不做"要说清"什么条件下做"，否则后来者要么重复造、要么永远绕开。
 */
public interface MessageStore {

    /**
     * 追加消息 —— <b>完整落库，不做任何裁剪</b>。
     *
     * <p>实现必须保证：要么整批写入成功，要么一条都不写（不能只落"问"不落"答"）。
     *
     * @param conversationId 会话标识
     * @param messages       本次新增的消息，按时间正序
     */
    void append(String conversationId, List<Message> messages);

    /**
     * 读取<b>最近</b>的 {@code maxMessages} 条，按时间正序返回。
     *
     * <p>用途：上下文层 miss 时<b>重建</b>它。
     *
     * @param maxMessages 条数上限（由调用方按预算给出，不是本层的策略）
     * @return 时间正序的消息；会话不存在时返回空列表（<b>不返回 null</b>）
     */
    List<Message> readLatest(String conversationId, int maxMessages);

    /**
     * 读取该会话的<b>全部</b>消息，按时间正序返回。
     *
     * <p>消费者是"给用户的历史回放"，与 {@link #readLatest} 的分工：
     * {@code readLatest(n)} 给模型（按预算截断），{@code readAll()} 给用户（不截断，它本身就是记录）。
     *
     * @return 时间正序的消息；会话不存在时返回空列表（<b>不返回 null</b>）
     */
    List<Message> readAll(String conversationId);

    /**
     * 清空会话内容，但会话本身仍然存在。
     *
     * <p>注：对本层的多数实现（file / mysql），"会话"不是独立实体，
     * 因此它与 {@link #delete} 的<b>效果相同</b> —— 区别是<b>语义</b>，由调用点保证。
     */
    void clear(String conversationId);

    /**
     * 删除整个会话（用途：合规要求 —— 用户可以要求删除自己的数据）。
     */
    void delete(String conversationId);

    /**
     * 探测本层此刻是否可用 —— 供健康检查如实上报，<b>不做缓存、每次真查</b>。
     *
     * <p><b>绝不抛异常</b>：探测失败本身就是一种结果（{@code reachable=false}）。
     */
    LayerStatus probe();
}
