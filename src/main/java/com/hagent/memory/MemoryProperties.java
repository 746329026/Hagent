package com.hagent.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 会话记忆相关配置项，绑定 {@code hagent.memory.chat.*}。
 *
 * <h2>★ 阶段 7：从"一个 type"变成"两个开关"</h2>
 *
 * <p>阶段 5 时这里是单一开关：
 * <pre>
 *   hagent.memory.chat.type: file | redis | mysql     ← 三选一（已废弃）
 * </pre>
 *
 * <p>它背后的假设是"<b>一份数据的三种存法</b>"。阶段 7 纠正了这个定位：
 * <ul>
 *   <li><b>记录层</b>（{@link #recordStore}）—— 用户可见的对话记录：权威、完整、永久；</li>
 *   <li><b>上下文层</b>（{@link #contextCache}）—— 模型这一次要用的上下文：有损、TTL、可重建。</li>
 * </ul>
 *
 * <p>这两层<b>同时存在、各司其职</b>，不是二选一 —— 条件装配天然表达不了那种关系，
 * 所以配置也必须变成两个开关。
 *
 * <p><b>"换实现只改配置"这句话依然成立</b>，只是它的对象从"三个平级实现"变成了"两层各自可换"：
 * <pre>
 *   # 生产组合
 *   hagent.memory.chat.record-store:  mysql
 *   hagent.memory.chat.context-cache: redis
 *   # 零基础设施组合（不起 MySQL / Redis 也能跑）
 *   hagent.memory.chat.record-store:  file
 *   hagent.memory.chat.context-cache: none
 * </pre>
 *
 * <p><b>★ 刻意不留旧 {@code type} 的兼容层</b>：本项目尚未上线、没有外部调用方，
 * 兼容层会成为一个<b>永久的负担</b>（两套语义并存，后来者不知道该看哪个）。
 * 配置写错的表现是<b>启动直接失败</b>，而不是悄悄用了别的实现 —— 见 {@link #afterPropertiesSet()}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.memory.chat")
public class MemoryProperties implements InitializingBean {

    /** 记录层的合法取值。 */
    public static final Set<String> VALID_RECORD_STORES = Set.of("mysql", "file");

    /** 上下文层的合法取值。 */
    public static final Set<String> VALID_CONTEXT_CACHES = Set.of("redis", "none");

    // ==================== 两个角色开关 ====================

    /**
     * 【记录层】实现 —— 对话记录的权威存储。
     *
     * <p>{@code mysql}（默认，生产）：完整落库、可审计、不丢；
     * {@code file}：零基础设施、人可读，作为兜底与"成长记录"。
     */
    private String recordStore = "mysql";

    /**
     * 【上下文层】实现 —— 模型上下文缓存。
     *
     * <p>{@code redis}（默认）：跨实例、带 TTL；
     * {@code none}：不缓存，每次读取回源记录层（功能完全正确，只是少了缓存）。
     *
     * <p>★ 注意 {@code none} 是<b>正常配置</b>，不是故障 ——
     * 健康检查对它报"可用"，避免在正常部署上亮红灯。
     */
    private String contextCache = "redis";

    /**
     * 某一层失败后，静默多少秒不再尝试它（熔断窗口），默认 30 秒。
     *
     * <p><b>为什么需要</b>：Redis / MySQL 挂掉时若不熔断，<b>每个请求都会白等一次连接超时</b>
     * （3s / 5s）—— 一次对话从 1 秒变成 4–8 秒，<b>故障期体验反而比正常时差得多</b>。
     *
     * <p>设为 {@code 0} 表示<b>关闭熔断</b>（永远尝试）—— 便于排查时对照。
     */
    private long circuitBreakSeconds = 30;

    // ==================== 预算参数 ====================

    /**
     * 会话模式的 token 预算（主限制，直接对应成本）。
     *
     * <p><b>口径</b>：只约束<b>历史消息</b>，不含 system 提示词与本次问题。
     */
    private int conversationMaxTokens = 5000;

    /** 会话模式的条数上限（兜底：防大量超短消息累积）。 */
    private int conversationMaxMessages = 30;

    /**
     * 工作流 / Agent 模式的 token 预算。
     *
     * <p>比会话模式大一个数量级，因为：多步循环中每一轮的工具返回结果
     * （如检索到的整篇制度文档）都会成为下一轮的输入，上下文自然更长。
     */
    private int workflowMaxTokens = 50000;

    /** 工作流模式的条数上限。 */
    private int workflowMaxMessages = 200;

    // ==================== 工具循环上限 ====================

    /**
     * 一次对话里<b>最多允许的模型调用步数</b>（阶段 9 新增）。
     *
     * <p><b>为什么必须有这个上限</b>：工具循环的正常出口是"模型不再请求工具"
     * （参考实现与主流 harness 都这么做）。但那是一个<b>由模型控制的</b>出口 ——
     * 模型如果绕圈（反复请求同一个工具、或永远不停），循环就不会自己结束，
     * 而<b>每一次循环都是一次计费的模型调用</b>。
     *
     * <p>所以这是"由我们控制的强制出口"，与下面两条一起构成完整的终止条件：
     * <pre>
     *   ① 模型不再请求工具          ← 正常出口（模型决定）
     *   ② 工具声明 returnDirect      ← 工具决定结束（框架翻译成 finishReason=returnDirect）
     *   ③ 本上限 + 框架的工具调用次数上限  ← 防御出口（我们决定）
     *   ④ 上游异常                   ← 错误出口
     * </pre>
     *
     * <p><b>取值依据</b>：10 步足够覆盖"查两次资料 + 追问一次 + 汇总"这类常见形态；
     * 再多通常意味着模型在绕圈。超限时<b>不抛异常</b>，而是用已累积的内容收尾
     * （见 {@code AgentPipeline}）—— 用户已经等到了一些结果，丢掉它没有好处。
     *
     * <p>★ 与框架的 {@code maxCallsPerTool} / {@code maxTotalToolCalls} 是<b>两层</b>：
     * 框架那层防"同一个工具被调太多次"，本层防"整轮步数太多"。
     */
    private int maxSteps = 10;

    // ==================== 各实现的参数 ====================

    /** 记录层 file 实现的存储目录（record-store=file 时使用）。 */
    private String fileDir = "tmp/chat-memory";

    /**
     * 上下文层 Redis 实现的缓存寿命（秒），context-cache=redis 时使用。
     *
     * <p><b>★ 阶段 7 起这个值的语义变了</b>：不再是"数据寿命"（那会让历史永久残缺），
     * 而是"<b>缓存寿命</b>" —— 过期只是下次读取回源记录层，数据仍在 MySQL 里。
     *
     * <p><b>取值依据</b>：24 小时 —— 办公场景里"当天没聊完、第二天接着聊"很常见，
     * 而过期太长会让无用会话长期占内存。
     */
    private long redisTtlSeconds = 24 * 60 * 60L;

    /** 上下文层 Redis 的 key 前缀，避免与其它应用的 key 冲突。 */
    private String redisKeyPrefix = "hagent:chat:memory:";

    // ==================== 记录层 MySQL ====================

    /**
     * 记录层 MySQL 的表名。
     *
     * <p>它会被拼进 SQL，因此只允许普通标识符（在
     * {@code MysqlConversationRecordStore} 的构造器里校验）。
     */
    private String mysqlTableName = "chat_message";

    /**
     * 会话目录的 MySQL 表名（阶段 10 新增，{@code record-store=mysql} 时使用）。
     *
     * <p>它与 {@link #mysqlTableName} 是<b>两张表</b>：一张存"会话里有什么"（消息），
     * 一张存"有哪些会话"（标题 / 模式 / 时间），见 {@code chat_conversation.sql}。
     *
     * <p>同样只允许普通标识符（在实现类构造器里校验，因为它会被拼进 SQL）。
     */
    private String conversationTableName = "chat_conversation";

    /**
     * 启动自检 —— 把"配置写错"变成<b>明确失败</b>。
     *
     * <p><b>这道校验有两次（这里是第二次）</b>：真正的早期拦截在
     * {@link MemoryConfigValidator}（{@code EnvironmentPostProcessor}，
     * 在任何 Bean 创建之前）。本方法作为第二道防线，防止有人绕过那条路径。
     *
     * <p>为什么必须拦：若放过它，Spring 会因缺少实现而抛出难以理解的
     * "找不到 Bean"，或者更糟 —— 静默用了别的实现，
     * <b>你以为在用 MySQL 存，实际历史在别处（甚至重启就丢），且不报错</b>。
     *
     * <p><b>★ 阶段 8 补注：这条失败模式的守卫换人了</b>。
     * 原先挡在框架 {@code ChatMemory} 前面的，是一个<b>占位 Bean</b> ——
     * {@code ChatMemoryAdapter} 占着那个类型，让 Spring AI 的
     * {@code @ConditionalOnMissingBean} 自动配置退避。阶段 8 删掉那个占位之后，
     * 改由一条<b>启动自检</b>守住：容器里一旦出现框架的 {@code ChatMemory}
     * （{@code InMemoryChatMemoryRepository} + {@code MessageWindowChatMemory} ——
     * 纯堆内存、无 token 预算），{@code StartupProbe} 就<b>直接让启动失败</b>，
     * 而不是等人重启之后才发现历史没了。
     * 也就是说："历史悄悄进堆内存"这条风险由自检而不是占位 Bean 守着。
     */
    @Override
    public void afterPropertiesSet() {
        String record = normalize(recordStore);
        if (!VALID_RECORD_STORES.contains(record)) {
            throw new IllegalStateException(String.format(
                    "hagent.memory.chat.record-store 取值非法：%s。合法取值：%s。"
                            + "【为什么直接失败而不是降级】：降级会让你以为配置生效了，"
                            + "而对话记录其实存在别处，且不会有任何报错。",
                    recordStore, VALID_RECORD_STORES));
        }
        String context = normalize(contextCache);
        if (!VALID_CONTEXT_CACHES.contains(context)) {
            throw new IllegalStateException(String.format(
                    "hagent.memory.chat.context-cache 取值非法：%s。合法取值：%s。",
                    contextCache, VALID_CONTEXT_CACHES));
        }
        if (circuitBreakSeconds < 0) {
            throw new IllegalStateException(
                    "hagent.memory.chat.circuit-break-seconds 不能为负：" + circuitBreakSeconds
                            + "（0 表示关闭熔断）");
        }
        if (maxSteps <= 0) {
            throw new IllegalStateException(
                    "hagent.memory.chat.max-steps 必须为正数：" + maxSteps
                            + "（它是一次对话里模型调用的步数上限，0 或负数会让工具循环无法进行）");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
