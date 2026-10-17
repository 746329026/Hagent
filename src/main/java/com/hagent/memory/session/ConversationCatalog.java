package com.hagent.memory.session;

import com.hagent.memory.MemoryMode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 【会话目录】—— <b>有哪些会话</b>（一会话一行），与记录层的"会话里有什么"互补。
 *
 * <h2>★ 为什么需要它（不是一个可以省掉的簿记）</h2>
 *
 * <p>{@code chat_message} 里其实能推出"有哪些会话"：
 * <pre>
 *   SELECT DISTINCT conversation_id FROM chat_message
 * </pre>
 * 但这句只能拿到<b>一串 UUID</b>，拿不到三样必需的东西：
 *
 * <ol>
 *   <li><b>标题</b>：侧栏要显示"公司年假政策怎么规定"，不是 {@code 3f2a…}。
 *       模型回答完之后标题不能变，用户还能改名 —— 所以它必须被<b>存下来</b>；</li>
 *   <li><b>模式</b>（{@link #Session#mode()}）：决定记忆预算（5000 vs 50000 token，差 10 倍）。
 *       见下面的"为什么模式必须落库"；</li>
 *   <li><b>最近活跃时间</b>：侧栏按它倒序。用消息表的 {@code MAX(created_at)} 也能算，
 *       但那需要按会话 GROUP BY 全表 —— 会话一多就是每次打开页面扫一遍全库。</li>
 * </ol>
 *
 * <h2>★ 为什么 {@code mode} 必须落库（本类最重要的一个理由）</h2>
 *
 * <p>阶段 10 之前，模式由 {@code ChatController} <b>硬编码</b>传 {@code CONVERSATION}。
 * 阶段 10 让客户端在请求里声明模式，于是出现一个新的风险：
 * <pre>
 *   若模式只由【每次请求】现传 → 客户端可以每轮换一次模式
 *   → 同一会话的历史预算在 5000 与 50000 之间来回跳
 *   → 且没有任何记录说明它为什么变（事后查不到，日志里只有"这次用了哪个"）
 * </pre>
 * 把模式写进会话目录之后，"这个会话是什么模式"变成一条<b>事实</b>，
 * 而不是一次请求的参数：<b>首次创建时确定，之后不再改变</b>（{@link #touch} 不覆盖它）。
 *
 * <h2>★ 生命周期：懒创建，不做"先建会话再发消息"</h2>
 *
 * <p>本接口<b>没有</b> {@code create}。会话是在<b>第一句话写回时</b>由
 * {@link #touch} 顺带创建的。理由：
 * <ul>
 *   <li>前端"点新建"其实不需要服务端做任何事 —— 它只要清空输入框；
 *       真发第一句话时再落库，少一次空会话（用户点了新建又没说话）；</li>
 *   <li>创建时天然能拿到第一句用户消息，标题不需要额外一次请求去补。</li>
 * </ul>
 *
 * <h2>★ 实现与记录层共用同一个开关</h2>
 *
 * <p>{@code hagent.memory.chat.record-store=mysql|file} 同时决定
 * {@code ConversationRecordStore} 与 {@code ConversationCatalog} 的实现。
 * 刻意<b>不给它俩各一个开关</b>：消息在 MySQL、目录在文件里的组合没有任何意义，
 * 而两个开关可以配出那种组合，且不会有任何报错（只表现为"侧栏永远是空的"）。
 */
public interface ConversationCatalog {

    /**
     * 标题列宽 —— 与 {@code chat_conversation.sql} 的 {@code VARCHAR(128)} 对齐。
     *
     * <p>两个实现都从这里取：{@link #titleOf} 是<b>唯一</b>的标题生成规则，
     * 放在接口上而不是各实现里，是因为"标题长什么样"属于<b>契约</b>，
     * 不属于某个存储 —— 换个实现不该让标题规则跟着变。
     */
    int TITLE_MAX_LENGTH = 128;

    /**
     * 从第一句用户消息生成标题。
     *
     * <p><b>为什么要截断而不是让数据库报错</b>：超长的第一句消息会让
     * {@code INSERT} 直接失败（严格模式下是 {@code Data too long}），
     * 而那意味着<b>整轮对话的消息也写不进去</b>。为了一个标题丢掉对话记录，是本末倒置。
     *
     * <p>截断到 {@link #TITLE_MAX_LENGTH} 而不是更短：更短的"好看"是前端用 CSS
     * 省略号做的事，不该在存储层丢掉用户内容 —— 侧栏宽度会变，存储丢了就找不回来。
     *
     * <p>★ 按<b>码点</b>而不是 {@code String.length()} 截断：{@code length()} 数的是
     * UTF-16 的 char，一个 emoji 占 2 个。用 {@code substring} 从中间切断代理对，
     * 会得到一个<b>非法的半个字符</b>，插进 utf8mb4 列时报错
     * （而报错的那一刻看起来与"标题"毫无关系）。
     *
     * @return 单行化后截断的标题；{@code seed} 为空/全空白时返回 <b>null</b>
     *         （null 标题由前端显示为"新会话"）
     */
    static String titleOf(String seed) {
        if (seed == null) {
            return null;
        }
        // 换行会把侧栏一行撑成多行，且标题本来就是"一句话摘要"
        String oneLine = seed.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return null;
        }
        if (oneLine.codePointCount(0, oneLine.length()) <= TITLE_MAX_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, TITLE_MAX_LENGTH));
    }

    /**
     * 会话摘要 —— 侧栏一行所需的全部信息。
     *
     * @param conversationId 会话标识（服务端生成的随机 UUID）
     * @param title          标题。<b>可能为 null</b>（理论上不会：懒创建时总会带第一句用户消息；
     *                       留 null 是给"将来先建会话后发消息"的形态留位，前端显示"新会话"）
     * @param mode           模式的<b>线上名称</b>（{@code chat} / {@code workflow}），
     *                       不是枚举名 —— 见 {@link MemoryMode#wireName()}
     * @param createdAt      创建时间
     * @param updatedAt      最近活跃时间（侧栏按它倒序）
     */
    record Session(String conversationId,
                   String title,
                   String mode,
                   Instant createdAt,
                   Instant updatedAt) {
    }

    /**
     * 列出全部会话，按 {@code updatedAt} <b>倒序</b>（最近的在前）。
     *
     * <p><b>为什么有 {@code limit} 而不是返回全部</b>：侧栏一次只显示前 N 个，
     * 把几千个会话全部序列化再传给浏览器，是"没有消费者的数据"的典型。
     * 真正的分页（游标）等有"滚动加载"交互时再加 —— 那时表结构不用改
     * （{@code updated_at} 已经是索引）。
     *
     * @param limit 最多返回几条；{@code <= 0} 返回空列表
     */
    List<Session> list(int limit);

    /**
     * 按 ID 查一个会话。
     *
     * <p>消费者：{@code GET /chat/sessions/{id}/messages} 要据此区分
     * "会话不存在"（404 + {@code SESSION_NOT_FOUND}）与"会话存在但还没有消息"（200 + 空列表）。
     * 这两件事对前端完全不同：前者该跳回首页，后者该显示一个空对话。
     */
    Optional<Session> find(String conversationId);

    /**
     * 记一次活动：<b>不存在则创建，存在则只更新活跃时间</b>。
     *
     * <p>★ 已存在的会话<b>不会被改写标题与模式</b> —— 这是本方法的契约核心：
     * <ul>
     *   <li>标题：用户改过名，下一句对话不能把它覆盖回"第一句话"；</li>
     *   <li>模式：见类注释"为什么模式必须落库"，一旦确定就不再由请求改变。</li>
     * </ul>
     *
     * @param conversationId 会话标识
     * @param mode           创建时使用的模式（已存在时忽略）
     * @param titleSeed      标题种子（通常是第一句用户消息；已存在时忽略）。
     *                       <b>可能为 null</b>：那时标题留空，前端显示"新会话"
     */
    void touch(String conversationId, MemoryMode mode, String titleSeed);

    /**
     * 改名。
     *
     * @return {@code true} 表示改成功；{@code false} 表示<b>会话不存在</b>
     *         （由调用方翻译成 404，本层不抛异常 —— 存储层不该知道 HTTP 语义）
     */
    boolean rename(String conversationId, String title);

    /**
     * 删除会话目录项。
     *
     * <p>★ 它与 {@code ConversationRecordStore#delete} 是<b>两件事</b>，
     * 需要由调用方<b>一起做</b>：删消息不删目录 = 侧栏留下一个打不开的空壳；
     * 删目录不删消息 = 数据还在库里，违反"用户要求删除自己的数据"。
     * 本接口不提供"连带删除"，因为跨两个存储的原子性在这里无法保证
     * （弱化成一个"尽力而为"的复合操作，不如让调用点显式写明它做了哪两步）。
     */
    void delete(String conversationId);
}
