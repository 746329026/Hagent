package com.hagent.document;

/**
 * 一个<b>切好的块</b> —— 切分阶段的产物，也是入库阶段 embedding 的输入。
 *
 * <p><b>为什么是独立的类型而不是直接复用框架的 {@code Document}</b>：
 * {@code Document} 是"向量库的写入单元"，它身上带着元数据 Map、可选 ID、媒体等
 * 一堆与"文本切分"无关的东西。如果切分器直接产出 {@code Document}：
 * <ul>
 *   <li>切分器就要负责算哈希、定密级、拼字段名 —— 职责从"切文本"膨胀成"包元数据"；</li>
 *   <li>单元测试切分时不得不去满足 {@code Document} 的所有约束，
 *       而真正要断言的东西只是"文本和章节对不对"。</li>
 * </ul>
 *
 * <p>所以切分器只产出本类型（<b>文本 + 章节名 + 序号</b>），
 * 由入库服务负责把它包装成 {@code Document}。这样：
 * <ul>
 *   <li>切分逻辑可以<b>纯粹地单测</b>（无 Spring、无数据库、无元数据）；</li>
 *   <li>将来换向量库（如从 pgvector 换成 MySQL 方案）时，切分器一行都不用改。</li>
 * </ul>
 *
 * @param index   块在<b>整篇文档</b>内的序号，从 0 开始。写入元数据的 {@code chunkIndex}。
 * @param total   该文档一共切成几块（含本块）。写入元数据的 {@code chunkTotal}。
 * @param section 本块所属的章节名。<b>允许为空字符串</b> —— 表示文档没有可直接引用的章节，
 *                拼接出处时由 {@code chunkIndex} 兜底。见 {@link DocumentMetadata} 的空值语义。
 * @param text    本块的正文。<b>这是会被送去 embedding 的内容</b>，
 *                因此长度必须受控（见 {@link ChunkingStrategy} 关于静默截断的说明）。
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
