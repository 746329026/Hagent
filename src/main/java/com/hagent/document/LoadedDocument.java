package com.hagent.document;

import java.util.Map;

/**
 * 一份<b>已加载的知识库文档</b> —— 加载阶段的产物，切分阶段的输入。
 *
 * <p>它把<b>文件读取</b>与<b>文本切分</b>解耦：切分器只认文本，不需要知道文本来自哪个文件，
 * 因此切分逻辑可以脱离真实文件系统单测。
 *
 * @param source      相对知识库根目录的来源路径，<b>统一用 {@code /} 分隔</b>。
 *                    它是文档的身份：幂等定位、按文档删除、出处展示都靠它。统一分隔符是刻意的 ——
 *                    同一份文档在 Windows 上是 {@code \}、Linux 上是 {@code /}，不统一就会得到
 *                    <b>两个不同的 source</b>，于是"删旧块"删不掉、导致重复入库。
 * @param title       文档标题，<b>不可为空</b>（frontmatter 的 title / 第一个 {@code #} 标题 / 文件名）。
 *                    它是出处的最后兜底。
 * @param kind        文档种类 —— 决定切分策略与默认密级。
 * @param level       密级。frontmatter 未指定时取 {@code kind} 的默认值。
 * @param dept        归属部门；空字符串表示全公司可见。
 * @param validFrom   生效日期；null 表示未设置（= 永久有效）。
 * @param validTo     失效日期；null 表示未设置。
 * @param body        正文文本（<b>已剥离 frontmatter</b>），即送去切分的部分。
 * @param frontmatter 原始 frontmatter；只读。
 */
public record LoadedDocument(
        String source,
        String title,
        DocumentKind kind,
        AccessLevel level,
        String dept,
        java.time.LocalDate validFrom,
        java.time.LocalDate validTo,
        String body,
        Map<String, Object> frontmatter) {

    public LoadedDocument {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空（来源=" + source + "）");
        }
        if (kind == null || level == null) {
            throw new IllegalArgumentException("kind / level 不能为空（来源=" + source + "）");
        }
        dept = dept == null ? "" : dept;
        body = body == null ? "" : body;
        frontmatter = frontmatter == null ? Map.of() : Map.copyOf(frontmatter);
    }
}
