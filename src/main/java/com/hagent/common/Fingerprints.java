package com.hagent.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <b>哈希计算的唯一实现</b>。
 *
 * <p>两个方法的区别（<b>不要合并</b>）：
 * <ul>
 *   <li>{@link #content} —— <b>先归一化</b>（统一换行符 + 去首尾空白）再取 SHA-256，用于内容判重：
 *       跨平台检出时换行符不同，不归一化会每次都重算整篇；</li>
 *   <li>{@link #identifier} —— 裸 SHA-256，当文件名 / 目录名用：输入本身已是规范化的短标识，
 *       归一化只会掩盖"两个 ID 其实是同一个"。</li>
 * </ul>
 *
 * <p>为什么必须是同一份实现：两份拷贝（一份归一化、一份不归一化）的漂移不是编译错误，
 * 而是"同一个输入在两处得到不同指纹"，表现为"幂等判重偶尔失效"，不报错。
 * 顺带消掉了一条不应存在的依赖边（{@code memory → document}）：哈希是跨域通用词汇，归 {@code common}。
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
     * 裸 SHA-256（<b>不归一化</b>）—— 用于把标识变成安全的文件名 / 目录名。
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
     * <p>只做这两件事是刻意的 —— <b>不做更多"清理"</b>（合并连续空格、删空行等）：
     * 过度归一化会掩盖真实的内容改动，让"改了但指纹没变"这种静默失配发生。
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
