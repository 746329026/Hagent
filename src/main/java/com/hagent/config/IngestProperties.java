package com.hagent.config;

import com.hagent.document.DocumentKind;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 知识库入库配置 —— 绑定 {@code hagent.rag.ingest.*}。
 *
 * <h2>★ {@code default-kind} 为什么是必填的、且没有默认值</h2>
 *
 * <p>文档种类（{@link DocumentKind}）决定<b>切分策略</b>与<b>默认密级</b>。
 * 如果给它一个默认值，那么"一份忘了写 frontmatter 的文档"会被
 * 静默按某个种类处理 —— 而后果是：
 * <ul>
 *   <li>用了不合适的切分策略（如给长制度文档用了"整篇一块"），
 *       检索质量下降但<b>没有任何报错</b>；</li>
 *   <li>被赋予了不合适的默认密级 —— 若那个默认恰好更宽松，
 *       就是一次真实的越权风险。</li>
 * </ul>
 *
 * <p>所以本配置<b>不设默认值</b>：不配置它，应用启动时绑定失败并给出明确提示，
 * 而不是让一个猜出来的种类悄悄影响数据。这属于"宁可启动失败，也不要静默猜错"
 * （与 {@code MemoryConfigValidator} 的立场一致）。
 *
 * <p>但要注意：它只是<b>目录级默认值</b>。文档 frontmatter 里写了 {@code kind} 时，
 * 以文档自己声明的为准 —— 因为单份文档比整个目录更了解自己是什么。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.ingest")
public class IngestProperties {

    /**
     * 知识库根目录（绝对路径或相对工作目录）。
     *
     * <p>所有来源路径都必须落在这个目录内 —— 这是路径穿越防护的基准点，
     * 见 {@code SourceLoader#resolveWithinRoot}。
     */
    private String rootDir = "knowledge";

    /**
     * 文档未声明 kind 时使用的默认种类。
     *
     * <p><b>不设默认值</b>，理由见类注释。必须在 {@code application.yml} 里显式配置。
     */
    private DocumentKind defaultKind;

    /** 归属部门（写入 metadata 的 {@code dept}）。空 = 全公司可见。 */
    private String dept = "";

    /** 支持的扩展名。本阶段只做纯文本格式，见 {@code SourceLoader} 的说明。 */
    private List<String> extensions = List.of(".md", ".txt");
}
