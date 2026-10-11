package com.hagent.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容指纹工具 —— 幂等入库的判据来源。
 *
 * <h2>★ 为什么幂等需要"两个"哈希而不是一个</h2>
 *
 * <p>入库时有两个不同的问题要回答，它们的判据不同：
 *
 * <table border="1">
 *   <caption>两级判据</caption>
 *   <tr><th>问题</th><th>判据</th><th>作用</th></tr>
 *   <tr>
 *     <td>"这篇文档变了吗？"</td>
 *     <td>{@code docHash}（全文哈希）</td>
 *     <td><b>没变就整篇跳过</b> —— 省掉切分与 embedding 的全部成本</td>
 *   </tr>
 *   <tr>
 *     <td>"这个块变了吗？"</td>
 *     <td>{@code contentHash}（块哈希）</td>
 *     <td>文档变了但只有一节改动时，<b>只重算改动的那部分</b>；
 *         也用于将来排查"为什么同一个块入库了两次"</td>
 *   </tr>
 * </table>
 *
 * <p>只有 {@code docHash} 也能工作（文档变了就整篇重算），但那样
 * 改一个标点就要重新 embedding 整篇文档 —— 对几十篇文档的批量入库是可以接受的，
 * 对"每天同步一批文档"的场景则浪费明显。所以两级都要，
 * 而 {@code contentHash} 的成本几乎为零（切分后本来就有每块的文本）。
 *
 * <h2>为什么用 SHA-256 而不是 {@code String.hashCode()}</h2>
 *
 * <p>{@code hashCode()} 只有 32 位，且<b>不保证跨 JVM 版本稳定</b>。
 * 哈希在这里承担的是"相等性判据"的角色 —— 一旦发生碰撞，
 * 结果就是"内容不同的两个块被当成同一个，其中一个被静默跳过、永久缺失"。
 * 这类错误不会报错、只会在某个检索不到的时刻暴露，所以必须用抗碰撞的算法。
 *
 * <h2>为什么要归一化空白</h2>
 *
 * <p>哈希前会统一换行符与首尾空白。理由：同一份文件在不同操作系统上
 * 检出时换行符会不同（CRLF vs LF），若不做归一化，<b>仅仅换行符差异
 * 就会让哈希变化</b>，导致每次跨平台同步都触发全量重新入库。
 * 而空白差异对内容语义没有影响，归一化不会掩盖真实的内容变更。
 */
public final class HashingSupport {

    private HashingSupport() {
    }

    /**
     * 计算文本的内容指纹。
     *
     * @param text 待哈希的文本；null 视为空串（返回空串的哈希）
     * @return 64 位十六进制小写字符串
     */
    public static String sha256(String text) {
        String normalized = normalize(text);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明 JVM 环境异常
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算内容指纹", e);
        }
    }

    /**
     * 归一化：统一换行符、去掉首尾空白。
     *
     * <p>只做这两件事是刻意的 —— <b>不做更多的"清理"</b>（如合并连续空格、
     * 去掉空行）。因为哈希的职责是"忠实反映内容是否变化"，
     * 过度归一化会掩盖真实的内容改动，让"改了但哈希没变"这种
     * 静默失配发生。而归一化换行符与首尾空白是为了跨平台一致性（见类注释）。
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
