package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>按段落聚合切分</b> —— 用于没有标题但有空行分段的文本。
 *
 * <p>一大块没有标题的文本通常仍有段落（连续换行分隔），段落是语义自然的边界
 * （作者换段就是在换意思），粒度也恰到好处：比句子大（单句信息太少、区分度低，容易误命中），
 * 比整篇小（整篇向量是全文主题的"平均值"，跟任何具体问题都不够近），
 * 且<b>不依赖文档有标题</b> —— 这是它相对 {@link HeadingChunker} 的唯一优势。
 *
 * <p><b>算法</b>：逐段贪心累积，直到"再加一段就超限"为止。不切段落 ——
 * 切段落会产生"上一块以半个意思结尾"的残缺块，所以只有<b>单段本身超限</b>时才退到句子级细分。
 *
 * <p><b>两级降级</b>：有段落 → 按段落聚合并限长；无段落（如从 PDF 提取的单块文字）→
 * 按句子滑动切分。连句子边界都没有时，由 {@link ChunkingSupport#enforceMaxSize} 做最后的硬切 ——
 * 那时已无法保住语义，只能保证不超限。
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
     * <p>这里有两条必须守住的约束（都曾导致超限，后果同为"embedding 静默截断、块尾永远检索不到"）：
     * <ul>
     *   <li>细分后的片段<b>逐个输出</b>，不再与重叠拼接 —— 最后一片本身已接近 {@code maxSize}，
     *       再叠加重叠必然越界；放弃那一点重叠换取"绝不超限"这个硬保证；</li>
     *   <li>大小判断要把拼接时插入的 {@code '\n'} 算进去，否则结果恰好等于 {@code maxSize} 时会多 1 个字符。</li>
     * </ul>
     *
     * <p>取向是让"每块 ≤ maxSize"成为本方法的结构性保证，而不是依赖几处判断都恰好写对；
     * 重叠的追加同样先算后判（与 {@link ChunkingSupport#enforceMaxSize} 一致）。
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

            // 分隔符的 1 个字符必须计入判断（漏掉它会多出 1 个字符）
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
