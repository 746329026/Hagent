package com.hagent.memory.record;

import com.hagent.memory.LayerStatus;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 【记录层】—— 用户可见的<b>对话记录</b>，权威、完整、永久。
 *
 * <h2>★ 它与上下文层（{@code ConversationContextCache}）不是"两种存法"</h2>
 *
 * <p>本项目阶段 7 纠正过一次定位错误，结论写在这里（别再走回去）：
 *
 * <table border="1">
 *   <caption>两个角色的需求正好相反</caption>
 *   <tr><th></th><th>记录层（本接口）</th><th>上下文层</th></tr>
 *   <tr><td>面向谁</td><td><b>用户</b>（刷新页面要看）</td><td><b>模型</b>（这次要发什么）</td></tr>
 *   <tr><td>完整性</td><td><b>必须完整</b>，一轮都不能少</td><td><b>可以有损</b>（预算裁剪）</td></tr>
 *   <tr><td>生命周期</td><td><b>永久</b></td><td>易失（TTL / 可淘汰）</td></tr>
 *   <tr><td>丢失后果</td><td><b>数据丢失事故</b></td><td>模型少看几轮，质量降级</td></tr>
 *   <tr><td>能否重建</td><td>❌ 它本身就是原数据</td><td>✅ 可从本层重建</td></tr>
 * </table>
 *
 * <p>所以两层的关系是「<b>派生</b>」，不是「缓存同一份数据」——
 * 这决定了写路径是"<b>两层各自写入</b>"，而不是"写库后删缓存"。
 * （原设计若被读成后者，会导出一个<b>命中率≈0</b>的无意义策略：
 *   会话历史是 1 读 : 1 写、且写的正是读的那份，删缓存会让每轮读都 miss。）
 *
 * <h2>★ 本层<b>不做</b>预算裁剪</h2>
 *
 * <p>{@link #readLatest} 只按<b>条数</b>截取"最近的 N 条"，<b>不按 token 预算裁剪</b>。
 * 两个原因：
 * <ol>
 *   <li>完整性：记录就是记录，裁剪属于"给模型看的那一份"（在上下文层/门面做）；</li>
 *   <li>将来"给用户的历史读 API"要的是完整、可分页的数据 —— 本层必须能提供。</li>
 * </ol>
 *
 * <h2>★ 阶段 10 新增了 {@link #readAll}（一次"消费者出现才加抽象"的记录）</h2>
 *
 * <p>本接口的早期版本<b>刻意没有</b> {@code readAll}，理由写的是：
 * "<b>它目前没有消费者</b>（前端在后续阶段），按项目约定不做没有消费者的抽象"。
 *
 * <p>阶段 10 的前端就是那个消费者（侧栏点开一个会话要看到<b>全部</b>历史）。
 * 于是加上了它，而<b>数据模型一行都没改</b> —— 这正是当时那段话的后半句：
 * "表结构已经能承载它，届时只需在本接口上加一个方法，不必改数据模型"。
 *
 * <p>⇒ 可迁移的判据：<b>"暂时不做"要说清"什么条件下做"</b>，
 * 否则后来者要么以为忘了做而重复造一个，要么以为永远不该做而绕开它。
 *
 * <h2>★ 命名约定（本层的关键差异）</h2>
 *
 * <p>为什么本层的读叫 {@code readLatest}（而不叫 {@code load} 或 {@code get}）、
 * 为什么删要有 {@code clear} 与 {@code delete} 两个、为什么<b>没有</b> {@code replace}
 * —— 完整判据见 {@link com.hagent.memory.ConversationMemory} 类注释的「命名约定」一节。
 *
 * <p>落到本层的两条：
 * <ul>
 *   <li>{@code readLatest} 收的是<b>条数上限</b>（不是 {@code TokenBudget}）—— 本层<b>不做预算裁剪</b>，
 *       所以名字必须与门面的 {@code load} 区分开；</li>
 *   <li>{@code clear} / {@code delete} 两个都要有 —— 本层<b>承载会话身份</b>，
 *       而"清空内容"与"删除数据"是用户可感知的两种语义。</li>
 * </ul>
 */
public interface ConversationRecordStore {

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
     * 读取该会话的<b>全部</b>消息，按时间正序返回（阶段 10 新增）。
     *
     * <p><b>消费者是"给用户的历史回放"</b>（{@code GET /chat/sessions/{id}/messages}），
     * 与 {@link #readLatest} 的分工见类注释的「两个角色」表：
     * <pre>
     *   readLatest(n)  → 给【模型】的上下文：按预算截断
     *   readAll()      → 给【用户】的完整记录：不截断（它本身就是"记录"）
     * </pre>
     *
     * <p>★ 为什么这次有了 {@code readAll}：本接口的早期版本<b>刻意没有</b>它，
     * 理由是"没有消费者"（前端在后续阶段）。阶段 10 的前端就是那个消费者 ——
     * 抽象不是提前设计出来的，是消费者出现时才加的。
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
     * 删除整个会话。
     *
     * <p>用途：合规要求 —— 用户可以要求删除自己的数据。
     */
    void delete(String conversationId);

    /**
     * 探测本层此刻是否可用 —— 供健康检查<b>如实上报</b>，不做缓存、每次真查。
     *
     * <p><b>绝不抛异常</b>：探测失败本身就是一种结果（{@code reachable=false}）。
     */
    LayerStatus probe();
}
