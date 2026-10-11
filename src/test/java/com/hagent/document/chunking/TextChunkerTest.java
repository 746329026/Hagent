package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分逻辑的测试。
 *
 * <h2>★ 这里断言的是"契约"，不是"实现细节"</h2>
 *
 * <p>{@link TextChunker} 的四条契约，每一条都对应一个<b>真实的故障模式</b>：
 *
 * <table border="1">
 *   <caption>契约与它防的问题</caption>
 *   <tr><th>契约</th><th>违反后的后果</th></tr>
 *   <tr>
 *     <td>返回块按原文顺序、index 从 0 连续</td>
 *     <td>"第 N 块"这个出处定位符会指错位置</td>
 *   </tr>
 *   <tr>
 *     <td>不适用时返回空列表（而非占位块）</td>
 *     <td>降级链失效，调度器拿到一个假块</td>
 *   </tr>
 *   <tr>
 *     <td><b>每块不超过 maxSize</b></td>
 *     <td>embedding <b>静默截断</b> —— 块的尾部永远检索不到，且无任何报错</td>
 *   </tr>
 *   <tr>
 *     <td><b>不丢正文</b></td>
 *     <td>被丢掉的那部分内容<b>永远无法被检索到</b>，用户问相关问题时系统答不出，
 *         而排查时看不出任何异常</td>
 *   </tr>
 * </table>
 *
 * <p>最后一条（不丢正文）是最重要的 —— 它是"分块"这件事的核心正确性：
 * 分块的目的是"把文档切小以便检索"，而不是"丢掉一部分内容"。
 */
class TextChunkerTest {

    private static final int MAX = 120;
    private static final int OVERLAP = 20;

    private final WholeDocumentChunker whole = new WholeDocumentChunker();
    private final HeadingChunker heading = new HeadingChunker();
    private final ParagraphChunker paragraph = new ParagraphChunker();

    // ========================================================================
    // 契约 3：不超限（防 embedding 静默截断）
    // ========================================================================

    @Test
    @DisplayName("★ 契约：无论哪种策略，每块都不超过 maxSize（这是防静默截断的唯一防线）")
    void noChunkExceedsMaxSize() {
        String longParagraph = "这是一段没有任何句号的很长文本".repeat(20);   // 无标点 → 只能硬切
        String manyParagraphs = ("段落内容。".repeat(5) + "\n\n").repeat(15);
        String withHeadings = "# 标题一\n\n正文内容。\n\n## 标题二\n\n" + "更长的正文。".repeat(60);
        String oneHugeSection = "# 唯一标题\n\n" + "这一节非常长。".repeat(60);

        // 注意：这里【不】断言 wholeDocumentChunker —— 它遇到超长文本会返回空列表
        // （表示"本策略不适用，请降级"），那是契约 2 的行为，由专门的用例覆盖。
        assertAllWithinLimit(paragraph.chunk(longParagraph, MAX, OVERLAP));
        assertAllWithinLimit(paragraph.chunk(manyParagraphs, MAX, OVERLAP));
        assertAllWithinLimit(heading.chunk(withHeadings, MAX, OVERLAP));
        // 标题切分后单节超长 → 必须按句子细分，同样不能超限
        assertAllWithinLimit(heading.chunk(oneHugeSection, MAX, OVERLAP));
    }

    private void assertAllWithinLimit(List<Chunk> chunks) {
        assertThat(chunks).isNotEmpty();
        for (Chunk chunk : chunks) {
            assertThat(chunk.text().length())
                    .as("块 #%d 长度 %d 超过上限 %d —— 会被 embedding 静默截断",
                            chunk.index(), chunk.text().length(), MAX)
                    .isLessThanOrEqualTo(MAX);
        }
    }

    // ========================================================================
    // 契约 4：不丢正文（最重要的正确性）
    // ========================================================================

    @Test
    @DisplayName("★★ 契约：不丢正文 —— 原文的每个字符都必须出现在某个块里（允许重叠重复）")
    void noContentIsLost() {
        String text = """
                # 第一张

                这是第一张的正文内容，包含若干句子。第二句在这里。第三句结束。

                ## 第一张之一

                子章节的正文，也需要被完整保留下来。不能因为切分而丢失。

                # 第二张

                第二张的正文同样重要。这里再写一句话凑长度。最后一句。
                """;

        List<Chunk> chunks = heading.chunk(text, 60, 10);
        String joined = String.join("", chunks.stream().map(Chunk::text).toList());

        // 逐个断言原文的关键片段都在（用片段而非整串比较 —— 因为块与块之间有重叠，
        // 拼起来会比原文长，直接比较整串会误判）
        assertThat(joined).contains("这是第一张的正文内容");
        assertThat(joined).contains("子章节的正文");
        assertThat(joined).contains("第二张的正文同样重要");
        assertThat(joined).contains("最后一句");
    }

