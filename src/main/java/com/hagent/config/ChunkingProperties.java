package com.hagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库切分参数 —— 绑定 {@code hagent.rag.chunking.*}。
 *
 * <h2>★ 参数取值的依据（每一条都有具体来源，不是拍脑袋）</h2>
 *
 * <h3>{@code max-size = 400}（汉字）</h3>
 * <p>三条独立依据：
 * <ol>
 *   <li><b>embedding 上限的安全余量</b>：本项目用本地 Ollama {@code mxbai-embed-large}，
 *       实测输入上限约 <b>1000 个汉字</b>（1000 字未截断、1010 字起<b>静默截断</b>）。
 *       400 只用了约 40%，留足余量。</li>
 *   <li><b>向量集中度</b>：块越大，块内主题越杂，向量越接近"多主题的平均值"，
 *       与任何具体问题的相似度都会下降 —— <b>块不是越大越好</b>。</li>
 *   <li><b>prompt 预算</b>：检索到的块要拼进 prompt，而会话预算是 5000 token。
 *       400 字 × topK 3 ≈ 1200 token，占约 24%，留足空间给历史与系统提示词。</li>
 * </ol>
 *
 * <h3>{@code overlap = 60}（汉字）</h3>
 * <p>约 15% 的重叠。作用是<b>防止语义被块边界切断</b>：一句话横跨两块时，
 * 没有重叠就会让第二块以半个句子开头，它的向量代表了一个不完整的含义。
 * 15% 是常见经验值 —— 再大则块之间重复过多（等于同一内容入库多次，
 * 会挤占 topK 名额），再小则挡不住句子被切断。
 *
 * <h2>★ 关于 {@code max-size} 的上限：这里刻意<b>不做</b>启动自检</h2>
 *
 * <p>把 {@code max-size} 配成 2000 会导致 embedding 静默截断（不报错、
 * 返回正常维度的向量、检索悄悄变差）。按理可以加一条启动期校验拦住它，
 * 但本项目在阶段 6 明确决定<b>不加</b>，而是在使用处写清限制。
 *
 * <p>决策依据（记录在此以便将来复核）：当前 400 远低于 1000 的上限，
 * 不存在"配错就出事"的现实风险；而加一条自检属于<b>为假想的误用付出真实复杂度</b>。
 * 等出现第二个会读这个参数的地方（或真有人改大了它）再加也不迟 ——
 * 见 {@code WORKING-AGREEMENT.md} 第 7 条。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hagent.rag.chunking")
public class ChunkingProperties {

    /**
     * 单块最大字符数（按汉字计）。
     *
     * <p>⚠️ <b>上限约束（必须遵守）</b>：embedding 模型 {@code mxbai-embed-large}
     * 的输入上限实测约 1000 汉字，超出部分会被<b>静默截断</b> ——
     * 不报错、返回正常维度的向量，但块的<b>后半部分对检索完全不可见</b>。
     * 因此本值必须显著小于 1000。
     */
    private int maxSize = 400;

    /**
     * 相邻块的重叠字符数。
     *
     * <p>作用见类注释。必须小于 {@link #maxSize}，否则每块都会被上一块的尾巴填满。
     */
    private int overlap = 60;

    /**
     * 合并"碎片尾块"的阈值比例。
     *
     * <p>切分推进到最后常剩下几十个字。这种碎片块承载信息太少、向量区分度低，
     * 容易在无关问题下被误命中，还会挤占有限的 topK 名额。
     * 小于 {@code maxSize * minRatio} 的尾块会被并入前一块。
     */
    private double minTailRatio = 0.25;
}
