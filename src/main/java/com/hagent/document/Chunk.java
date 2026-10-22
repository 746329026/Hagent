package com.hagent.document;

/**
 * 一个<b>切好的块</b> —— 切分阶段的产物，也是入库阶段 embedding 的输入。
 *
 * <p>用独立类型而不直接产出框架的 {@code Document}：切分器只管"文本 + 章节名 + 序号"
 * （能纯粹单测：无 Spring、无数据库、无元数据），算哈希/定密级/拼字段名由入库服务负责，
 * 换向量库时切分器一行不改。
 *
 * @param index   块在<b>整篇文档</b>内的序号，从 0 开始。写入元数据的 {@code chunkIndex}。
 * @param total   该文档一共切成几块（含本块）。写入元数据的 {@code chunkTotal}。
 * @param section 本块所属的章节名。<b>允许为空字符串</b> —— 表示文档没有可直接引用的章节，
 *                拼接出处时由 {@code chunkIndex} 兜底。见 {@link DocumentMetadata} 的空值语义。
 * @param text    本块的正文。这是<b>会被送去 embedding 的内容</b>，长度必须受控
 *                （见 {@link ChunkingStrategy} 关于静默截断的说明）。
 */
public record Chunk(int index, int total, String section, String text) {

    public Chunk {
        if (index < 0) {
            throw new IllegalArgumentException("块序号不能为负：" + index);
        }
        if (total <= 0 || index >= total) {
            throw new IllegalArgumentException("index/total 不自洽：index=" + index + " total=" + total);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("块正文不能为空 —— 空块送去 embedding 会污染向量库");
        }
        section = section == null ? "" : section;
    }

    /** 本块是否有关联的章节名（决定拼出处时用章节名还是"第 N 块"）。 */
    public boolean hasSection() {
        return !section.isBlank();
    }
}
