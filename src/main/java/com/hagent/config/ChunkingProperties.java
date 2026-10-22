package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库切分参数 —— 绑定 {@code hagent.rag.chunking.*}。
 *
 * <p>★ 取值依据：{@code max-size = 400} 汉字是三条约束的交点 ——
 * ① 本地 Ollama {@code mxbai-embed-large} 的输入上限实测约 1000 汉字（超出<b>静默截断</b>），
 * 只用了约 40%；② 块越大块内主题越杂，向量越接近"多主题平均值"，与任何具体问题都变远；
 * ③ 400 字 × topK 3 ≈ 1200 token，占会话预算 5000 token 的约 24%。
 *
 * <p>★ {@code overlap = 60}（约 15%）：防止句子被块边界切断 —— 没有重叠时第二块会以半个
 * 句子开头，向量代表不完整的含义。再大则块间重复过多（挤占 topK 名额），再小则挡不住切断。
 *
 * <p>★ 对 {@code max-size} 刻意<b>不做启动自检</b>：配成 2000 会静默截断，
 * 但当前 400 远低于上限，不存在"配错就出事"的现实风险；加自检属于为假想的误用
 * 付出真实复杂度，限制写在使用处即可。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.chunking")
public class ChunkingProperties {

    /**
     * 单块最大字符数（按汉字计）。
     *
     * <p>⚠️ <b>上限约束</b>：embedding 模型 {@code mxbai-embed-large} 的输入上限实测约 1000
     * 汉字，超出部分被<b>静默截断</b> —— 不报错、向量维度正常，但块的后半部分对检索完全不可见。
     * 因此本值必须显著小于 1000。
     */
    private int maxSize = 400;

    /**
     * 相邻块的重叠字符数。
     *
     * <p>必须小于 {@link #maxSize}，否则每块都会被上一块的尾巴填满。
     */
    private int overlap = 60;

    /**
     * 合并"碎片尾块"的阈值比例。
     *
     * <p>切分推进到最后常剩几十个字。碎片块信息太少、向量区分度低，容易在无关问题下被误命中，
     * 还挤占有限的 topK 名额。小于 {@code maxSize * minTailRatio} 的尾块并入前一块。
     */
    private double minTailRatio = 0.25;
}
