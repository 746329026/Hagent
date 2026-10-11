package com.hagent.app;

import com.hagent.document.IngestOutcome;
import com.hagent.document.IngestResult;

import java.util.List;

/**
 * 批量入库报告。
 *
 * <h2>★ 为什么把"失败清单"放进报告，而不是只抛异常</h2>
 *
 * <p>批量入库面对的是"一整个目录的文档"。其中一份格式不对时，有两种处理方式：
 * <pre>
 *   ① 立即抛异常中止整批  → 98 份正常的文档也一份都没入库
 *   ② 跳过失败的那份继续  → 98 份入库成功，但有 1 份<b>没入进去</b>
 * </pre>
 *
 * <p>本项目选 ②（理由：批量同步场景下"部分成功"远好于"全部失败"），
 * 但 ② 有一个必须堵住的漏洞：<b>如果失败信息只出现在日志里，调用方拿到的
 * 只是一个"完成了"的信号</b> —— 于是"以为同步了 99 份，实际只有 98 份"。
 * 这正是本项目全篇在防的"看起来成功"。
 *
 * <p>所以把失败清单作为报告的<b>结构化字段</b>返回，让调用方<b>能够</b>
 * 据此判断（打日志、告警、或视为错误）。是否把它当错误，是调用方的决定 ——
 * 但数据必须给到它。
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
        return results.stream().filter(IngestResult::written)
                .mapToInt(IngestResult::chunkCount).sum();
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
     * @param reason 失败原因（异常消息）。<b>只用于日志与排查</b>，
     *               不要直接回显给最终用户 —— 它可能包含文件系统路径等内部信息
     */
    public record Failure(String source, String reason) {
    }
}
