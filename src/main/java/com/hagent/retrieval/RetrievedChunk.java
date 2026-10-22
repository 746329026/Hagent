package com.hagent.retrieval;

import com.hagent.document.AccessLevel;
import com.hagent.document.DocumentMetadata;

import java.util.Map;

/**
 * 一条<b>检索命中</b> —— 从 pgvector 的一行提炼出的、拼 prompt 所需要的最小信息。
 *
 * <p>不直接把框架的 {@code Document} 传下去：字段名会变成"运行时才知道"的东西 ——
 * 下游写 {@code doc.getMetadata().get("section")}，拼错 key 不报错、只是永远拿到 null；
 * 本记录在<b>构造时</b>取出字段（取不到就在这里暴露），也让"section 为空降级为第 N 块"
 * 这类转换规则只有一个出处。字段名一律引用 {@link DocumentMetadata#FIELD_*} 常量。
 *
 * @param content    块正文
 * @param source     来源路径（相对知识库根目录），如 {@code 员工手册.md}
 * @param title      文档标题，出处的兜底（入库时保证非空）
 * @param section    章节标题；可能为空字符串
 * @param chunkIndex 块在文档内的序号，从 0 开始
 * @param level      密级 —— <b>过滤已在 SQL 侧完成</b>，主代码里没有读者，
 *                   但权限集成测试靠它证明"SQL 过滤真的按密级生效"（安全性质，不是展示），故保留。
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
     * 从向量库的 metadata 构造。字段取不到时给出<b>明确的默认值</b>，而不是抛异常 ——
     * "检索到一条元数据不全的记录"不该让整轮对话失败，但取值规则必须集中在这里。
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
     * <p><b>降级规则</b>：{@code section} 允许为空，若直接取空串拼出"来源：公司介绍 "，
     * 模型很可能自己"补全"出一个看起来合理的章节名，也就是<b>编造来源</b>；
     * 所以显式降级成"第 N 块"（{@code chunkIndex + 1}，对人是 1-based）。
     *
     * <p>这个 "+1" 只在这里出现一次 —— 散在多处，迟早会有一处忘了加。
     */
    public String displaySource() {
        String location = (section == null || section.isBlank())
                ? "第 " + (chunkIndex + 1) + " 块"
                : section;
        String docName = (title == null || title.isBlank()) ? source : title;
        return docName + " " + location;
    }

    // metadata 取值的小工具 —— 收口在此，避免下游各自写强制转换

    private static String text(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * {@code chunkIndex} 在库里存的是<b>数字</b>（jsonb 数字，不是字符串）。
     *
     * <p>同时接受 {@code Number} 与数字形式的字符串：JDBC 驱动读 json 列时可能给出
     * {@code Integer} 也可能给出 {@code String}（本项目实测该列类型是 {@code json} 而非
     * {@code jsonb}），两种都接受，避免"换个驱动就读不到序号"这种极难排查的问题。
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
     * 密级解析 —— <b>规则只有一处</b>（{@link AccessLevel#tryParse}），这里只决定策略：<b>宽松</b>。
     *
     * <p>读侧不该因为一条脏记录让整轮对话失败，所以取不到/非法时落到
     * {@link AccessLevel#DEFAULT}（= INTERNAL，偏严的一侧）。
     * 规则若各自维护，一旦漂移就表现为"同一文档在入库与检索时被判成不同密级"，且不报错。
     */
    private static AccessLevel level(Map<String, Object> metadata) {
        return AccessLevel.tryParse(text(metadata, DocumentMetadata.FIELD_LEVEL, ""))
                .orElse(AccessLevel.DEFAULT);
    }
}
