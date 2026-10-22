package com.hagent.app;

import com.hagent.config.TraceProperties;

/**
 * 日志里<b>文本的格式化</b> —— 压单行与截断的唯一出处（两处日志必须同一口径；各写一份时"一处改了
 * 另一处没改"不会报错，只会让口径分叉，而排查正是靠对比这两条日志）。
 *
 * <p>截断与压单行都是必须的：完整 prompt 一次带上千字文档会让日志暴涨、把用户输入与内部文档落进
 * 日志（需要全文时用 {@code hagent.trace.log-full-content=true} 临时打开）；prompt 天然含换行，不压平
 * 一条日志会散成几十行，破坏"一行一条记录"这个可被 grep 的结构。
 */
public final class PromptLogFormat {

    private PromptLogFormat() {
        // 工具类，禁止实例化
    }

    /**
     * 把文本压成单行，并按配置截断。
     *
     * @param text  原始文本；允许为 null
     * @param props 截断配置
     * @return 可直接写进日志的文本（不会为 null）
     */
    public static String truncate(String text, TraceProperties props) {
        if (text == null) {
            return "<null>";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (props.isLogFullContent()) {
            return oneLine;
        }
        int max = props.getMaxLogLength();
        if (max > 0 && oneLine.length() > max) {
            return oneLine.substring(0, max) + "...[截断，共 " + oneLine.length() + " 字符]";
        }
        return oneLine;
    }
}
