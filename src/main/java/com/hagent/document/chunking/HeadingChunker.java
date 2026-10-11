package com.hagent.document.chunking;

import com.hagent.document.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>按 Markdown 标题切分</b> —— 每个标题及其正文成为一块（超长时再按句子细分）。
 *
 * <h2>为什么标题是最好的切分点</h2>
 *
 * <p>标题是<b>作者主动标注的语义边界</b>。作者写下"## 假期管理"就是在声明
 * "从这里开始讲另一件事"。这比按字数硬切准确得多，理由有三：
 *
 * <ol>
 *   <li><b>主题集中</b>：一块 = 一个小节，向量不会被多个主题平均掉；</li>
 *   <li><b>出处可用</b>：标题天然就是"章节名"，可以直接用作引用
 *       （"根据《员工手册》第三章假期管理"）—— 按字数硬切是拿不到这个的；</li>
 *   <li><b>长度天然不均但常常合适</b>：小节本身就是作者控制过的信息单元。</li>
 * </ol>
 *
 * <h2>几个必须处理的现实情况</h2>
 *
 * <table border="1">
 *   <caption>边界情况与处理方式</caption>
 *   <tr><th>情况</th><th>处理</th><th>为什么</th></tr>
 *   <tr>
 *     <td>文档开头、第一个标题<b>之前</b>有正文</td>
 *     <td>作为独立一块，章节名取文档标题</td>
 *     <td>这段正文通常正是"总则/概述"，丢掉它就丢了重要内容</td>
 *   </tr>
 *   <tr>
 *     <td>某个小节<b>超过块上限</b></td>
 *     <td>按句子细分，<b>但保留同一个章节名</b></td>
 *     <td>出处仍然准确；且不会因超长被 embedding 截断</td>
 *   </tr>
 *   <tr>
 *     <td>多个连续标题之间<b>没有正文</b></td>
 *     <td>跳过空小节</td>
 *     <td>标题本身不构成可检索的内容</td>
 *   </tr>
 *   <tr>
 *     <td>全文<b>没有任何标题</b></td>
 *     <td>返回空列表 → 由调度器降级到段落切分</td>
 *     <td>见 {@link TextChunker} 契约第 2 条</td>
 *   </tr>
 * </table>
 *
 * <h2>只识别哪种标题</h2>
 *
 * <p>识别 Markdown 的 ATX 标题（{@code #} 到 {@code ######}）。
 * 刻意<b>不</b>识别 Setext 风格（下划线式，如
 * {@code 标题} 换行 {@code =====}）—— 因为那种写法在正文里
 * 容易与分隔线、表格边框混淆，误判会把正文误当成标题，
 * 而"误判成标题"会凭空产生一个错误的章节名并污染出处。
 * 少支持一种写法只是功能少一点，误判则是静默的错误数据。
 */
@Component
public class HeadingChunker implements TextChunker {

    public static final String NAME = "by-heading";

    /**
     * 匹配 ATX 标题行：行首 1~6 个 {@code #}，后跟至少一个空格与标题文字。
     *
     * <p>要求"至少一个空格"是刻意的：{@code #话题} 在 Markdown 里<b>不是标题</b>
     * （是普通文本，常用于标签）。若不要求空格，这类文本会被误判成标题。
     */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$", Pattern.MULTILINE);

    @Override
    public List<Chunk> chunk(String text, int maxSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip();

        List<Section> sections = splitIntoSections(normalized);
        if (sections.isEmpty()) {
            // 没有标题 → 本策略不适用，交给调度器降级
            return List.of();
        }

        // 展开：每个小节可能因超长被细分成多块（但共用同一个章节名）
        List<Pending> pending = new ArrayList<>();
        for (Section section : sections) {
            List<String> pieces = ChunkingSupport.enforceMaxSize(section.body(), maxSize, overlap);
            for (String piece : pieces) {
                pending.add(new Pending(section.heading(), piece));
            }
        }
        if (pending.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            Pending p = pending.get(i);
            chunks.add(new Chunk(i, pending.size(), p.heading(), p.text()));
        }
        return chunks;
    }

    /**
     * 把文档切成"标题 + 正文"的小节。
     *
     * <p>{@code fallbackHeading} 用于第一个标题<b>之前</b>的那段正文 ——
     * 它没有自己的标题，但也不该被丢弃，所以给它一个可引用的名字。
     * 传空字符串表示"没有名字可用"，此时该块的 {@code section} 为空，
     * 拼出处时由块序号兜底。
     */
    private List<Section> splitIntoSections(String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Section> sections = new ArrayList<>();
        int cursor = 0;
        String currentHeading = null;   // null = 尚未遇到任何标题（前言部分）
        int headingCount = 0;

        while (matcher.find()) {
            String body = text.substring(cursor, matcher.start()).strip();
            if (!body.isEmpty()) {
                sections.add(new Section(currentHeading, body));
            }
            currentHeading = matcher.group(2).strip();
            headingCount++;
            cursor = matcher.end();
        }
        // 最后一个标题之后的正文
        String tail = text.substring(cursor).strip();
        if (!tail.isEmpty()) {
            sections.add(new Section(currentHeading, tail));
        }

        if (headingCount == 0) {
            return List.of();
        }
        // 前言部分（currentHeading 为 null 时产生）的章节名留空 —— 
        // 它确实没有章节名，编一个反而会让出处不准确
        List<Section> normalized = new ArrayList<>(sections.size());
        for (Section s : sections) {
            normalized.add(new Section(s.heading() == null ? "" : s.heading(), s.body()));
        }
        return normalized;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 一个小节：标题 + 正文。标题为空字符串表示"无标题的前言部分"。 */
    private record Section(String heading, String body) {
    }

    /** 细分后的待编号项：继承所属小节的章节名。 */
    private record Pending(String heading, String text) {
    }
}
