package com.hagent.memory.longterm;

import com.hagent.document.AccessLevel;
import com.hagent.security.UserContext;

import java.util.List;
import java.util.Optional;

/**
 * 【长期记忆】的存 —— 跨会话的事实与偏好，由<b>模型通过工具</b>自主读写。
 *
 * <h2>★ 它与会话记忆的区别（别搞混）</h2>
 *
 * <table border="1">
 *   <caption>两种记忆回答的是两个不同的问题</caption>
 *   <tr><th></th><th>会话记忆（阶段 7）</th><th>长期记忆（本接口）</th></tr>
 *   <tr><td>回答</td><td>"这次聊了什么"</td><td>"这个人是怎样的"</td></tr>
 *   <tr><td>谁写</td><td><b>管线自动</b>（每轮落库问+答）</td><td><b>模型</b>（它判断什么值得记）</td></tr>
 *   <tr><td>生命周期</td><td>跟着一个会话</td><td><b>跨会话、跨天</b></td></tr>
 *   <tr><td>存哪</td><td>MySQL / 文件（结构化）</td><td>人类可读的 {@code .md}</td></tr>
 * </table>
 *
 * <h2>★ 为什么"读"分成两层（索引常驻 + 明细按需）</h2>
 *
 * <p>记忆不读就等于没存。但全量注入会随记忆增长吃光 prompt 预算，纯工具读取则依赖
 * "模型主动想起来问一句"—— 它通常不会。
 *
 * <p>所以：<b>索引</b>（每条一行标题+摘要）每轮注入，让模型<b>知道有哪些记忆</b>；
 * <b>明细</b>由 {@code readMemory} 按需取。索引的长度上限因此是一条硬约束
 * （见 {@link LongTermMemoryProperties#getIndexMaxChars()}）。
 *
 * <h2>★ 密级：复用 {@link AccessLevel}，不新增概念</h2>
 *
 * <p>条目带着写入时的 {@code AccessLevel}；读取时用
 * {@code entry.level().visibleTo(当前用户.clearance())} 过滤。
 * 这条规则与检索<b>完全同源</b>（{@code RetrievalFilterBuilder} 用的是同一个枚举的
 * {@code visibleLevels}）—— 于是"记忆"永远不会比"检索"更宽松。
 *
 * <p>为什么需要它：模型可能把检索到的 {@code CONFIDENTIAL} 内容写进记忆。
 * 若不做这层过滤，同一个 userId 换成低权限角色后，记忆会<b>绕过检索的权限控制</b>。
 */
public interface LongTermMemoryStore {

    /**
     * 该用户<b>可见</b>的全部记忆，按 {@code updatedAt} 倒序。
     *
     * @return 可见条目；一条都没有时返回空列表（<b>不返回 null</b>）
     */
    List<MemoryEntry> list(UserContext user);

    /**
     * 按标题查一条记忆。
     *
     * <p>★ <b>不可见与不存在返回同一个结果</b>（{@code Optional.empty()}）：
     * 若"存在但你看不到"能被探测出来，攻击者就能靠它枚举出别人的记忆标题。
     * 真实原因只写日志。
     */
    Optional<MemoryEntry> find(String title, UserContext user);

    /**
     * 渲染<b>给模型看的索引块</b>（含使用规则），已按密级过滤。
     *
     * @return 索引块；该用户没有任何可见记忆时返回<b>空字符串</b>
     *         （调用方据此决定"不注入这一段"）
     */
    String renderIndexFor(UserContext user);

    /**
     * 写入一条记忆 —— <b>同名即覆盖（upsert）</b>。
     *
     * <p>upsert 而不是"先删再写"：两步操作中间失败会<b>丢掉旧值</b>，
     * 而覆盖是单步、幂等、不存在失败窗口。所以本接口<b>刻意没有</b> {@code update}。
     *
     * <p>密级取 {@code user.clearance()}（写入者当时能看到的最高密级），
     * 覆盖时<b>沿用原条目的密级</b>（不让一次低权限的覆盖把别人的条目降级）。
     *
     * @return 结果；<b>不抛业务异常</b> —— 调用方（工具）要把它翻译成给模型看的话
     */
    SaveResult save(String title, String content, UserContext user);

    /**
     * 删除一条记忆。
     *
     * @return {@code true} = 确实删了一条；{@code false} = 不存在<b>或当前身份不可见</b>
     */
    boolean forget(String title, UserContext user);

    /** 写入结果 —— 工具据此回一句模型能看懂的话。 */
    enum SaveResult {
        /** 新建了一条。 */
        CREATED,
        /** 覆盖了同名条目。 */
        UPDATED,
        /** 索引已达上限，<b>没有写入</b>（要求模型先整理）。 */
        INDEX_FULL,
        /** 同名条目存在，但当前身份的密级看不到它 —— 拒绝覆盖（否则等于悄悄降级别人的记忆）。 */
        FORBIDDEN
    }
}
