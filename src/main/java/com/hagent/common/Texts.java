package com.hagent.common;

/**
 * 文本处理小工具 —— <b>跨模块共用的唯一出处</b>。
 *
 * <p>同一段"按码点截断"在两处各写一遍就会漂移，而漂移的表现是
 * "某个长度刚好超一点的内容被切成了半个字符"—— 切的时候不报错，
 * 只在插入数据库时报一个与标题毫无关系的错。
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
     * <p>{@code length()} 数的是 UTF-16 的 char，一个 emoji 占 2 个；{@code substring} 从中间切断
     * 代理对会得到<b>非法的半个字符</b>，写进 utf8mb4 列时报错 —— 而报错的那一刻看起来
     * 与"标题/摘要"毫无关系。
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
