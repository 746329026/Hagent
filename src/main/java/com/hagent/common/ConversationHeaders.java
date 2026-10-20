package com.hagent.common;

/**
 * HTTP 头名的<b>唯一出处</b>（阶段 12 第 0 步搬来）。
 *
 * <h2>★ 为什么它从 {@code controller} 搬到了 {@code common}</h2>
 *
 * <p>它原先声明在 {@code ChatController} 里，而 {@code WebConfig} 需要用它做
 * {@code exposedHeaders(...)}（否则浏览器读不到响应头，且<b>不报错</b>）。
 * 于是形成一条反向边：{@code config → controller} —— 装配层依赖了 HTTP 层。
 *
 * <h2>★★ 这条边的诡异之处：它在字节码里根本不存在</h2>
 *
 * <p>{@code ID} 是<b>编译期常量</b>，javac 会把值<b>内联</b>到使用处：
 * <pre>
 *   javap -c WebConfig
 *     ldc  "X-Conversation-Id"        ← 直接用字面量，零指令引用 ChatController
 * </pre>
 * 也就是说：
 * <ul>
 *   <li><b>任何字节码级守卫（含 ArchUnit）都看不见这条边</b> —— 它只能靠源码扫描发现；</li>
 *   <li>反过来说，把它搬到 {@code common} 之后，字节码<b>一点没变</b>
 *       （该内联的还是内联）—— 变的只是"源码层面谁依赖谁"。</li>
 * </ul>
 *
 * <p>⚠️ <b>内联带来的真实风险（记在这里，别踩）</b>：这个常量在字节码里是<b>两份副本</b>
 * （{@code ChatController} 一份、{@code WebConfig} 一份）。按"每个文件"增量编译的构建器
 * （IDEA）在<b>只改这一处值</b>时可能只重编一侧，症状是"前端读不到会话 ID"且<b>没有任何报错</b>。
 * Maven 是模块级重编，风险低但存在。
 *
 * <h2>★ 为什么放 {@code common} 而不是新建一个包</h2>
 *
 * <p>它只有<b>一个</b>常量、消费者是两个不同层（HTTP 层读、装配层暴露）——
 * 按协议第 7 条，为一个字符串建一个包是纯开销。{@code common} 的定位正是"跨层共享的叶子词汇"。
 */
public final class ConversationHeaders {

    /**
     * 会话 ID 的响应头名。
     *
     * <p>前端把它读出来，下次请求原样带回 —— 它是"续用同一会话"的唯一凭据
     * （<b>不是</b> URL 参数，因为 URL 会进访问日志、浏览器历史与 Referer）。
     */
    public static final String ID = "X-Conversation-Id";

    private ConversationHeaders() {
    }
}
