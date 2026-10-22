package com.hagent.document;

/**
 * 一份文档入库后的<b>处理结论</b>。
 *
 * <p>必须区分"跳过"与"写入"：真的写入 1 篇，与"哈希没变、跳过了 1 篇"，都是期望结果但含义
 * <b>相反</b>；压成一个计数就等于把"幂等生效了"和"什么都没做"变成同一个信号，无法判断系统是否正常。
 *
 * <p><b>没有 {@code FAILED}</b>：单份失败直接抛异常，由调用方决定整批中止还是跳过 ——
 * 失败不是"正常的处理结论"，放进枚举会让调用方把它当成可忽略的返回值。
 */
public enum IngestOutcome {

    /** 库中不存在该来源 → 全量写入。 */
    ADDED,

    /** 库中存在该来源、且文档哈希已变 → 删除旧块后写入新块。 */
    UPDATED,

    /**
     * 库中存在该来源、且文档哈希未变 → <b>跳过</b>，不做任何切分与 embedding。
     *
     * <p>这是幂等的正常路径，不是异常情况。省下的是"整篇重切 + 每块一次 embedding +
     * 每块一次写入"的全部成本。
     */
    SKIPPED_UNCHANGED;

    /**
     * 本次是否<b>没有</b>被跳过（只说明"我们处理过它"，<b>不代表写入了任何东西</b>）。
     *
     * <p>"正文为空 → ADDED、0 块"的文档也返回 {@code true}；
     * 是否真的写入了块看 {@code IngestResult#written()}（{@code chunkCount > 0}）。
     */
    public boolean notSkipped() {
        return this != SKIPPED_UNCHANGED;
    }
}
