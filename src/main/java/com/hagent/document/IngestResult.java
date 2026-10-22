package com.hagent.document;

/**
 * 单份文档的入库结果。
 *
 * @param source      来源路径（文档身份）
 * @param outcome     处理结论 —— 新增 / 更新 / 跳过。见 {@link IngestOutcome}
 * @param chunkCount  写入（或跳过的已有）块数。<b>跳过时为 0</b> —— 跳过时没有切分，
 *                    报"已有 N 块"会让调用方以为读了 N 块。
 * @param docHash     本文档的内容指纹，用于跨次运行的比对与排查
 */
public record IngestResult(String source, IngestOutcome outcome, int chunkCount, String docHash) {

    /** 跳过（内容未变）。语义化构造，避免调用方手写 {@code new IngestResult(..., 0, ...)} 时把顺序写错。 */
    public static IngestResult skipped(String source, String docHash) {
        return new IngestResult(source, IngestOutcome.SKIPPED_UNCHANGED, 0, docHash);
    }

    /**
     * 是否<b>真的</b>写入了向量库 —— 判据是"写入了块"，不是"没被跳过"。
     *
     * <p>不能用 {@code outcome.notSkipped()} 代替："正文为空 → ADDED、0 块"的文档
     * 会被报成"已写入"，谁按它判断"这次有没有东西进库"就会错。
     */
    public boolean written() {
        return chunkCount > 0;
    }
}
