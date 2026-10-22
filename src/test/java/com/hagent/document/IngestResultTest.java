package com.hagent.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IngestResult} / {@link IngestOutcome} 的语义测试 —— 防的是"契约说谎"，不是数值错误。
 *
 * <p>统计数字碰巧对（0 块累加仍是 0）而语义为假时，任何按 {@code written()} 判断
 * "这次有没有东西进库"的调用方都会得到错误答案。所以本类钉住两个不同的概念：
 * {@code outcome().notSkipped()} = "我们处理过它"（含正文为空），
 * {@code written()} = "真的写入了块"（{@code chunkCount > 0}）。
 */
class IngestResultTest {

    @Test
    @DisplayName("★ 正文为空：处理过 但【没有】写入任何块")
    void emptyDocumentIsProcessedButNotWritten() {
        IngestResult result = new IngestResult("只有frontmatter.md", IngestOutcome.ADDED, 0, "hash");

        assertThat(result.written())
                .as("0 块 = 什么都没写进向量库。旧实现这里返回 true，是契约说谎")
                .isFalse();
        assertThat(result.outcome().notSkipped())
                .as("它确实被处理过（不是跳过）—— 两个概念必须能分开表达")
                .isTrue();
    }

    @Test
    @DisplayName("跳过：既没写入，也不算写入")
    void skippedIsNotWritten() {
        IngestResult result = IngestResult.skipped("员工手册.md", "hash");

        assertThat(result.written()).isFalse();
        assertThat(result.outcome().notSkipped()).isFalse();
    }

    @Test
    @DisplayName("正常写入 N 块：written() 为 true")
    void writtenWhenBlocksExist() {
        assertThat(new IngestResult("a.md", IngestOutcome.ADDED, 3, "h").written()).isTrue();
        assertThat(new IngestResult("a.md", IngestOutcome.UPDATED, 1, "h").written()).isTrue();
    }
}
