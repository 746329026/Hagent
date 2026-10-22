package com.hagent.document.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分实现的<b>共用工具</b> —— 语义边界优先，大小上限兜底。
 *
 * <p>切分有两个冲突的目标：按语义边界切（标题/段落/句子），让每块向量"含义集中"、检索才准；
 * 且不超过大小上限，否则 embedding 会<b>静默截断</b>、后半部分永远检索不到。
 * 天真做法是先按语义切、超限再硬切 —— 那会把句子从中间切断，留下"语义残缺块"。
 * 所以本类的做法是<b>超限时退到更小的语义单位继续切</b>（段落 → 句子），
 * 硬切只在"连句子都没有"的极端情况下发生。
 *
 * <p>overlap 的作用是防止语义被边界切断：一句话横跨两块时，没有重叠则第二块以半个句子开头、
 * 其向量代表不完整的含义。实现方式是"把上一块末尾若干字符接到下一块开头"，并
 * <b>优先在句子边界处取</b>，这样重叠部分本身也是完整句子。
 *
 * <p>参数取值（400 汉字 / overlap 60，由配置提供）的依据：embedding 实测上限约 1000 汉字，
 * 400 只用了 40%、留足余量；块越大块内主题越杂，向量越趋近"多主题的平均值"而降低区分度；
 * 400 字 × topK 3 ≈ 1200 token，占 5000 会话预算的 24%，留得出历史与系统提示词的空间。
 */
public final class ChunkingSupport {

    /** 句末标点 —— 中文与英文的常见句末符号。 */
    private static final String SENTENCE_ENDINGS = "。！？!?；;…";

    /** 最小块大小占上限的比例：合并后仍小于上限 25% 的尾块，尝试并入前一块。 */
    private static final double MIN_RATIO = 0.25;

    private ChunkingSupport() {
    }

    // 大小约束

    /**
     * 把一段文本拆成<b>不超过 maxSize</b> 的若干片段，并在片段之间加重叠。
     *
     * <p>"大小上限兜底"的唯一实现 —— 所有切分策略得到语义单元后都调用它，
     * 因此不会出现"某个策略忘了限长"（那会导致 embedding 静默截断）。
     * 拆解顺序：<b>句子边界 → 硬切</b>，只有单个句子本身就超限时才硬切。
     *
     * <p><b>重叠与上限的冲突</b>：{@link #tailOverlap} 会退到句子边界，其返回值<b>可能远大于
     * overlap 参数</b>；若"追加重叠"不参与大小检查，新块就会突破上限，且幅度<b>随 overlap
     * 增大而系统性恶化</b>（实测 maxSize=80 / overlap=45 产出 102 字），后果是块尾内容被
     * embedding <b>静默截断</b>、永远检索不到。所以重叠的追加也纳入大小决策：
     * 装不下就<b>放弃这一块的重叠</b> —— 重叠是锦上添花，超限是正确性问题。
     *
     * <p>由此得到一个性质：<b>输出是否超限不再取决于 overlap 的取值</b>，
     * 由 {@code ChunkingSupportTest} 的暴力组合测试（穷举 maxSize × overlap × 文本长度）守卫。
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
     * <p>分段聚合是"逐段累积"的过程，必须在<b>每次开新块时</b>主动取上一块末尾作为开头，
     * 这个动作发生在策略内部，而不在 {@link #enforceMaxSize} 的循环里。
     * 保持在本类内（而不是让策略各写一份），是为了让<b>重叠规则只有一个出处</b> ——
     * 否则调整重叠策略时必然漏改一处，两种文档的重叠行为就不一致。
     */
    public static String tailOverlapFor(String previous, int overlap) {
        return tailOverlap(previous, overlap);
    }

    /**
     * 取用于重叠的"上一块末尾"，<b>优先在句子边界处截取</b>：
     * 直接按字符数从末尾截会让重叠以半句话开头；退到最近的句子边界后，
     * 重叠部分本身是完整句子，对下一块的语义更有帮助。
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
     * <p>按段落/句子推进时最后往往剩几十个字，"碎片块"信息太少、向量区分度低，
     * 容易在各种问题下被误命中，还挤占 topK 名额（topK=3，一个碎片就占 1/3）。
     *
     * <p>只处理尾块：中间的块不会被单独留下，只有切分自然结束时才会出现"最后一块明显偏小"；
     * 处理尾块收益最大、复杂度最低。
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
