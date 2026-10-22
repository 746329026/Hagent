package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>整篇一块</b> —— 用于短小且信息整体性的文档（如公司介绍）。
 *
 * <p>这类文档的答案常横跨整篇（成立时间、主营业务、规模、客户），切开后每块只是答案的一部分，
 * 而 topK 有限（本项目为 3），命中不全就等于答不全；不切则一次命中全部相关内容。
 *
 * <p><b>硬约束：只有整篇短于 embedding 上限时"整篇一块"才成立。</b>超长还硬塞成一块会被
 * <b>静默截断</b>，后半部分永远无法被检索到而系统无任何异常。所以超长时本实现<b>返回空列表</b>
 * （= 本策略不适用），由 {@link ChunkingDispatch} 降级到按段落聚合切分 ——
 * 与 {@link TextChunker} 契约里"空列表 = 请降级"一致。
 */
@Component
public class WholeDocumentChunker implements TextChunker {

    /** 策略名，出现在日志里，便于回答"这篇文档是怎么被切的"。 */
    public static final String NAME = "whole-document";

    /**
     * 严格以 {@code maxSize} 为界、不做放大：embedding 超限是<b>静默失败</b>，
     * 越界不会有任何提示，所以必须守在配置值之内。
     */
    @Override
    public List<Chunk> chunk(String text, int maxSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.length() > maxSize) {
            // 本策略不适用 —— 交给调度器降级，而不是自作主张地切
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>(1);
        chunks.add(new Chunk(0, 1, "", trimmed));
        return chunks;
    }

    @Override
    public String name() {
        return NAME;
    }
}
