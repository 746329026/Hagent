package com.hagent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考内容的<b>存储格式</b>：一段文本 + 分隔标记 ↔ 结构化分段。
 *
 * <h2>★ 它解决什么问题</h2>
 *
 * <p>一个回合（一次用户输入 → 最终回答）里，模型可能被调用<b>多次</b>（每次工具调用后
 * 重新决策一次）。落库时只有<b>一个</b> {@code chat_message.reasoning} 文本列（一行 = 一个回合），
 * 但这几段思考属于<b>不同的步</b>，中间夹着工具调用。
 *
 * <p>把它们的边界压平掉，就得到一个"读得出、查不出"的字段：
 * <pre>
 *   "先查知识库…没查到…换个说法再查…还是不行，直接答"
 * </pre>
 * 无法回答"它在第几次工具调用后改的主意"—— 而多步任务的价值恰好在这些转折上。
 *
 * <h2>★ 两个方法，一对正反</h2>
 *
 * <pre>
 *   render(List&lt;Section&gt;)  →  String   （写库时：结构化 → 文本）
 *   parse(String)            →  List&lt;Section&gt;（读库时：文本 → 结构化）
 * </pre>
 *
 * <p><b>为什么格式收在这一个类里</b>：标记语法是<b>前后两半共用</b>的契约 ——
 * 写的一侧改了格式、读的一侧没跟上，症状是"历史里的思考忽然全变成第 1 步"，
 * 而这<b>不会报错</b>。让语法只存在于一个文件里，是防止这种漂移的唯一办法。
 *
 * <h2>★ 标记格式与它的两条约束</h2>
 *
 * <pre>
 *   @@step:2 tool:retrieveKnowledge
 *   第 2 步的思考…
 * </pre>
 *
 * <ol>
 *   <li><b>必须不会与模型自己的输出撞车</b> —— 所以刻意<b>不用</b>
 *       {@code ---} / {@code ###} / {@code ===}：模型自己就爱输出这些 markdown 分隔线，
 *       撞车的结果是把一段思考劈成两半，且完全静默。{@code @@step:} 这种
 *       在自然语言里几乎不可能出现的串才安全；</li>
 *   <li><b>必须人可读</b> —— 有人会直接用 SQL 查这一列。
 *       用 JSON 数组也行，但 `SELECT reasoning` 出来是一坨转义的 {@code \n}，
 *       得先解一次才知道写了什么。而这个字段的第一消费者是<b>人</b>。</li>
 * </ol>
 *
 * <h2>★ 第 1 步为什么不写标记</h2>
 *
 * <p>一段没有标记的思考就是第 1 步。这样<b>单步回合（绝大多数普通对话、没调工具）
 * 存下来就是纯思考文本</b>，没有任何格式噪音 —— 而那是最常见的形态。
 * 只有真的分了步，才会看到标记。
 */
public final class ReasoningTrace {

    /** 标记前缀。改它等于改存储格式（需要迁移数据），所以它只有这一处定义。 */
    public static final String MARKER_PREFIX = "@@step:";

    /** 工具名的分隔符（一步里可能调多个工具）。 */
    private static final String TOOL_SEPARATOR = ",";

    private ReasoningTrace() {
    }

    /**
     * 一步的思考。
     *
     * @param step  第几步，<b>从 1 开始</b>
     * @param tools <b>进入这一步之前</b>执行过的工具名（也就是上一步请求的那些），可能为空。
     *              <p>★ 它们的执行时刻在上一步<b>结束之后</b>，所以“挂在自己那一步”会丢失 ——
     *              第 1 步按约定不写标记（见类注释），若把第 1 步请求的工具挂回第 1 段，
     *              它就没有任何位置可以写。所以统一挂到<b>下一步的标记行</b>上：
     *              <pre>
     *                第 1 步的思考（无标记）
     *                @@step:2 tool:getWeather   ← 上一步请求、本步之前执行
     *                第 2 步的思考
     *              </pre>
     *              <p>⚠️ 因此<b>第 1 段的 {@code tools} 恒为空</b>（{@link #render} 会忽略它）——
     *              这是格式契约的一部分，不是遗漏。
     * @param text  该步的思考正文（可能为空：模型可能直接给出工具调用而不写思考）
     */
    public record Section(int step, List<String> tools, String text) {

        public Section {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /**
     * 结构化 → 存储文本。
     *
     * @return 拼好的文本；<b>所有分段都没有内容时返回 null</b>
     *         （null 而不是空串：空串在库里是"存了但什么都没有"，null 是"没有这件事"，
     *          两者在排查时含义不同，而 SQL 的 {@code IS NULL} 能直接筛出后者）
     */
    public static String render(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(sections.size());
        for (Section section : sections) {
            String body = section.text() == null ? "" : section.text().strip();
            // ★ 与 parse 的 addIfMeaningful 对称：既没思考也没工具名的分段不产出
            //   （它不含任何信息，产出只会让历史里多一行空标题）
            if (body.isEmpty() && section.tools().isEmpty()) {
                continue;
            }
            if (section.step() <= 1) {
                // 第 1 步：不加标记（见类注释）
                if (!body.isEmpty()) {
                    parts.add(body);
                }
                continue;
            }
            StringBuilder head = new StringBuilder(MARKER_PREFIX).append(section.step());
            if (!section.tools().isEmpty()) {
                head.append(" tool:").append(String.join(TOOL_SEPARATOR, section.tools()));
            }
            // ★ 正文为空但工具名不为空时也要写标记：那一步确实发生了（可能直接调了工具），
            //   丢掉标记会让"第 2 步调了 retrieveKnowledge"这个事实消失。
            parts.add(body.isEmpty() ? head.toString() : head + "\n" + body);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /**
     * 存储文本 → 结构化。
     *
     * <p>容错优先：文本为 null / 空白 → 空列表；没有任何标记 → <b>一个 step=1 的分段</b>
     * （这正是单步回合存下来的样子）。所以调用方不需要特判"这条历史有没有分步"。
     *
     * <p>★ 遇到不认识的标记（比如将来改了格式）时<b>不丢弃</b>，而是当作正文的一部分 ——
     * 丢掉它会让一段真实的思考从历史里消失，而"多了一行看不懂的文字"只是难看。
     *
     * <p>★ 分段与 {@link #render} 的规则对称：<b>正文与工具名都为空的分段不产出</b>
     * （它不含任何信息）。于是 {@code parse(render(x))} 对"每个分段都有内容"的 x 是恒等的 ——
     * 这条对称性由一个往返测试守着。
     */
    public static List<Section> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Section> sections = new ArrayList<>();

        // 第一段永远是 step=1（render 不给第 1 步写标记）
        int step = 1;
        List<String> tools = List.of();
        StringBuilder body = new StringBuilder();

        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd);
            Integer markerStep = line.startsWith(MARKER_PREFIX) ? stepOf(line) : null;
            if (markerStep != null) {
                // 上一段收尾，开启新一段
                addIfMeaningful(sections, step, tools, body);
                step = markerStep;
                tools = toolsOf(line);
                body = new StringBuilder();
            } else {
                // ★ 只有【行首】的标记才算标记：正文中间出现的同形串不切分。
                //   不认识的标记（步号非法）也走到这里，被当作正文保留下来。
                body.append(line).append('\n');
            }
            lineStart = lineEnd + 1;
        }
        addIfMeaningful(sections, step, tools, body);
        return sections;
    }

    /** 追加一个分段，但跳过"既没有思考、也没有工具名"的空段（它不含任何信息）。 */
    private static void addIfMeaningful(List<Section> sections,
                                        int step,
                                        List<String> tools,
                                        StringBuilder body) {
        String content = body.toString().strip();
        if (content.isEmpty() && tools.isEmpty()) {
            return;
        }
        sections.add(new Section(step, tools, content));
    }

    /**
     * 从标记行里取步号。
     *
     * @return 步号；不是合法标记时返回 null（调用方会把它当正文）
     */
    private static Integer stepOf(String line) {
        String rest = line.substring(MARKER_PREFIX.length()).trim();
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            int step = Integer.parseInt(rest.substring(0, end));
            return step >= 1 ? step : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从标记行里取工具名列表（{@code tool:a,b} 部分）；没有则空列表。 */
    private static List<String> toolsOf(String line) {
        int index = line.indexOf(" tool:");
        if (index < 0) {
            return List.of();
        }
        String raw = line.substring(index + " tool:".length()).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        for (String one : raw.split(TOOL_SEPARATOR)) {
            String name = one.trim();
            if (!name.isEmpty()) {
                tools.add(name);
            }
        }
        return List.copyOf(tools);
    }
}
