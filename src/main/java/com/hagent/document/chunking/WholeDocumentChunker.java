package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>整篇一块</b> —— 用于短小且信息整体性的文档（如公司介绍）。
 *
 * <h2>为什么不切反而更好</h2>
 *
 * <p>这类文档的信息是<b>整体性</b>的："我们公司是做什么的"这个问题的答案
 * 可能横跨整篇（成立时间、主营业务、规模、客户）。切开之后：
 * <ul>
 *   <li>每一块都只是答案的一部分，用户问一个问题却需要命中多块才能拼出完整答案；</li>
 *   <li>而 topK 是有限的（本项目为 3），命中不全就等于答不全。</li>
 * </ul>
 * 不切则一次命中全部相关内容 —— 对这种短文档是更优的选择。
 *
 * <h2>★ 但它有一条硬约束，且必须自己检查</h2>
 *
 * <p>"整篇一块"只有在<b>整篇短于 embedding 上限</b>时才成立。
 * 若文档超长还硬塞成一块，embedding 会<b>静默截断</b>，
 * 后半部分<b>永远无法被检索到</b>，而系统没有任何异常。
 *
 * <p>所以本实现在超长时<b>返回空列表</b>（表示"本策略不适用"），
 * 由 {@link ChunkingDispatch} 降级到按段落聚合切分 ——
 * 而不是自己偷偷切，也不是硬塞进一个块。
 *
 * <p>这与 {@link TextChunker} 契约里"空列表 = 请降级"的约定一致。
 */
@Component
public class WholeDocumentChunker implements TextChunker {

    /** 策略名，出现在日志里，便于回答"这篇文档是怎么被切的"。 */
    public static final String NAME = "whole-document";

    /**
     * 单块上限的<b>安全系数</b>：允许整篇作为一块的文本长度不得超过
     * {@code maxSize}。这里刻意不放大 —— 因为 embedding 的上限是"静默失败"，
     * 一旦越界不会有任何提示，所以要严格守在配置值之内。
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