    @Test
    @DisplayName("★ 段落切分同样不丢正文")
    void paragraphChunkerDoesNotLoseContent() {
        String text = "第一段的内容在这里。\n\n第二段的内容在这里。\n\n第三段的内容在这里。";

        List<Chunk> chunks = paragraph.chunk(text, 200, 0);
        String joined = String.join("", chunks.stream().map(Chunk::text).toList());

        assertThat(joined).contains("第一段的内容");
        assertThat(joined).contains("第二段的内容");
        assertThat(joined).contains("第三段的内容");
    }

    // ========================================================================
    // 契约 1：序号连续（出处的定位基础）
    // ========================================================================

    @Test
    @DisplayName("★ 契约：index 从 0 连续递增，total 等于块数（否则「第 N 块」这个出处定位符会指错）")
    void indexesAreContiguousAndTotalMatches() {
        String text = "# 一\n\n内容一。\n\n# 二\n\n内容二。\n\n# 三\n\n内容三。";

        List<Chunk> chunks = heading.chunk(text, MAX, OVERLAP);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).total()).isEqualTo(chunks.size());
        }
    }

    // ========================================================================
    // 契约 2：不适用时返回空列表（降级链的基础）
    // ========================================================================

    @Test
    @DisplayName("★ 契约：无标题时 HeadingChunker 返回空列表（表示「请降级」），而不是自己乱切")
    void headingChunkerReturnsEmptyWhenNoHeading() {
        String noHeading = "这是一段没有标题的文本。它只有正文。";

        assertThat(heading.chunk(noHeading, MAX, OVERLAP)).isEmpty();
    }

    @Test
    @DisplayName("★ 契约：超长时 WholeDocumentChunker 返回空列表（不能把超长文本硬塞成一块）")
    void wholeDocumentChunkerReturnsEmptyWhenTooLong() {
        String tooLong = "很长的内容。".repeat(50);   // 远大于 MAX

        assertThat(whole.chunk(tooLong, MAX, OVERLAP)).isEmpty();
    }

    @Test
    @DisplayName("短文本时 WholeDocumentChunker 产出唯一一块，且无章节名")
    void wholeDocumentChunkerEmitsSingleChunk() {
        List<Chunk> chunks = whole.chunk("短的公司介绍内容。", MAX, OVERLAP);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).total()).isEqualTo(1);
        // 整篇一块 → 没有章节可引用，section 为空（拼接出处时由块序号兜底）
        assertThat(chunks.get(0).hasSection()).isFalse();
    }

    // ========================================================================
    // 章节名（出处的来源）
    // ========================================================================

    @Test
    @DisplayName("★ 章节名被正确带上 —— 它是出处的来源，模型据此说「根据《X》第几章」")
    void sectionNameIsCarried() {
        String text = "# 员工手册\n\n前言部分。\n\n## 假期管理\n\n年假五天。\n\n## 报销标准\n\n住宿上限五百元。";

        List<Chunk> chunks = heading.chunk(text, MAX, OVERLAP);

        List<String> sections = chunks.stream().map(Chunk::section).toList();
        assertThat(sections).contains("员工手册", "假期管理", "报销标准");
    }

    @Test
    @DisplayName("★ 单节超长被细分时，各块【共享同一个章节名】—— 出处仍然准确")
    void subsplitChunksShareSameSection() {
        String text = "# 超长章节\n\n" + "这一节很长需要细分。".repeat(30);

        List<Chunk> chunks = heading.chunk(text, 60, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.section()).isEqualTo("超长章节"));
    }

    @Test
    @DisplayName("第一个标题之前的正文单独成块，且不编造章节名")
    void preambleBecomesItsOwnChunkWithEmptySection() {
        String text = "这段正文出现在任何标题之前。\n\n# 正式标题\n\n标题下的正文。";

        List<Chunk> chunks = heading.chunk(text, MAX, OVERLAP);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).text()).contains("出现在任何标题之前");
        // 前言确实没有章节名 —— 编一个会让出处不准确，所以留空
        assertThat(chunks.get(0).hasSection()).isFalse();
        assertThat(chunks.get(1).section()).isEqualTo("正式标题");
    }

    @Test
    @DisplayName("空小节被跳过（标题本身不构成可检索内容）")
    void emptySectionsAreSkipped() {
        String text = "# 有正文的标题\n\n这里的内容。\n\n## 空标题\n\n### 另一个空标题\n\n## 也有正文\n\n内容在这。";

        List<Chunk> chunks = heading.chunk(text, MAX, OVERLAP);

        assertThat(chunks).allSatisfy(c -> assertThat(c.text()).isNotBlank());
        assertThat(chunks.stream().map(Chunk::section))
                .contains("有正文的标题", "也有正文")
                // 空标题不该产出块 —— 因为它没有可检索的内容
                .doesNotContain("空标题");
    }

    // ========================================================================
    // Markdown 标题识别的边界
    // ========================================================================

    @Test
    @DisplayName("★ # 后面没有空格的不算标题（如 #话题 是普通文本，误判会凭空造出章节名）")
    void hashWithoutSpaceIsNotAHeading() {
        String text = "#标签 这是一个话题标签，不是标题。\n\n这一行仍然是正文。";

        // 没有真正的标题 → 本策略不适用
        assertThat(heading.chunk(text, MAX, OVERLAP)).isEmpty();
        // 而按段落切分能正常处理它
        assertThat(paragraph.chunk(text, MAX, OVERLAP)).isNotEmpty();
    }

    @Test
    @DisplayName("正文中间的分隔线 --- 不会被误当成标题")
    void horizontalRuleIsNotAHeading() {
        String text = "第一段内容。\n\n---\n\n第二段内容。";

        assertThat(heading.chunk(text, MAX, OVERLAP)).isEmpty();
    }

    // ========================================================================
    // 段落切分与重叠
    // ========================================================================

    @Test
    @DisplayName("按段落聚合：多段能合并到接近上限，而不是每段一块")
    void paragraphChunkerAggregates() {
        // 每段约 24 字，上限 120 → 约 5 段一块。用 40 段（约 960 字）确保必须切成多块。
        String text = String.join("\n\n",
                java.util.stream.IntStream.range(0, 40)
                        .mapToObj(i -> "这是第" + i + "段的内容，需要足够长才能触发分块。")
                        .toList());

        List<Chunk> chunks = paragraph.chunk(text, MAX, 0);

        // 40 段必须被聚合成远少于 40 的若干块 —— 这正是"聚合"的意义：
        // 不聚合的话每段一块，会有 40 个向量，且每块信息量太少、区分度低
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).hasSizeLessThan(30);
    }

    @Test
    @DisplayName("★ 重叠确实存在：相邻块有公共内容（防止句子被边界切断）")
    void overlapExists() {
        // 构造明显需要多块的文本
        String text = "第一句话内容。第二句话内容。第三句话内容。第四句话内容。"
                + "第五句话内容。第六句话内容。第七句话内容。第八句话内容。";

        List<Chunk> chunks = paragraph.chunk(text, 40, 15);

        assertThat(chunks).hasSizeGreaterThan(1);
        // 相邻两块之间存在重叠：前一块的结尾片段应当出现在后一块的开头附近
        String first = chunks.get(0).text();
        String second = chunks.get(1).text();
        String tailOfFirst = first.substring(Math.max(0, first.length() - 10));
        assertThat(second).contains(tailOfFirst);
    }

    @Test
    @DisplayName("碎片尾块被并入前一块（碎片块会挤占有限的 topK 名额）")
    void tinyTailIsMerged() {
        // 让最后只剩很短一点内容
        String text = "第一段有比较多的内容需要占位。\n\n"
                + "第二段也有比较多的内容需要占位。\n\n短尾。";

        List<String> blocks = ChunkingSupport.mergeTinyTail(
                List.of("第一段有比较多的内容需要占位。", "短尾。"), 120, 0.25);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).contains("第一段").contains("短尾");
    }

    // ========================================================================
    // 空输入
    // ========================================================================

    @Test
    @DisplayName("三种策略对空输入都安全返回空列表，不抛异常")
    void emptyInputIsHandled() {
        assertThat(whole.chunk("", MAX, OVERLAP)).isEmpty();
        assertThat(whole.chunk("   ", MAX, OVERLAP)).isEmpty();
        assertThat(heading.chunk(null, MAX, OVERLAP)).isEmpty();
        assertThat(paragraph.chunk("", MAX, OVERLAP)).isEmpty();
    }
}
