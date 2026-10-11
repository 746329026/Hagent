package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import com.hagent.document.ChunkingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切分调度器 —— <b>降级链的唯一持有者</b>。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>同一种文档在现实中形态各异：一份"规章制度"可能结构规整（全是
 * {@code ##} 标题），也可能是一大段没有标题的纯文本。若让每种策略
 * 自己处理"我的前提不满足"的情况，降级链就会分散在多个实现里 ——
 * 新增策略时不知道该插在哪一环，且容易出现循环降级。
 *
 * <p>所以把"<b>是否适用</b>"（各策略自己判断，不适用就返回空列表）
 * 与"<b>如何降级</b>"（本类统一持有）分开：
 *
 * <pre>
 *   WHOLE_DOCUMENT → 整篇一块
 *        ↓ 超长时不适用
 *   BY_HEADING    → 有标题就按标题切
 *        ↓ 无标题时不适用
 *   BY_PARAGRAPH  → 按段落聚合（内部还可降到句子级）
 * </pre>
 *
 * <h2>★ 降级不是静默的 —— 会记日志</h2>
 *
 * <p>"降级发生了"这件事必须<b>可见</b>。理由：降级意味着<b>实际使用的切分方式
 * 与文档种类声明的不同</b>。例如一份 {@code POLICY} 类文档本该按标题切，
 * 但因为作者忘了写标题而降级成了按段落切 —— 它的每块都没有章节名，
 * 将来拼出处时只能说"第 N 块"。
 *
 * <p>这类情况若不记录，运维与开发都无从知道"为什么这篇文档的检索效果不好"。
 * 所以每次降级都打一条 WARN，写明"哪个策略不适用、降级到了哪个"。
 *
 * <p>这与本项目"兜底处理器要报出缺口"的立场一致：
 * <b>把静默的行为变化变成一条可读的记录</b>。
 */
@Slf4j
@Component
public class ChunkingDispatch {

    private final WholeDocumentChunker wholeDocumentChunker;
    private final HeadingChunker headingChunker;
    private final ParagraphChunker paragraphChunker;

    public ChunkingDispatch(WholeDocumentChunker wholeDocumentChunker,
                            HeadingChunker headingChunker,
                            ParagraphChunker paragraphChunker) {
        this.wholeDocumentChunker = wholeDocumentChunker;
        this.headingChunker = headingChunker;
        this.paragraphChunker = paragraphChunker;
    }

    /**
     * 按声明的策略切分，必要时降级。
     *
     * @param text     文档正文
     * @param strategy 由 {@link com.hagent.document.DocumentKind} 声明的策略
     * @param maxSize  单块上限字符数
     * @param overlap  相邻块重叠字符数
     * @param source   来源路径（仅用于日志定位）
     * @return 切好的块，永不为空（除非正文本身为空）
     */
    public List<Chunk> chunk(String text, ChunkingStrategy strategy, int maxSize, int overlap, String source) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 第一级：按声明策略
        List<Chunk> chunks = apply(strategy, text, maxSize, overlap);
        if (!chunks.isEmpty()) {
            return chunks;
        }

        // 降级链 —— 按"语义边界从强到弱"的顺序尝试
        for (ChunkingStrategy fallback : fallbacksFor(strategy)) {
            chunks = apply(fallback, text, maxSize, overlap);
            if (!chunks.isEmpty()) {
                log.warn("[知识入库] 来源={} 的声明策略 [{}] 不适用（可能无标题或超长），"
                                + "已降级为 [{}]。影响：该文档的块可能缺少章节名，"
                                + "拼接出处时会退化为按块序号定位。",
                        source, strategy, fallback);
                return chunks;
            }
        }

        // 所有策略都不适用：正常文档不会走到这里（段落切分的内部还有句子级与硬切兜底）
        throw new IllegalStateException(
                "所有切分策略都无法处理该文档（来源=" + source + "）。"
                        + "这通常意味着正文是纯空白字符或存在异常字符，请检查文档内容。");
    }

    private List<Chunk> apply(ChunkingStrategy strategy, String text, int maxSize, int overlap) {
        return switch (strategy) {
            case WHOLE_DOCUMENT -> wholeDocumentChunker.chunk(text, maxSize, overlap);
            case BY_HEADING -> headingChunker.chunk(text, maxSize, overlap);
            case BY_PARAGRAPH -> paragraphChunker.chunk(text, maxSize, overlap);
        };
    }

    /**
     * 某策略不适用时的降级顺序。
     *
     * <h3>★ 规则：降级的对象是"<b>切分粒度</b>"，不是"文档结构类型"</h3>
     *
     * <p>最初的实现是 {@code WHOLE_DOCUMENT → BY_HEADING → BY_PARAGRAPH}，
     * 理由是"标题比段落更结构化"。但集成测试立刻暴露了这个顺序的问题：
     * 公司介绍（正文 717 字，超过 400 上限）被降级到 {@code BY_HEADING}，
     * 而它<b>恰好一个标题都没有</b> —— 一次降级没成功，要再降一级才落到段落切分，
     * 白白多走一轮，日志里也容易让人误以为"按标题切分生效了"。
     *
     * <p>真正要表达的是<b>粒度递增</b>：
     * <pre>
     *   整篇一块（最粗）  →  按段落（中等）  →  按句子（最细，在段落策略内部）
     * </pre>
     * {@code BY_HEADING} 是<b>另一种平行策略</b>，不是"介于整篇与段落之间的一级"——
     * 它只在文档<b>确实有标题</b>时才有意义，而"整篇装不下"这件事
     * 本身并不提示文档有标题。
     *
     * <p>所以正确的降级链是：<b>整篇 → 段落</b>（段落策略内部再降到句子）。
     * 至于"有标题的文档" —— 它一开始就声明了 {@code BY_HEADING}，不依赖这条降级链。
     */
    private List<ChunkingStrategy> fallbacksFor(ChunkingStrategy strategy) {
        return switch (strategy) {
            case WHOLE_DOCUMENT -> List.of(ChunkingStrategy.BY_PARAGRAPH);
            case BY_HEADING -> List.of(ChunkingStrategy.BY_PARAGRAPH);
            // BY_PARAGRAPH 是链条末端：它内部还有句子级与硬切兜底，
            // 返回空列表本身就意味着"无法处理"，调度器会据此抛出明确异常
            case BY_PARAGRAPH -> List.of();
        };
    }
}
