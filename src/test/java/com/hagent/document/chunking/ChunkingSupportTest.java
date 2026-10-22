package com.hagent.document.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkingSupport#enforceMaxSize} 的边界条件测试。
 *
 * <p>这里有一个只在特定长度组合下才出现的缺陷：上一块末尾的重叠很长时，追加重叠后没有再次检查大小，
 * 于是可能突破 {@code maxSize}。手写用例很难恰好撞上（取决于"句子长度 / 重叠长度 / 上限"三者的相对关系），
 * 所以用穷举组合把参数空间扫一遍，让缺陷无法靠"运气好没测到"存活。
 *
 * <p>后果很重：块超过 {@code maxSize} 时 embedding 会静默截断 —— 不报错、返回正常维度的向量，
 * 但块尾部的内容永远无法被检索到。
 */
class ChunkingSupportTest {

    /**
     * 生成"混合长度 + 含句末标点"的文本 —— 刻意制造各种句子边界组合。
     *
     * <p>用固定种子（{@code new Random(42)}）而不是随机种子：测试必须可复现 ——
     * "这次过了、下次挂了"的测试比没有测试更糟。
     */
    private static String mixedSentenceText(int sentenceCount) {
        java.util.Random random = new java.util.Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentenceCount; i++) {
            // 句子长度在 1~60 字之间随机，且都以句末标点结尾
            int len = 1 + random.nextInt(60);
            sb.append("内".repeat(len)).append('。');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("★★ 暴力组合：任何 maxSize / overlap 组合下，输出都不得超过 maxSize")
    void neverExceedsMaxSizeUnderAnyCombination() {
        List<String> violations = new java.util.ArrayList<>();

        for (int maxSize : new int[]{40, 60, 80, 100, 120, 200}) {
            for (int overlap : new int[]{0, 5, 10, 20, 30, 45}) {
                if (overlap >= maxSize) {
                    continue;   // 重叠不小于上限本身没有意义
                }
                for (int sentenceCount : new int[]{5, 12, 30, 60}) {
                    String text = mixedSentenceText(sentenceCount);
                    List<String> chunks = ChunkingSupport.enforceMaxSize(text, maxSize, overlap);
                    for (String chunk : chunks) {
                        if (chunk.length() > maxSize) {
                            violations.add(String.format(
                                    "maxSize=%d overlap=%d 句子数=%d → 产出 %d 字的块（超 %d 字）",
                                    maxSize, overlap, sentenceCount, chunk.length(),
                                    chunk.length() - maxSize));
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("以下组合产出了超过上限的块 —— 超出部分会被 embedding 静默截断、永远检索不到：%s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 暴力组合：不得产出空块（空块送去 embedding 是无效数据）")
    void neverProducesBlankChunks() {
        List<String> blanks = new java.util.ArrayList<>();

        for (int maxSize : new int[]{40, 60, 120}) {
            for (int overlap : new int[]{0, 10, 30}) {
                if (overlap >= maxSize) {
                    continue;
                }
                List<String> chunks = ChunkingSupport.enforceMaxSize(
                        mixedSentenceText(40), maxSize, overlap);
                if (chunks.stream().anyMatch(String::isBlank)) {
                    blanks.add("maxSize=" + maxSize + " overlap=" + overlap);
                }
            }
        }

        assertThat(blanks).as("这些组合下出现了空块：%s", blanks).isEmpty();
    }

    @Test
    @DisplayName("★ 暴力组合：不得丢内容 —— 原文每个句子都要出现在某个块里")
    void neverLosesSentences() {
        List<String> lost = new java.util.ArrayList();

        for (int maxSize : new int[]{40, 60, 120}) {
            for (int overlap : new int[]{0, 10, 30}) {
                if (overlap >= maxSize) {
                    continue;
                }
                String text = mixedSentenceText(30);
                List<String> chunks = ChunkingSupport.enforceMaxSize(text, maxSize, overlap);
                String joined = String.join("", chunks);

                // 逐句检查：原文的每一句（含标点）都必须能在拼接结果里找到
                for (String sentence : ChunkingSupport.splitIntoSentences(text)) {
                    if (!joined.contains(sentence)) {
                        lost.add(String.format("maxSize=%d overlap=%d → 丢失句子（%d 字）",
                                maxSize, overlap, sentence.length()));
                        break;
                    }
                }
            }
        }

        assertThat(lost)
                .as("以下组合丢失了原文内容 —— 丢失的部分永远无法被检索到：%s", lost)
                .isEmpty();
    }

    @Test
    @DisplayName("短文本直接返回自身，不做任何切分")
    void shortTextReturnedAsIs() {
        List<String> chunks = ChunkingSupport.enforceMaxSize("短文本。", 100, 20);

        assertThat(chunks).containsExactly("短文本。");
    }

    @Test
    @DisplayName("★★ 段落切分器同样不得超限（它有自己的拼接逻辑，与 enforceMaxSize 独立）")
    void paragraphChunkerNeverExceedsMaxSize() {
        ParagraphChunker chunker = new ParagraphChunker();
        List<String> violations = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(7);

        for (int maxSize : new int[]{40, 60, 80, 120}) {
            for (int overlap : new int[]{0, 10, 20, 30}) {
                if (overlap >= maxSize) {
                    continue;
                }
                // 构造段落长度差异很大的文本：既有正常段落，也有超长段落（触发句子细分路径）
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 25; i++) {
                    int len = 5 + random.nextInt(90);
                    sb.append("段".repeat(len)).append("。\n\n");
                }
                List<com.hagent.document.Chunk> chunks =
                        chunker.chunk(sb.toString(), maxSize, overlap);
                for (com.hagent.document.Chunk chunk : chunks) {
                    if (chunk.text().length() > maxSize) {
                        violations.add(String.format("maxSize=%d overlap=%d → 产出 %d 字的块（超 %d）",
                                maxSize, overlap, chunk.text().length(),
                                chunk.text().length() - maxSize));
                    }
                }
            }
        }

        assertThat(violations)
                .as("段落切分器产出了超限块 —— 超出部分会被 embedding 静默截断：%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 标题切分器同样不得超限")
    void headingChunkerNeverExceedsMaxSize() {
        HeadingChunker chunker = new HeadingChunker();
        List<String> violations = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(11);

        for (int maxSize : new int[]{40, 60, 100}) {
            for (int overlap : new int[]{0, 10, 30}) {
                if (overlap >= maxSize) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (int s = 0; s < 10; s++) {
                    sb.append("## 章节").append(s).append("\n\n");
                    for (int p = 0; p < 3; p++) {
                        sb.append("内".repeat(5 + random.nextInt(60))).append("。\n\n");
                    }
                }
                List<com.hagent.document.Chunk> chunks =
                        chunker.chunk(sb.toString(), maxSize, overlap);
                for (com.hagent.document.Chunk chunk : chunks) {
                    if (chunk.text().length() > maxSize) {
                        violations.add(String.format("maxSize=%d overlap=%d → 产出 %d 字的块（超 %d）",
                                maxSize, overlap, chunk.text().length(),
                                chunk.text().length() - maxSize));
                    }
                }
            }
        }

        assertThat(violations)
                .as("标题切分器产出了超限块：%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("空输入返回空列表（不抛异常、不产生空块）")
    void blankInputReturnsEmptyList() {
        assertThat(ChunkingSupport.enforceMaxSize(null, 100, 20)).isEmpty();
        assertThat(ChunkingSupport.enforceMaxSize("", 100, 20)).isEmpty();
        assertThat(ChunkingSupport.enforceMaxSize("   ", 100, 20)).isEmpty();
    }
}
