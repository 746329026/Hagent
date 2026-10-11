package com.hagent.document.chunking;

import com.hagent.document.Chunk;

import java.util.List;

/**
 * 文本切分器 —— 把一份文档的正文切成若干块。
 *
 * <h2>契约（三种实现都必须满足）</h2>
 *
 * <ol>
 *   <li><b>返回的块按原文顺序排列</b>，{@code index} 从 0 连续递增，
 *       {@code total} 等于列表长度 —— 这样"第 N 块"这个定位符永远自洽；</li>
 *   <li><b>不返回空列表</b>（除非输入文本本身为空）—— 若某个策略认为自己不适用，
 *       应返回空列表表示"请调用方降级到别的策略"，而不是返回一个占位块；</li>
 *   <li><b>每块不超过配置的 maxSize</b>，且块之间带 overlap ——
 *       这是 embedding 静默截断的唯一防线（见 {@link ChunkingSupport}）；</li>
 *   <li><b>不丢正文</b>：所有块的文本拼起来应当覆盖原文（允许因 overlap 而重复，
 *       但不允许有内容从未出现在任何块里 —— 那部分内容将永远无法被检索到）。</li>
 * </ol>
 *
 * <h2>为什么第 2 条要"返回空列表"而不是"自己降级"</h2>
 *
 * <p>如果一个切分器内部嵌了降级逻辑（"没有标题就按段落切"），那么
 * <b>降级链就分散在每个实现里</b>，新增一种策略时不知道该插在哪一环。
 * 把"是否适用"与"如何降级"分开后，降级链由调度器统一持有，
 * 顺序清晰且只有一处需要维护。
 */
public interface TextChunker {

    /**
     * 切分。
     *
     * @param text    文档正文（已 trim）
     * @param maxSize 单块最大字符数
     * @param overlap 相邻块的重叠字符数
     * @return 切好的块；<b>空列表表示"本策略不适用于该文本，请降级"</b>
     */
    List<Chunk> chunk(String text, int maxSize, int overlap);

    /** 策略名，用于日志与排查（"这篇文档用了哪个策略切的"）。 */
    String name();
}
