package com.hagent.document.chunking;

import com.hagent.document.Chunk;

import java.util.List;

/**
 * 文本切分器 —— 把一份文档的正文切成若干块。
 *
 * <h2>契约（三种实现都必须满足）</h2>
 *
 * <ol>
 *   <li><b>块按原文顺序排列</b>，{@code index} 从 0 连续递增，{@code total} 等于列表长度；</li>
 *   <li><b>不适用时返回空列表</b>（不返回占位块）—— 空列表 = "请调用方降级到别的策略"；</li>
 *   <li><b>每块不超过 maxSize</b>，块间带 overlap —— 这是 embedding 静默截断的唯一防线
 *       （见 {@link ChunkingSupport}）；</li>
 *   <li><b>不丢正文</b>：允许因 overlap 而重复，但不允许有内容从未出现在任何块里 ——
 *       那部分将永远无法被检索到。</li>
 * </ol>
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
