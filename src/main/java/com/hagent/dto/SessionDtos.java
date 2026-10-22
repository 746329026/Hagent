package com.hagent.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 会话列表 / 历史 / 改名 的传输对象。
 *
 * <p><b>为什么集中在一个文件里、用嵌套 record</b>：它们是<b>同一个契约的四个面</b>
 * （侧栏一行、一条消息、一个会话的详情、一次改名请求），字段互相引用，
 * 拆成四个文件会让"它们是一套"在目录里看不出来。
 *
 * <p>★ 这些 record 的<b>字段名就是前端契约</b>：改名不会报错，只会让前端拿到 {@code undefined}。
 * 所以刻意不直接复用存储层的 {@code SessionStore.Session} / {@code ReasoningTrace.Section} ——
 * 那两个是<b>存储形态</b>，允许随实现演化。
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * 侧栏一行。
     *
     * @param conversationId 会话 ID（后续请求要带上它）
     * @param title          <b>可能为 null</b>（会话已创建但标题还没生成）—— 前端显示"新会话"
     * @param mode           模式的线上名称：{@code chat} / {@code workflow}
     *                       （小写；不是枚举名 {@code CHAT}，见 {@code MemoryMode.wireName()}）
     * @param createdAt      创建时间
     * @param updatedAt      最近活跃时间（列表按它倒序）
     */
    public record SessionSummary(String conversationId,
                                 String title,
                                 String mode,
                                 Instant createdAt,
                                 Instant updatedAt) {
    }

    /**
     * 一段思考（一个"步"的思考）—— <b>SSE 与历史回放共用的形状</b>。
     *
     * <p>流式时前端靠 {@code step} 事件自己攒出同样的结构，回放时后端从
     * {@code chat_message.reasoning} 的文本里解析出来 —— 两条路径的渲染逻辑只有一份。
     *
     * @param step  第几步（从 1 开始）
     * @param tools 该步之后模型请求调用的工具名（可能为空）
     * @param text  该步的思考正文（可能为空）
     */
    public record ReasoningSection(int step, List<String> tools, String text) {
    }

    /**
     * 一轮对话的统计 —— <b>SSE 与历史回放共用的形状</b>。
     *
     * <p>理由与 {@link ReasoningSection} 相同：实时那一行小字来自 {@code stats} 事件，
     * 刷新后来自历史接口，两条路径必须给同一份形状，否则前端要为同一行字写两套渲染。
     * 数字只有一个来源（{@code chat_turn}），所以"刷新前后数字不一样"在结构上不可能发生。
     *
     * <p>⚠️ <b>不含身份</b>（{@code user_id} / {@code role}）：浏览器就是这个人，回显没有信息量。
     *
     * @param status            {@code SUCCESS} / {@code FAILED} / {@code CANCELLED}
     * @param elapsedMs         整轮耗时（毫秒）
     * @param firstTokenMs      首个 token 耗时；<b>null = 一个字都没吐出来</b>
     * @param modelCalls        本轮模型调用次数
     * @param toolCalls         本轮工具调用次数
     * @param totalTokens       整轮 token；<b>null = 没拿到 usage</b>（不是 0）
     * @param usageMissingCalls 其中几次没拿到 usage（前端据此显示"token 未知"）
     */
    public record TurnStatsView(String status,
                                long elapsedMs,
                                Long firstTokenMs,
                                int modelCalls,
                                int toolCalls,
                                Integer totalTokens,
                                int usageMissingCalls) {
    }

    /**
     * 历史里的一条消息。
     *
     * <p>★ <b>没有时间戳</b>：消息表有 {@code created_at}，但"一条消息是几点"目前
     * <b>没有任何消费者</b>（按顺序排就够了），而把它读出来要多走一趟类型转换。
     *
     * @param role      {@code USER} / {@code ASSISTANT} / {@code SYSTEM}
     * @param content   正文
     * @param reasoning 该条消息的思考分段。<b>空列表表示没有思考</b>（不是 null）；
     *                  只有 {@code ASSISTANT} 可能有内容
     * @param stats     这一轮的统计。<b>可能为 null</b>：账写入失败的轮次没有数字 ——
     *                  <b>不编造</b>，前端据此决定不显示那一行
     */
    public record SessionMessage(String role, String content, List<ReasoningSection> reasoning,
                                 TurnStatsView stats) {
    }

    /**
     * 一个会话的完整详情：元信息 + 全部消息。
     *
     * <p><b>为什么把 session 一起返回</b>：前端点开历史会话时需要 ①标题 ②模式 ③消息。
     * 合在一个响应里，避免"打开会话"变成两次请求（而且两次之间可能不一致）。
     */
    public record SessionDetail(SessionSummary session, List<SessionMessage> messages) {
    }

    /**
     * 改名请求。
     *
     * <p>{@code @Size(max = 128)} 与 {@code chat_conversation.title} 的 {@code VARCHAR(128)}
     * 对齐 —— <b>在校验层就拦住</b>，而不是让数据库抛 {@code Data too long}
     * （那会变成 500，且看起来像服务故障）。
     */
    public record RenameRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 128, message = "标题不能超过 128 个字符")
            String title) {
    }
}
