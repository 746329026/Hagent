package com.hagent.memory;

import lombok.extern.slf4j.Slf4j;
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
 * 消息与"可持久化结构"之间的转换 —— 三实现共用的唯一出处。
 *
 * <p><b>为什么要抽出来</b>：file 与 redis（以及阶段 7 的 mysql）都要做
 * "Message ↔ 可序列化结构"的转换。若各自实现，一旦消息类型增加
 * （比如阶段 8 要持久化 TOOL 类型消息），三个实现都要改，而且极易漏改一个 ——
 * 这正是参考实现的坑（三个记忆实现各抄一份序列化代码）。
 *
 * <p><b>为什么用中间结构而不是直接序列化 Message 对象</b>：
 * {@code Message} 是框架接口，其实现类带有大量运行时字段（元数据、工具调用、媒体等），
 * 直接序列化会：① 产生脆弱的耦合（框架升级字段变化就崩）；
 * ② 存下大量我们不需要的数据。
 * 转成"只含类型 + 文本"的中间结构，<b>存储格式由我们掌控</b>。
 *
 * <p>⚠️ 当前只保留 {@code type + text} 两类信息。
 * 阶段 8 引入工具调用后，TOOL 类型消息需要额外字段（工具名、调用 ID 等），
 * 届时扩展 {@link StoredMessage} 即可 —— <b>这是一个明确的待扩展点</b>。
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    /**
     * 可持久化的消息结构。
     *
     * <p>用 record 而不是直接存 Message：字段少、可控、Jackson 能稳定序列化。
     *
     * @param type 消息类型名（USER / ASSISTANT / SYSTEM / TOOL）
     * @param text 文本内容
     */
    public record StoredMessage(String type, String text) {
    }

    /** Message → 可持久化结构。 */
    public static StoredMessage toStored(Message message) {
        return new StoredMessage(message.getMessageType().name(), message.getText());
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
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            // TOOL 类型当前不持久化（阶段 8 引入工具调用时再扩展）
            case TOOL -> throw new IllegalStateException(
                    "TOOL 类型消息尚未支持持久化（阶段 8 扩展 MessageCodec）");
        };
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
