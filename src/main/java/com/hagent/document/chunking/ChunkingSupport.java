package com.hagent.document.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分实现的<b>共用工具</b> —— 语义边界优先，大小上限兜底。
 *
 * <h2>★ 这个类解决的核心矛盾</h2>
 *
 * <p>切分有两个互相冲突的目标：
 * <ol>
 *   <li><b>按语义边界切</b>（标题、段落、句子）—— 这样每块的向量"含义集中"，检索才准；</li>
 *   <li><b>不超过大小上限</b> —— 否则 embedding 会<b>静默截断</b>，后半部分永远检索不到。</li>
 * </ol>
 *
 * <p>天真做法是先按 1 切，发现超限再按 2 硬切 —— 但那会把句子从中间切断，
 * 留下一块以半个词结尾、下一块以半个词开头的"语义残缺块"。
 * 本类的做法是：<b>超限时退到更小的语义单位继续切</b>（段落 → 句子），
 * 硬切只在"连句子都没有"的极端情况下发生。
 *
 * <h2>★ 关于 overlap（重叠）</h2>
 *
 * <p>重叠的作用是<b>防止语义被边界切断</b>：一句话横跨两块时，
 * 若没有重叠，第二块会以半个句子开头，它的向量就代表了一个不完整的含义。
 *
 * <p>本类的重叠实现方式是"<b>把上一块的末尾若干字符接到下一块开头</b>"，
 * 并<b>优先在句子边界处取重叠</b> —— 这样重叠部分本身也是完整的句子，
 * 而不是从半句话开始。
 *
 * <h2>当前参数取值依据</h2>
 *
 * <p>块大小上限 400 汉字、重叠 60 汉字（由配置提供）。三条依据：
 * <ul>
 *   <li><b>安全余量</b>：embedding 模型上限实测约 1000 汉字（超出静默截断），
 *       400 只用了 40%，留足余量；</li>
 *   <li><b>向量集中度</b>：块越大，块内主题越杂，向量越趋近"多主题的平均值"，
 *       与任何具体问题的相似度都会下降 —— 块不是越大越好；</li>
 *   <li><b>prompt 预算</b>：检索到的块要拼进 prompt，会话预算 5000 token。
 *       400 字 × topK 3 ≈ 1200 token，占约 24%，留足空间给历史与系统提示词。</li>
 * </ul>
 */
public final class ChunkingSupport {

    /** 句末标点 —— 中文与英文的常见句末符号。 */
    private static final String SENTENCE_ENDINGS = "。！？!?；;…";

    /** 最小块大小占上限的比例：合并后仍小于上限 25% 的尾块，尝试并入前一块。 */
    private static final double MIN_RATIO = 0.25;

    private ChunkingSupport() {
    }

    // ========================================================================
    // 大小约束
    // ========================================================================

    /**
     * 把一段文本拆成<b>不超过 maxSize</b> 的若干片段，并在片段之间加重叠。
     *
     * <p>这是"大小上限兜底"的唯一实现 —— 所有切分策略在得到语义单元后都调用它，
     * 因此不会出现"某个策略忘了限长"的情况（这类遗漏会导致 embedding 静默截断）。
     *
     * <p>拆解顺序：<b>句子边界 → 硬切</b>。只有在"单个句子本身就超限"
     * （比如一整段没有标点的长文本）时才会硬切。
     *
     * <h3>★ 重叠与上限的冲突（这里修过一个导致系统性超限的缺陷）</h3>
     *
     * <p>最初的写法是"判断要不要开新块 → 开新块 → 追加重叠 → 追加上句子"，
     * 而<b>大小检查只发生在追加重叠之前</b>。于是当"上一块的末尾重叠"很长时
     * （{@link #tailOverlap} 会退到句子边界，所以它的返回值<b>可能远大于 overlap 参数</b>），
     * 新块会突破上限。暴力组合测试实测到的超限幅度：
     * <pre>
     *   maxSize=60 overlap=45 → 产出 95 字的块（超 35 字）
     *   maxSize=80 overlap=45 → 产出 102 字的块（超 22 字）
     *   maxSize=40 overlap=20 → 产出 60 字的块（超 20 字，即超出上限的 50%）
     * </pre>
     * 也就是说这不是边界抖动，而是<b>随 overlap 增大而系统性恶化</b>的缺陷。
     * 而"超过 maxSize"的后果是 embedding <b>静默截断</b> —— 块尾内容永远检索不到。
     *
     * <p><b>修法</b>：把重叠的追加纳入大小决策 —— 先算出候选重叠，
     * 只有当它与下一句能一起装下时才追加；装不下就<b>放弃这一块的重叠</b>。
     * 这个取舍是刻意的：
     * <ul>
     *   <li>重叠的作用是提升语义连续性，属于<b>锦上添花</b>；</li>
     *   <li>超过上限的后果是"内容永久检索不到"，属于<b>正确性问题</b>。</li>
     * </ul>
     * 两者冲突时必须保上限。
     *
     * <p>由此得到一个重要性质：<b>输出是否超限不再取决于 overlap 的取值</b> ——
     * 无论 overlap 配成 0 还是 45，都不会超限。该性质由
     * {@code ChunkingSupportTest} 的暴力组合测试守卫（穷举 maxSize × overlap × 文本长度）。
     *
     * @param text    待处理的文本（已 trim）
     * @param maxSize 单块最大字符数
     * @param overlap 相邻块的重叠字符数；0 表示不重叠
     */
    public static List<String> enforceMaxSize(String text, int maxSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxSize) {
            result.add(trimmed);
            return result;
        }

