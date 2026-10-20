package com.hagent.memory.context;

import com.hagent.memory.LayerStatus;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 【上下文层】—— 模型这一次要用的<b>上下文</b>，有损、易失、<b>可从记录层重建</b>。
 *
 * <p>本接口的完整定位说明（两层为什么不是"两种存法"）见
 * {@link com.hagent.memory.record.MessageStore} 的类注释。
 *
 * <h2>★ 它存"原始消息"，不存"裁剪后的结果"</h2>
 *
 * <p>裁剪（按 token 预算）由门面 {@code LayeredConversationMemory} 在<b>取出之后</b>统一做，
 * 本层只负责"这个会话最近的消息"。理由：
 * <ul>
 *   <li>预算随模式变化（会话 30 条 / 工作流 200 条）—— 若存裁剪结果，
 *       缓存键就必须带上预算，复杂度陡增；</li>
 *   <li>裁剪只对"新端"做 token 计数且有条数封顶，成本有界；</li>
 *   <li>让本层语义保持单纯：<b>它就是"这个会话最近的消息"</b>，与预算无关。</li>
 * </ul>
 *
 * <h2>★ 一条必须守住的不变量</h2>
 *
 * <p><b>上下文层永远不会比记录层"新"。</b>
 * 写入顺序固定为"先记录层成功、再追加上下文层"，因此最坏情况是本层<b>少</b>几条
 * （下一轮模型少看一点），<b>绝不会多</b> —— 不会让模型看到"没发生过"的对话。
 *
 * <h2>★ 命名约定（本层的关键差异）</h2>
 *
 * <p>为什么本层的读叫 {@code get}（而不叫 {@code load} 或 {@code readLatest}）、
 * 为什么只有一个 {@code evict}、为什么多一个 {@code replace}
 * —— 完整判据见 {@link com.hagent.memory.ConversationMemory} 类注释的「命名约定」一节。
 *
 * <p>落到本层的三条：
 * <ul>
 *   <li>{@code get} <b>可以返回 {@code null}</b>（未命中）—— 这是它与另两个读方法最本质的差别。
 *       这里刻意沿用 Spring {@code org.springframework.cache.Cache} 的惯例：
 *       {@code Cache#get} 未命中即返回 {@code null}，{@code Cache#evict} 丢弃一个 key。
 *       懂那套 API 的人一眼就懂语义；</li>
 *   <li>只有一个 {@code evict} —— 本层<b>不承载会话身份</b>（身份在记录层），
 *       所以"清空"与"删除"在这里是同一件事；</li>
 *   <li>多一个 {@code replace} —— 它是<b>重建路径</b>专用的（"本层应当等于记录层说的内容"），
 *       记录层没有对应方法，因为整体替换权威记录 = 数据丢失。</li>
 * </ul>
 */
public interface ConversationContextCache {

    /**
     * 读取最近的消息。
     *
     * <p>★ <b>必须区分"未命中"与"命中但是空"</b>：
     * <ul>
     *   <li>{@code null} —— <b>未命中</b>（键不存在 / 本层不可用）→ 调用方应回源记录层重建；</li>
     *   <li>空列表 —— <b>命中</b>，且这个会话确实（还）没有消息。</li>
     * </ul>
     * 把两者混为一谈会导致"空会话每次都回源"或更糟的"有历史却当作空"。
     *
     * @param maxMessages 条数上限（只取尾部 N 条 —— 本层不需要吐出比预算用得上的更多）
     * @return 时间正序的消息；未命中返回 {@code null}
     */
    List<Message> get(String conversationId, int maxMessages);

    /**
     * 追加消息（写路径用）。
     *
     * <p>实现应当刷新 TTL：只要还在聊，会话就不该过期。
     */
    void append(String conversationId, List<Message> messages);

    /**
     * <b>整体替换</b>为给定内容（重建路径用）。
     *
     * <p>为什么重建不用 {@link #append}：重建的语义是"本层现在应当等于记录层说的内容"。
     * 若本层残留了任何旧内容，{@code append} 会把它<b>叠加</b>上去（脏读），
     * 而替换不会。这个区别让重建路径在语义上自解释。
     */
    void replace(String conversationId, List<Message> messages);

    /** 丢弃这个会话在本层的一切痕迹（clear / delete 都走它 —— 本层没有独立的会话实体）。 */
    void evict(String conversationId);

    /**
     * 探测本层此刻是否可用 —— 供健康检查<b>如实上报</b>，不做缓存、每次真查。
     *
     * <p><b>绝不抛异常</b>：探测失败本身就是一种结果。
     * 未启用本层（{@code none}）时返回 {@code reachable=true} + 说明"未启用" ——
     * 那不是故障，是配置选择。
     */
    LayerStatus probe();
}
