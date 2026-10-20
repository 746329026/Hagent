package com.hagent.retrieval;

import com.hagent.document.AccessLevel;
import com.hagent.document.DocumentMetadata;

import java.util.Map;

/**
 * 一条<b>检索命中</b> —— 从 pgvector 的一行提炼出的、拼 prompt 所需要的最小信息。
 *
 * <h2>★ 为什么不直接把框架的 {@code Document} 传下去</h2>
 *
 * <p>{@code Document} 是向量库的通用载体：它带着 {@code Map<String,Object> metadata}
 * 和可能很大的 {@code embedding}。直接往下传有两个具体坏处：
 * <ol>
 *   <li><b>字段名变成"运行时才知道"的东西。</b>
 *       下游要写 {@code doc.getMetadata().get("section")}，
 *       拼错 key 不报错、只是永远拿到 null（{@code DocumentMetadata}
 *       的类注释已经记录过这个陷阱）。本记录把字段在<b>构造时</b>取出来，
 *       取不到就在这里暴露。</li>
 *   <li><b>截断/转换逻辑会散落。</b>"section 为空时降级为第 N 块"
 *       这类规则必须只有一个出处，否则两处写法不一致时不会报错。</li>
 * </ol>
 *
 * <h2>★ 字段与 {@link DocumentMetadata} 的对应关系</h2>
 *
 * <p>本记录<b>不自己发明字段名</b>，一律引用 {@link DocumentMetadata#FIELD_*} 常量 ——
 * 入库写、检索读的字段名只有一个出处。
 *
 * @param content    块正文
 * @param source     来源路径（相对知识库根目录），如 {@code 员工手册.md}
 * @param title      文档标题，出处的兜底（入库时保证非空）
 * @param section    章节标题；可能为空字符串
 * @param chunkIndex 块在文档内的序号，从 0 开始
 * @param level      密级 —— <b>过滤已在 SQL 侧完成</b>，这里的值只用于核对/断言。
 *                   ★ 阶段 12 复核：它在<b>主代码</b>里没有读者，但有真实消费者 ——
 *                   权限集成测试靠它证明"SQL 过滤真的按密级生效"（安全性质，不是展示）。
 *                   所以<b>保留</b>，不当"零读者字段"删掉。
 * @param similarity 余弦相似度（<b>越大越相关</b>，实测值域约 0.51~0.82）
 * @param band       由 {@link #similarity} 换算出的相关度档位
 */
public record RetrievedChunk(
        String content,
        String source,
        String title,
        String section,
        int chunkIndex,
        AccessLevel level,
        double similarity,
        RelevanceBand band) {

    /**
     * 从向量库的 metadata 构造。字段取不到时给出<b>明确的默认值</b>，
     * 而不是抛异常 —— 因为"检索到一条元数据不全的记录"不该让整轮对话失败，
     * 但取值规则必须集中在这里（见类注释）。
     *
     * @param metadata   pgvector 的 metadata 列
     * @param content    块正文
     * @param similarity 相似度
     * @param band       档位
     */
    public static RetrievedChunk from(Map<String, Object> metadata,
                                      String content,
                                      double similarity,
                                      RelevanceBand band) {
        String source = text(metadata, DocumentMetadata.FIELD_SOURCE, "");
        String title = text(metadata, DocumentMetadata.FIELD_TITLE, "");
        String section = text(metadata, DocumentMetadata.FIELD_SECTION, "");
        int chunkIndex = number(metadata, DocumentMetadata.FIELD_CHUNK_INDEX);
        // 密级取不到时给 INTERNAL（保守：偏严的一侧），而不是 PUBLIC。
        // 这与 AccessLevel.DEFAULT 的取向一致 —— 出问题时要"少看到"而不是"多看到"。
        AccessLevel level = level(metadata);
        return new RetrievedChunk(content, source, title, section, chunkIndex, level, similarity, band);
    }

    /**
     * 出处的<b>展示文本</b> —— 拼进 prompt 的那一段。
     *
     * <p><b>★ 为什么必须有这个降级规则</b>：
     * {@code section} 允许为空（{@code DocumentMetadata} 的注释写明：
     * 有些文档没有可直接引用的章节）。若直接取空串，拼出来就是
     * "来源：公司介绍 "—— 模型拿到一个不完整的出处，很可能自己"补全"成
     * 一个看起来很合理的章节名，也就是<b>编造来源</b>。
     * 所以这里显式降级成"第 N 块"（N = chunkIndex + 1，对人是 1-based）。
     *
     * <p>用 {@code chunkIndex + 1} 而不是 {@code chunkIndex}：前者是给人看的。
     * 这个 "+1" 只在这里出现一次 —— 若散在多处，迟早会有一处忘了加。
     */
    public String displaySource() {
        String location = (section == null || section.isBlank())
                ? "第 " + (chunkIndex + 1) + " 块"
                : section;
        String docName = (title == null || title.isBlank()) ? source : title;
        return docName + " " + location;
    }

    // ========================================================================
    // metadata 取值的小工具 —— 收口在此，避免下游各自写强制转换
    // ========================================================================

    private static String text(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * {@code chunkIndex} 在库里存的是<b>数字</b>（jsonb 数字，不是字符串）。
     *
     * <p>这里同时接受 {@code Number} 与数字形式的字符串：JDBC 驱动读 json 列时
     * 可能给出 {@code Integer}，也可能给出 {@code String}，取决于列类型与驱动版本
     * （本项目实测该列类型是 {@code json} 而非 {@code jsonb}）。
     * 两种都接受，避免"换个驱动就读不到序号"这种极难排查的问题。
     */
    private static int number(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到下面的默认值
            }
        }
        return 0;
    }

    /**
     * 密级解析 —— <b>规则只有一处</b>（{@link AccessLevel#tryParse}），这里只决定策略。
     *
     * <p>策略：<b>宽松</b>。读侧不该因为一条脏记录让整轮对话失败，所以取不到/非法时落到
     * {@link AccessLevel#DEFAULT}（= INTERNAL，偏严的一侧）。
     *
     * <p>★ 阶段 12 第 2 步：这里原来自带一份"字符串 → 枚举"的循环，与 {@code AccessLevel.parse}
     * 平行维护；两处一旦漂移，就表现为"同一个文档在入库与检索时被判成不同密级"，且不报错。
     */
    private static AccessLevel level(Map<String, Object> metadata) {
        return AccessLevel.tryParse(text(metadata, DocumentMetadata.FIELD_LEVEL, ""))
                .orElse(AccessLevel.DEFAULT);
    }
}
