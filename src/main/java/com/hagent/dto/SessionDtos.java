package com.hagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 会话列表 / 历史 / 改名 的传输对象（阶段 10）。
 *
 * <p><b>为什么集中在一个文件里、用嵌套 record</b>：它们是<b>同一个契约的四个面</b>
 * （侧栏一行、一条消息、一个会话的详情、一次改名请求），字段之间互相引用，
 * 拆成四个文件会让"它们是一套"这件事在目录里看不出来。
 * 而单个 record 仍然各自独立、不可变 —— 与 {@code PipelineEvent} 的嵌套方式一致。
 *
 * <p>★ 这些 record 的<b>字段名就是前端契约</b>：改名字等于破坏前端，
 * 而它不会报错，只会让前端拿到 {@code undefined}。所以它们刻意不直接复用
 * 存储层的 {@code ConversationCatalog.Session} / {@code ReasoningTrace.Section} ——
 * 那两个类是<b>存储形态</b>，允许随实现演化。
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
     *                       （不是枚举名 {@code CONVERSATION}，见 {@code MemoryMode.wireName()}）
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
     * 一段思考（一个"步"的思考）。
     *
     * <p>这是 <b>SSE 与历史回放共用的形状</b>：流式时前端靠 {@code step} 事件自己攒出同样的结构，
     * 回放时后端从 {@code chat_message.reasoning} 的文本里解析出来。
     * 两条路径的渲染逻辑因此<b>只有一份</b>。
     *
     * @param step  第几步（从 1 开始）
     * @param tools 该步之后模型请求调用的工具名（可能为空）
     * @param text  该步的思考正文（可能为空）
     */
    public record ReasoningSection(int step, List<String> tools, String text) {
    }

    /**
     * 历史里的一条消息。
     *
     * <p>★ <b>没有时间戳</b>：消息表有 {@code created_at}，但"一条消息是几点"目前
     * <b>没有任何消费者</b>（对话界面按顺序排就够了），而把它读出来要多走一趟
     * 类型转换。等真有"显示消息时间"的需求再加 —— 那时改的是这个 record 与一次查询。
     *
     * @param role      {@code USER} / {@code ASSISTANT} / {@code SYSTEM}
     * @param content   正文
     * @param reasoning 该条消息的思考分段。<b>空列表表示没有思考</b>（不是 null）；
     *                  只有 {@code ASSISTANT} 可能有内容
     */
    public record SessionMessage(String role, String content, List<ReasoningSection> reasoning) {
    }

    /**
     * 一个会话的完整详情：元信息 + 全部消息。
     *
     * <p><b>为什么把 session 一起返回</b>：前端点开一个历史会话时，需要
     * ①标题（显示在顶部）②模式（决定是否显示"工作流编排开发中"的提示）
     * ③消息。合在一个响应里，避免"打开会话"变成两次请求（而且两次之间可能不一致）。
     */
    public record SessionDetail(SessionSummary session, List<SessionMessage> messages) {
    }

    /**
     * 改名请求。
     *
     * <p>{@code @Size(max = 128)} 与 {@code chat_conversation.title} 的
     * {@code VARCHAR(128)} 对齐 —— <b>在校验层就拦住</b>，
     * 而不是让数据库抛 {@code Data too long}（那会变成 500，且看起来像服务故障）。
     */
    public record RenameRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 128, message = "标题不能超过 128 个字符")
            String title) {
    }
}
