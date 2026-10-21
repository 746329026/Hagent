package com.hagent.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息与"可持久化结构"之间的转换 —— 多个实现共用的唯一出处。
 *
 * <p><b>为什么要抽出来</b>：file 与 redis（以及阶段 7 的 mysql）都要做
 * "Message ↔ 可序列化结构"的转换。若各自实现，一旦消息类型增加
 * （比如阶段 9 要持久化 TOOL 类型消息），所有实现都要改，而且极易漏改一个 ——
 * 这正是参考实现的坑（三个记忆实现各抄一份序列化代码）。
 *
 * <p><b>为什么用中间结构而不是直接序列化 Message 对象</b>：
 * {@code Message} 是框架接口，其实现类带有大量运行时字段（元数据、工具调用、媒体等），
 * 直接序列化会：① 产生脆弱的耦合（框架升级字段变化就崩）；
 * ② 存下大量我们不需要的数据。
 * 转成"只含类型 + 文本"的中间结构，<b>存储格式由我们掌控</b>。
 *
 * <h2>★ 阶段 10：思考内容怎么进这个结构（以及为什么**不是** {@code reasoningContent}）</h2>
 *
 * <p>思考内容在 {@link #toStored} 里被读出来、在 {@link #fromStored} 里被写回，
 * 走的是 {@link AssistantMessage} 的 <b>properties（元数据）</b>，键为
 * {@link #REASONING_PROPERTY}。
 *
 * <p><b>为什么不用 {@code DeepSeekAssistantMessage.reasoningContent}</b> —— 这条是实测出来的，
 * 不是为了"干净"：{@code DeepSeekChatModel.createRequest} 里会有
 * {@code instanceof DeepSeekAssistantMessage → getReasoningContent()}，
 * 把它<b>重新填进发出去的请求</b>。而 {@code reasoning_content} 是
 * <b>输出专用字段</b>：回传轻则白烧 token、重则 HTTP 400。
 * 用 properties 存则根本不进请求构建器 —— 前端能显示，模型永远看不到。
 * 症状会是"下一轮对话莫名失败"，而根因几乎不可能被联想到"历史里带了思考"。
 *
 * <p>所以本类只做"存取"，不做"优化"：<b>不要</b>把它改成建一个
 * {@code DeepSeekAssistantMessage} 再塞 reasoningContent。
 */
public final class MessageCodec {

    /**
     * 思考内容在 {@link AssistantMessage} properties 里的键。
     *
     * <p>带 {@code hagent_} 前缀：它是<b>我们</b>的键，不是框架的，
     * 前缀能避免与框架将来可能引入的元数据键撞名。
     */
    public static final String REASONING_PROPERTY = "hagent_reasoning";

    /**
     * 本轮 {@code turn_id} 在 properties 里的键（阶段 14 后补）。
     *
     * <p><b>为什么它也走 properties</b>：它是"这条回答是哪一次服务产生的"这个元数据，
     * 与思考同一个性质 —— <b>前端要用，模型绝不该看到</b>。
     * 走 properties 的好处是<b>接口一行都不用改</b>：{@code MessageStore.append} 仍然
     * 只收 {@code List<Message>}，而两个实现都通过 {@link #toStored} 统一取值。
     *
     * <p>它的唯一用途：历史回放时把这条 assistant 消息关联到 {@code chat_turn} 的那一行，
     * 从而显示"这一轮花了多久 / 多少 token"。没有它，"刷新页面后那一行数字消失"。
     */
    public static final String TURN_ID_PROPERTY = "hagent_turnId";

    private MessageCodec() {
    }

    /**
     * 可持久化的消息结构。
     *
     * <p>用 record 而不是直接存 Message：字段少、可控、Jackson 能稳定序列化。
     *
     * @param type      消息类型名（USER / ASSISTANT / SYSTEM / TOOL）
     * @param text      文本内容
     * @param reasoning 思考内容（阶段 10）。<b>只有 assistant 消息可能有</b>，
     *                  其余为 null；用户消息没有思考这种东西。
     * @param turnId    产生这条回答的轮次标识（阶段 14 后补）。<b>只有 assistant 消息可能有</b>；
     *                  阶段 14 之前写入的历史为 null（那时还没有"回合账"）
     */
    public record StoredMessage(String type, String text, String reasoning, String turnId) {

        /**
         * 两参便捷构造 —— 给"没有思考内容"的场景用。
         *
         * <p>保留它不是为了兼容：{@link #toJsonFriendly} 那条 Redis 路径
         * 本来就不带思考（缓存的是<b>给模型看的那一份</b>，模型不看思考）。
         */
        public StoredMessage(String type, String text) {
            this(type, text, null, null);
        }

        /** 三参构造 —— 阶段 14 之前写入的 file 格式（没有 turnId）。 */
        public StoredMessage(String type, String text, String reasoning) {
            this(type, text, reasoning, null);
        }
    }

    /** Message → 可持久化结构。 */
    public static StoredMessage toStored(Message message) {
        return new StoredMessage(message.getMessageType().name(),
                message.getText(), reasoningOf(message), turnIdOf(message));
    }

    /**
     * 从消息的 properties 里取思考内容。取不到返回 null（"没有思考"是合法状态）。
     *
     * <p>★ 只认 {@code AssistantMessage}：思考是模型产物的属性，
     * 用户/系统/工具消息上出现它没有语义。
     *
     * <p>它是 public 的：读历史时（{@code GET /chat/sessions/{id}/messages}）
     * 需要把落库的文本取出来、再交给 {@link ReasoningTrace} 解析成分段。
     * 让"思考存在哪"这件事只有一个读取出口 —— 否则读的一侧会自己拼一个键名，
     * 而键名拼错的表现是"历史里的思考永远是空的"，<b>不报错</b>。
     */
    public static String reasoningOf(Message message) {
        if (!(message instanceof AssistantMessage assistant)) {
            return null;
        }
        Map<String, Object> metadata = assistant.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(REASONING_PROPERTY);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * 从消息的 properties 里取轮次标识。取不到返回 null（"没有回合账"是合法状态：
     * 阶段 14 之前写入的历史都没有）。
     *
     * <p>★ 与 {@link #reasoningOf} 同一模式：<b>"它存在哪"只有一个读取出口</b>。
     * 读的一侧自己去拼键名，拼错的表现是"历史里那一行统计永远是空的"，而<b>不报错</b>。
     */
    public static String turnIdOf(Message message) {
        if (!(message instanceof AssistantMessage assistant)) {
            return null;
        }
        Map<String, Object> metadata = assistant.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(TURN_ID_PROPERTY);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** 批量转换（保序）。 */
    public static List<StoredMessage> toStoredList(List<Message> messages) {
        List<StoredMessage> result = new ArrayList<>(messages.size());
        for (Message m : messages) {
            result.add(toStored(m));
        }
        return result;
    }

    /**
     * 可持久化结构 → Message。
     *
     * <p>遇到未知类型时<b>不抛异常，而是跳过并记录</b>：
     * 这样"旧版本存下的数据、新版本读到"不会导致整个会话不可用 ——
     * 存储格式的向前兼容由这里兜住。
     */
    public static Message fromStored(StoredMessage stored) {
        MessageType type;
        try {
            type = MessageType.valueOf(stored.type());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "无法识别的消息类型（存储格式可能来自更新版本）：" + stored.type(), e);
        }
        String text = stored.text() == null ? "" : stored.text();
        return switch (type) {
            case USER -> new UserMessage(text);
            // ★ 思考内容与 turn_id 挂回 properties（不是 reasoningContent），理由见类注释
            case ASSISTANT -> assistantOf(text, stored);
            case SYSTEM -> new SystemMessage(text);
            // TOOL 类型当前不持久化（工具活动进审计表 chat_tool_invocation）
            case TOOL -> throw new IllegalStateException(
                    "TOOL 类型消息尚未支持持久化（工具活动记在 chat_tool_invocation 表）");
        };
    }

    /**
     * 还原一条 assistant 消息：把落库的思考与轮次标识挂回 properties。
     *
     * <p>抽出来只为一件事：<b>两个键都要挂，且缺哪个都不能建空 map</b>
     * （{@code Map.of()} 空表传给 builder 虽然目前不炸，但没有任何意义）。
     */
    private static AssistantMessage assistantOf(String text, StoredMessage stored) {
        boolean hasReasoning = stored.reasoning() != null && !stored.reasoning().isBlank();
        boolean hasTurnId = stored.turnId() != null && !stored.turnId().isBlank();
        if (!hasReasoning && !hasTurnId) {
            return new AssistantMessage(text);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        if (hasReasoning) {
            properties.put(REASONING_PROPERTY, stored.reasoning());
        }
        if (hasTurnId) {
            properties.put(TURN_ID_PROPERTY, stored.turnId());
        }
        return AssistantMessage.builder()
                .content(text)
                .properties(properties)
                .build();
    }

    /** 批量转换（保序，跳过无法识别的类型并记录）。 */
    public static List<Message> fromStoredList(List<StoredMessage> stored) {
        List<Message> result = new ArrayList<>(stored.size());
        for (StoredMessage s : stored) {
            try {
                result.add(fromStored(s));
            } catch (Exception e) {
                // 单条读不出来不应让整个会话失效 —— 记录并跳过
                org.slf4j.LoggerFactory.getLogger(MessageCodec.class)
                        .warn("[记忆] 跳过一条无法反序列化的消息：type={} 原因={}", s.type(), e.getMessage());
            }
        }
        return result;
    }

    /** 供 Redis 实现使用：把消息列表转成 JSON 友好的结构。 */
    public static List<Map<String, String>> toJsonFriendly(List<Message> messages) {
        List<Map<String, String>> list = new ArrayList<>(messages.size());
        for (Message m : messages) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("type", m.getMessageType().name());
            one.put("text", m.getText());
            list.add(one);
        }
        return list;
    }

    /** 供 Redis 实现使用：从 JSON 友好结构还原。 */
    public static List<Message> fromJsonFriendly(List<Map<String, String>> list) {
        List<Message> result = new ArrayList<>(list.size());
        for (Map<String, String> one : list) {
            try {
                result.add(fromStored(new StoredMessage(one.get("type"), one.get("text"))));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(MessageCodec.class)
                        .warn("[记忆] 跳过一条无法还原的消息：{}", one, e);
            }
        }
        return result;
    }
}
