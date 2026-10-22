package com.hagent.app;

import com.hagent.document.IngestOutcome;
import com.hagent.document.IngestResult;

import java.util.List;

/**
 * 批量入库报告 —— <b>失败清单是结构化字段，不只是日志</b>。
 *
 * <p>批量入库选"跳过失败的继续"（部分成功远好于全部失败），但有一个必须堵住的漏洞：失败信息
 * 只进日志时，调用方拿到的只是一个"完成了"的信号 —— "以为同步了 99 份，实际只有 98 份"。
 * 所以失败清单随报告返回，让调用方<b>能够</b>判断（打日志、告警或视为错误）；是否当错误由它决定。
 *
 * @param results  每份文档的处理结论（含跳过的）
 * @param failures 未能入库的文档及原因
 */
public record BatchIngestReport(List<IngestResult> results,
                                List<Failure> failures) {

    public BatchIngestReport {
        results = results == null ? List.of() : List.copyOf(results);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** 新增的文档数。 */
    public long addedCount() {
        return countOf(IngestOutcome.ADDED);
    }

    /** 更新的文档数。 */
    public long updatedCount() {
        return countOf(IngestOutcome.UPDATED);
    }

    /** 内容未变、被跳过的文档数。 */
    public long skippedCount() {
        return countOf(IngestOutcome.SKIPPED_UNCHANGED);
    }

    /** 本次实际写入的块总数。 */
    public int writtenChunkCount() {
        // 不需要 filter(IngestResult::written)：被跳过 / 正文为空的文档 chunkCount 本来就是 0
        return results.stream().mapToInt(IngestResult::chunkCount).sum();
    }

    /** 是否有任何文档失败。 */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    private long countOf(IngestOutcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    /**
     * 单个失败项。
     *
     * @param source 出问题的来源路径 —— 必须带上，否则调用方无法定位是哪一份
     * @param reason 失败原因（异常消息）。<b>只用于日志与排查</b>，可能含内部路径，不要回显给最终用户
     */
    public record Failure(String source, String reason) {
    }
}
