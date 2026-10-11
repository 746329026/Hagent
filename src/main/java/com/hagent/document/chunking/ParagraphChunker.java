package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>按段落聚合切分</b> —— 用于没有标题但有空行分段的文本。
 *
 * <h2>为什么以段落为单位</h2>
 *
 * <p>一大块没有标题的文本通常<b>仍然有段落</b>（连续换行分隔）。
 * 段落是语义自然的边界（作者换段就是在换意思），而且它的粒度恰到好处：
 * <ul>
 *   <li>比句子大 —— 单句信息太少，向量区分度低，容易在各种问题下被误命中；</li>
 *   <li>比整篇小 —— 整篇的向量是全文主题的"平均值"，跟任何具体问题都不够近；</li>
 *   <li>不依赖文档有标题 —— 这是它相对 {@link HeadingChunker} 的唯一优势。</li>
 * </ul>
 *
 * <h2>算法：贪心聚合</h2>
 *
 * <pre>
 *   逐段累积，直到"再加一段就超限"为止 → 输出一块，然后开始下一块
 * </pre>
 *
 * <p><b>为什么不把段落切开</b>：切段落会产生"上一块以半个意思结尾"的残缺块。
 * 所以只有当<b>单段本身就超限</b>时才退到句子级细分 —— 这是最后手段。
 *
 * <h2>两级降级（本类内部）</h2>
 *
 * <pre>
 *   有段落  → 按段落聚合并限长
 *   无段落  → 按句子滑动切分    ← 连空行都没有的文本（如从 PDF 提取的单块文字）
 * </pre>
 *
 * <p>连句子边界都没有（整篇无标点）时，{@link ChunkingSupport#enforceMaxSize}
 * 内部会做最后的硬切 —— 那时已经无法保住语义，只能保证不超限。
 */
@Component
public class ParagraphChunker implements TextChunker {

    public static final String NAME = "by-paragraph";

    @Override
    public List<Chunk> chunk(String text, int maxSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip();

        List<String> paragraphs = ChunkingSupport.splitIntoParagraphs(normalized);
        List<String> blocks = paragraphs.isEmpty()
                // 连段落都没有 → 直接按句子切
                ? ChunkingSupport.enforceMaxSize(normalized, maxSize, overlap)
                : aggregateParagraphs(paragraphs, maxSize, overlap);

        if (blocks.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            // 无标题文档 → section 留空，拼出处时由块序号兜底（见 DocumentMetadata 空值语义）
            chunks.add(new Chunk(i, blocks.size(), "", blocks.get(i)));
        }
        return chunks;
    }

    /**
     * 贪心聚合段落：尽量多装，但<b>每块严格不超过 maxSize</b>，并带重叠。
     *
     * <h3>★ 这里修过两处会导致超限的缺陷（同一类问题的两个表现）</h3>
     *
     * <ol>
     *   <li><b>"重叠 + 已限长的片段"必然超限</b>：把超长段落按句子细分后，
     *       原写法把"重叠 + 最后一片"拼成一个新块 —— 而最后一片本身已经接近
     *       {@code maxSize}，再叠加重叠就一定越界；</li>
     *   <li><b>大小检查漏算了换行符</b>：判断"这段装得下吗"时用的是
     *       {@code current.length() + paragraph.length()}，但实际拼接时会多插一个
     *       {@code '\n'} 分隔符。当结果恰好等于 {@code maxSize} 时就会多出 1 个字符。</li>
     * </ol>
     *
     * <p>两处的共同后果都是"块超过上限 → embedding 静默截断 → 块尾内容永远检索不到"。
     * 修复的取向是<b>让"每块 ≤ maxSize"成为本方法的结构性保证</b>，
     * 而不是依赖几处判断都恰好写对：
     * <ul>
     *   <li>细分后的片段<b>逐个输出</b>，不再与重叠拼接 —— 放弃那一点重叠，
     *       换取"绝不超限"这个硬保证；</li>
     *   <li>大小判断里把分隔符的 1 个字符算进去；</li>
     *   <li>重叠的追加同样先算后判（与 {@link ChunkingSupport#enforceMaxSize} 一致）。</li>
     * </ul>
     */
    private List<String> aggregateParagraphs(List<String> paragraphs, int maxSize, int overlap) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 单段超限 → 交给 enforceMaxSize 按句子细分，片段【逐个输出】
            // 权衡：放弃与相邻内容的重叠，换取"绝不超限"（见方法注释）
            if (paragraph.length() > maxSize) {
                addIfNotEmpty(blocks, current.toString());
                current.setLength(0);
                // enforceMaxSize 本身已保证每片 ≤ maxSize，直接全部输出
                for (String piece : ChunkingSupport.enforceMaxSize(paragraph, maxSize, overlap)) {
                    addIfNotEmpty(blocks, piece);
                }
                continue;
            }

            // 分隔符的 1 个字符必须计入判断（原写法漏了它，见方法注释第 2 点）
            int separator = current.length() > 0 ? 1 : 0;
            if (current.length() > 0 && current.length() + separator + paragraph.length() > maxSize) {
                String previous = current.toString();
                addIfNotEmpty(blocks, previous);
                current.setLength(0);

                // ★ 重叠先算后判：只有它与本段能一起装下时才用
                String candidateOverlap = ChunkingSupport.tailOverlapFor(previous, overlap);
                if (!candidateOverlap.isBlank()
                        && candidateOverlap.length() + 1 + paragraph.length() <= maxSize) {
                    current.append(candidateOverlap);
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        addIfNotEmpty(blocks, current.toString());

        return ChunkingSupport.mergeTinyTail(blocks, maxSize);
    }

    private static void addIfNotEmpty(List<String> blocks, String text) {
        if (text != null && !text.isBlank()) {
            blocks.add(text.strip());
        }
    }

    @Override
    public String name() {
        return NAME;
    }
}