        List<String> sentences = splitIntoSentences(trimmed);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            // 单个句子超限 → 输出当前累积，再把这个句子硬切
            // （语义边界已无处可退：连一个句子都装不下）
            if (sentence.length() > maxSize) {
                flush(result, current);
                result.addAll(hardSplit(sentence, maxSize));
                continue;
            }

            if (current.length() + sentence.length() <= maxSize) {
                // 装得下，直接接上
                current.append(sentence);
                continue;
            }

            // 装不下 → 输出当前块，开新块
            String previous = current.toString();
            flush(result, current);

            // ★ 关键修复：重叠的追加必须纳入大小检查。
            //   先算候选重叠，只有它与本句能一起装下时才用 —— 否则放弃重叠。
            String candidateOverlap = tailOverlap(previous, overlap);
            if (candidateOverlap.length() + sentence.length() <= maxSize) {
                current.append(candidateOverlap);
            }
            current.append(sentence);
        }
        flush(result, current);
        return result;
    }

    /**
     * 供<b>分段聚合</b>类策略使用的重叠入口（{@link ParagraphChunker} 需要它）。
     *
     * <p>为什么把它公开：分段聚合是"逐段累积"的过程，它必须在<b>每次开新块时</b>
     * 主动取上一块末尾作为开头 —— 这个动作发生在策略内部，而不是在
     * {@link #enforceMaxSize} 的循环里。所以策略需要直接访问重叠逻辑。
     *
     * <p>把它保持在 {@link ChunkingSupport} 内（而不是让策略自己实现一份），
     * 是为了保证<b>重叠规则只有一个出处</b>：若两个策略各写一份，
     * 调整重叠策略时必然漏改一处，结果是"两种文档的重叠行为不一致"。
     */
    public static String tailOverlapFor(String previous, int overlap) {
        return tailOverlap(previous, overlap);
    }

    /**
     * 取用于重叠的"上一块末尾"。
     *
     * <p><b>优先在句子边界处截取</b>：如果直接按字符数从末尾截，重叠部分
     * 会以半句话开头；退到最近的句子边界后，重叠部分本身是完整句子，
     * 对下一块的语义更有帮助。
     */
    private static String tailOverlap(String previous, int overlap) {
        if (overlap <= 0 || previous.isEmpty()) {
            return "";
        }
        if (previous.length() <= overlap) {
            return previous;
        }
        String candidate = previous.substring(previous.length() - overlap);
        // 尝试把开头对齐到句子边界（找不到就保留原样，不强行对齐）
        for (int i = 0; i < candidate.length(); i++) {
            if (SENTENCE_ENDINGS.indexOf(candidate.charAt(i)) >= 0 && i + 1 < candidate.length()) {
                return candidate.substring(i + 1);
            }
        }
        return candidate;
    }

    /** 按句末标点切句，标点<b>保留在前一句末尾</b>（切掉标点会丢语义）。 */
    public static List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SENTENCE_ENDINGS.indexOf(c) >= 0) {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /** 无标点的超长文本只能硬切 —— 这是最后手段，正常文档不会走到这里。 */
    private static List<String> hardSplit(String text, int maxSize) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += maxSize) {
            parts.add(text.substring(start, Math.min(text.length(), start + maxSize)));
        }
        return parts;
    }

    private static void flush(List<String> result, StringBuilder buffer) {
        if (buffer.length() > 0) {
            result.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    /**
     * 把过小的<span>尾块</span>并入前一块。
     *
     * <p><b>为什么需要这一步</b>：按段落/句子推进时，最后往往剩下几十个字。
     * 这种"碎片块"有两个坏处：① 它承载的信息太少，向量区分度低，
     * 容易在各种问题下被误命中；② 它挤占 topK 名额（topK=3，一个碎片就占了 1/3）。
     *
     * <p><b>为什么只处理尾块</b>：中间的块不会被单独留下 —— 只有当切分自然结束时
     * 才会产生"最后一块明显偏小"的情况。处理尾块是收益最大、复杂度最低的做法。
     *
     * @param minRatio 低于 {@code maxSize * minRatio} 视为碎片
     */
    public static List<String> mergeTinyTail(List<String> chunks, int maxSize, double minRatio) {
        if (chunks.size() < 2) {
            return chunks;
        }
        int threshold = (int) (maxSize * minRatio);
        String last = chunks.get(chunks.size() - 1);
        if (last.length() >= threshold) {
            return chunks;
        }
        List<String> merged = new ArrayList<>(chunks.subList(0, chunks.size() - 1));
        int lastIndex = merged.size() - 1;
        merged.set(lastIndex, merged.get(lastIndex) + last);
        return merged;
    }

    /** 便捷重载：使用默认的碎片阈值。 */
    public static List<String> mergeTinyTail(List<String> chunks, int maxSize) {
        return mergeTinyTail(chunks, maxSize, MIN_RATIO);
    }

    /** 按段落切分：连续空行视为分段。返回的段落已 trim，空段落被丢弃。 */
    public static List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }
}
