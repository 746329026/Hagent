package com.hagent.advisor;

import com.hagent.config.AdvisorProperties;

/**
 * 切面日志里 <b>prompt / 回复文本的格式化</b> —— 截断规则与单行化的唯一出处。
 *
 * <h2>★ 为什么抽出这个类，而不是各切面各写一份</h2>
 *
 * <p>阶段 6 后半起有<b>两个</b>切面要打印 prompt 文本：
 * <ul>
 *   <li>{@code PromptTraceAdvisor} —— 记"请求进链时的样子"与回复；</li>
 *   <li>{@code PromptAssemblyAdvisor} —— 记"模型真正收到的 prompt"。</li>
 * </ul>
 *
 * <p>两处要用的截断规则是同一条（{@link AdvisorProperties#getMaxLogLength()}）。
 * 若各写一份，"一处改了另一处没改"不会报错，只会让两条日志的口径慢慢分叉 ——
 * 而排查时正是靠对比这两条日志来看"注入生效了没有"。
 *
 * <h2>★ 为什么必须截断</h2>
 *
 * <p>对应参考实现的一个真实问题：它把完整 prompt 打进 INFO 日志。
 * 一次 RAG 调用会带上千字检索到的企业文档，结果是
 * ① 日志文件暴涨；② <b>用户输入与内部文档内容全部落进日志</b>，形成隐私风险。
 *
 * <p>需要看全文时用 {@code hagent.advisor.trace.log-full-content=true} 临时打开 ——
 * 那是排查手段，不是常态。
 *
 * <h2>★ 为什么要压成单行</h2>
 *
 * <p>prompt 天然含换行（拼接出来的片段块是多行的）。不压平的话，
 * 一条日志会散成几十行，在日志文件里既难读、也破坏了
 * "一行一条记录"这个可被 grep 的结构。
 */
final class PromptLogFormat {

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
    static String truncate(String text, AdvisorProperties props) {
        if (text == null) {
            return "<null>";
        }
        // 换行会让一条日志散成多行，排查时反而难读，所以先压成一行
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
