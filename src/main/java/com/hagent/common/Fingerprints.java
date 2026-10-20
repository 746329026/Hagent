package com.hagent.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <b>哈希计算的唯一实现</b>（阶段 12 第 2 步：两份重复收口到一处）。
 *
 * <h2>★ 为什么必须有它</h2>
 *
 * <p>阶段 12 之前，SHA-256 的调用点在两个包里各写了一份：
 * <ul>
 *   <li>{@code document.HashingSupport#contentFingerprint} —— <b>先归一化</b>再哈希（入库判重）；</li>
 *   <li>{@code memory.longterm.FileLongTermMemoryStore#sha256Hex} —— <b>不归一化</b>（当文件名用）。</li>
 * </ul>
 * 两份都是"取 SHA-256"这一步，只是<b>是否归一化</b>不同。它们的漂移不会是编译错误，
 * 而是"同一个输入在两处得到不同指纹" —— 然后表现为"幂等判重偶尔失效"，不报错。
 *
 * <p>★ 顺带解决一条**不该存在的依赖边**：{@code memory.record} 为了复用哈希，
 * 曾经直接依赖 {@code document.HashingSupport}（{@code memory → document}）。
 * 哈希是<b>跨域的通用词汇</b>，放 {@code common} 后那条边消失（第 1 步留下的账）。
 *
 * <h2>两个方法的区别（不要合并）</h2>
 *
 * <table border="1">
 *   <caption>两种指纹</caption>
 *   <tr><th>方法</th><th>是否归一化</th><th>用途</th></tr>
 *   <tr><td>{@link #content}</td><td><b>是</b>（换行符 + 首尾空白）</td>
 *       <td>内容判重：跨平台检出时换行符不同，不归一化会每次都重算整篇</td></tr>
 *   <tr><td>{@link #identifier}</td><td><b>否</b>（裸 SHA-256）</td>
 *       <td>当文件名/目录名用：输入本身就是规范化的短标识，归一化只会掩盖"两个 ID 其实是同一个"</td></tr>
 * </table>
 *
 * <p>为什么用 SHA-256 而不是 {@code String.hashCode()}：后者只有 32 位且不保证跨 JVM 稳定；
 * 这里承担的是"相等性判据"，一旦碰撞就是"内容不同的两份被当成同一份、其中一份被静默跳过"。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 内容指纹：<b>先归一化</b>（统一换行符、去掉首尾空白）再取 SHA-256。
     *
     * @param text 待哈希的文本；{@code null} 视为空串
     * @return 64 位十六进制小写字符串
     */
    public static String content(String text) {
        return identifier(normalize(text));
    }

    /**
     * 裸 SHA-256（<b>不归一化</b>）—— 用于把标识变成安全的文件名/目录名。
     *
     * @param input 输入文本；{@code null} 视为空串
     * @return 64 位十六进制小写字符串
     */
    public static String identifier(String input) {
        String value = input == null ? "" : input;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明 JVM 环境异常
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 归一化：统一换行符、去掉首尾空白。
     *
     * <p>★ 只做这两件事是刻意的 —— <b>不做更多"清理"</b>（合并连续空格、删空行等）。
     * 过度的归一化会掩盖真实的内容改动，让"改了但指纹没变"这种静默失配发生。
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
