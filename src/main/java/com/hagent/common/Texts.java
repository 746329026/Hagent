package com.hagent.common;

/**
 * 文本处理的小工具 —— <b>跨模块共用的唯一出处</b>（协议第 13 条）。
 *
 * <h2>★ 为什么会有这个类</h2>
 *
 * <p>阶段 10 在 {@code ConversationCatalog} 里写过一次"按码点截断"，
 * 阶段 11 的长期记忆又要截断摘要。**同一段逻辑写两遍**正是协议第 13 条要防的：
 * 两处会漂移，而漂移的表现是"某个长度刚好超一点的内容被切成了半个字符"——
 * 它不会报错，只会在某次插入数据库时报一个与标题毫无关系的错。
 *
 * <p>所以把它抽到这里，两处都调它。
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 把多行文本压成一行：所有空白序列（含换行）折叠成一个空格，并去掉首尾空白。
     *
     * @return 压好的单行；输入为 null 时返回 {@code ""}
     */
    public static String singleLine(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    /**
     * 按<b>码点</b>截断（不是 {@code String.length()}）。
     *
     * <p>★ 为什么必须按码点：{@code length()} 数的是 UTF-16 的 char，一个 emoji 占 2 个。
     * 用 {@code substring} 从中间切断代理对，会得到一个<b>非法的半个字符</b>，
     * 写进 utf8mb4 列时报错 —— 而报错的那一刻看起来与"标题/摘要"毫无关系。
     *
     * @param text   原文；为 null 时返回 {@code ""}
     * @param max    最多保留多少个<b>码点</b>（&lt;=0 表示不截断）
     * @param suffix 截断后追加的后缀（如 "…"）；不想要就传 {@code ""}
     */
    public static String truncateByCodePoints(String text, int max, String suffix) {
        String safe = text == null ? "" : text;
        if (max <= 0 || safe.codePointCount(0, safe.length()) <= max) {
            return safe;
        }
        String head = safe.substring(0, safe.offsetByCodePoints(0, max));
        return head + (suffix == null ? "" : suffix);
    }
}
