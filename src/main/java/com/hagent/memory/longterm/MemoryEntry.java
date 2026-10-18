package com.hagent.memory.longterm;

import com.hagent.common.Texts;
import com.hagent.document.AccessLevel;

import java.time.Instant;

/**
 * 一条长期记忆。
 *
 * @param title     标题（人可读、<b>同时是身份</b>：同名即同一条记忆，保存时覆盖）
 * @param content   正文
 * @param level     密级 —— <b>直接复用 {@link AccessLevel}，不新造概念</b>。
 *                  写入时取写入者角色的那一个（{@code user.clearance()}）；
 *                  注入前用 {@code level.visibleTo(当前用户密级)} 过滤，
 *                  与检索用的是<b>同一个枚举、同一条规则</b>。
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间（索引按它倒序）
 */
public record MemoryEntry(String title,
                          String content,
                          AccessLevel level,
                          Instant createdAt,
                          Instant updatedAt) {

    /** 索引里每条只留一句话摘要，最多这么长。 */
    public static final int SUMMARY_MAX_CHARS = 40;

    /**
     * 一句话摘要 —— 从正文压出来。
     *
     * <p><b>为什么不让模型单独传一个 summary 参数</b>：多一个参数就多一次"它可能写不好/写太长"，
     * 而摘要完全可以从正文派生（压成一行 + 截断）。派生出来的东西不会与正文不一致。
     */
    public String summary() {
        return Texts.truncateByCodePoints(
                Texts.singleLine(content), SUMMARY_MAX_CHARS, "…");
    }

    /** 是否对"拥有给定密级的用户"可见 —— 直接复用 {@link AccessLevel#visibleTo}。 */
    public boolean visibleTo(AccessLevel viewerLevel) {
        return level != null && level.visibleTo(viewerLevel);
    }
}
