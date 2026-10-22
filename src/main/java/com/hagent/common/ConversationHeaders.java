package com.hagent.common;

/**
 * HTTP 头名的<b>唯一出处</b>。
 *
 * <p>它原先是 {@code ChatController} 里的常量，而 {@code WebConfig} 要用它做 {@code exposedHeaders(...)}
 * （否则浏览器读不到响应头且<b>不报错</b>），于是形成反向边 {@code config → controller}，故搬到 {@code common}。
 *
 * <p>⚠️ 这条边在字节码里<b>根本不存在</b>：{@code ID} 是编译期常量，javac 把值<b>内联</b>到使用处，
 * 所以任何字节码级守卫（含 ArchUnit）都看不见它，只有源码扫描能发现。代价是该常量在字节码里
 * 有<b>两份副本</b>：按"每个文件"增量编译的构建器（IDEA）只改这一处值时可能只重编一侧，
 * 症状是"前端读不到会话 ID"且<b>没有任何报错</b>。
 */
public final class ConversationHeaders {

    /**
     * 会话 ID 的响应头名。
     *
     * <p>前端读出后下次请求原样带回 —— 它是"续用同一会话"的唯一凭据
     * （<b>不用</b> URL 参数：URL 会进访问日志、浏览器历史与 Referer）。
     */
    public static final String ID = "X-Conversation-Id";

    private ConversationHeaders() {
    }
}
