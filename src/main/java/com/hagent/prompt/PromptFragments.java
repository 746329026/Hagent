package com.hagent.prompt;

/**
 * <b>提示词可见文本的唯一出处</b>。
 *
 * <h2>为什么这些字面量必须有一个家</h2>
 *
 * <p>这里有一条<b>不会报错的耦合</b>：代码渲染的标记与 {@code prompts/rag-answer-prompt.md}
 * 里的约束靠人工对齐。若把片段头改成别的写法，提示词就指向一个不存在的标记 ——
 * 模型照常回答、日志全绿，只是那条约束<b>悄悄失效了</b>。所以标记字面量只住在这里，
 * 并由 {@code PromptFragmentsTest} 读那份 {@code .md} 断言每个标记都真的出现 ——
 * 把"人工对齐"变成"改一边另一边就红"。
 *
 * <p>{@code RelevanceBand.displayName()} 刻意<b>不</b>搬进来：它同时是给模型的片段文本与
 * 发给前端的 {@code band} 字段，搬进 {@code prompt/} 等于把前端契约塞进"模型可见文本"。
 */
public final class PromptFragments {

    private PromptFragments() {
    }

    /** 检索片段块的起始标记 —— 提示词第 1 条引用了它。 */
    public static final String KNOWLEDGE_FRAGMENT_HEADER = "【知识库片段】";

    /** 片段里"相关度"这个字段名 —— 提示词第 5 条引用了它（「若某片段的【相关度】为低…」）。 */
    public static final String RELEVANCE_FIELD_LABEL = "相关度";
}
