package com.hagent.config;

import com.hagent.document.DocumentKind;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 知识库入库配置 —— 绑定 {@code hagent.rag.ingest.*}。
 *
 * <p>★ {@code default-kind} 为什么必填、且没有默认值：文档种类（{@link DocumentKind}）决定
 * <b>切分策略</b>与<b>默认密级</b>。给它默认值，"一份忘了写 frontmatter 的文档"就会被静默
 * 按某个种类处理 —— 用了不合适的切分策略（检索变差但无报错），或被赋予更宽松的密级
 * （真实的越权风险）。不配置则绑定失败并给出明确提示，宁可启动失败，也不要静默猜错。
 *
 * <p>但它只是<b>目录级默认值</b>：frontmatter 里写了 {@code kind} 时以文档自己的声明为准。
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
     * <p><b>不设默认值</b>（理由见类注释），必须在 {@code application.yml} 里显式配置。
     */
    private DocumentKind defaultKind;

    /** 归属部门（写入 metadata 的 {@code dept}）。空 = 全公司可见。 */
    private String dept = "";

    /** 支持的扩展名。只做纯文本格式，见 {@code SourceLoader} 的说明。 */
    private List<String> extensions = List.of(".md", ".txt");
}
