package com.hagent.memory.context;

import com.hagent.memory.LayerStatus;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 【上下文层】—— 模型这一次要用的<b>上下文</b>，有损、易失、<b>可从记录层重建</b>。
 *
 * <p>它与记录层为何是两个角色而非"两种存法"，见 {@link com.hagent.memory.record.MessageStore}
 * 的类注释。
 *
 * <h2>它存"原始消息"，不存"裁剪后的结果"</h2>
 *
 * <p>裁剪（按 token 预算）由门面 {@link com.hagent.memory.LayeredConversationMemory} 在<b>取出之后</b>
 * 统一做。若存裁剪结果，缓存键就必须带上预算（预算随模式变化：会话 30 条 / 工作流 200 条），
 * 复杂度陡增；而裁剪本身成本有界。让本层语义保持单纯：<b>它就是"这个会话最近的消息"</b>。
 *
 * <h2>一条必须守住的不变量</h2>
 *
 * <p><b>上下文层永远不会比记录层"新"。</b>写入顺序固定为"先记录层成功、再追加上下文层"，
 * 因此最坏情况是本层<b>少</b>几条，<b>绝不会多</b> —— 不会让模型看到"没发生过"的对话。
 *
 * <h2>命名约定（本层的关键差异）</h2>
 *
 * <p>完整判据见 {@link com.hagent.memory.ConversationMemory} 的「命名约定」。落到本层：
 * {@code get} <b>可以返回 {@code null}</b>（未命中）—— 刻意沿用 Spring
 * {@code org.springframework.cache.Cache} 的惯例（{@code get} 未命中返回 null、{@code evict}
 * 丢弃一个 key），懂那套 API 的人一眼就懂；只有一个 {@code evict}，因为本层<b>不承载会话身份</b>
 * （"清空"与"删除"在这里是同一件事）；多一个 {@code replace}，是重建路径专用 ——
 * 记录层没有对应方法，因为整体替换权威记录 = 数据丢失。
 */
public interface ConversationContextCache {

    /**
     * 读取最近的消息。
     *
     * <p><b>必须区分"未命中"与"命中但是空"</b>：{@code null} 是未命中（键不存在 / 本层不可用），
     * 调用方应回源记录层重建；空列表是命中，且这个会话确实（还）没有消息。
     * 混为一谈会导致"空会话每次都回源"，或更糟的"有历史却当作空"。
     *
     * @param maxMessages 条数上限（只取尾部 N 条 —— 不必吐出比预算用得上的更多）
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
     * <p>重建不用 {@link #append}：重建的语义是"本层现在应当等于记录层说的内容"，
     * 若本层残留旧内容，{@code append} 会把它<b>叠加</b>上去（脏读），替换不会。
     */
    void replace(String conversationId, List<Message> messages);

    /** 丢弃这个会话在本层的一切痕迹（clear / delete 都走它 —— 本层没有独立的会话实体）。 */
    void evict(String conversationId);

    /**
     * 探测本层此刻是否可用 —— 供健康检查如实上报，<b>不做缓存、每次真查</b>。
     *
     * <p><b>绝不抛异常</b>：探测失败本身就是一种结果。未启用本层（{@code none}）时返回
     * {@code reachable=true} + 说明"未启用"—— 那不是故障，是配置选择。
     */
    LayerStatus probe();
}
