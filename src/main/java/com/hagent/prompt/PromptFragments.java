package com.hagent.prompt;

/**
 * <b>提示词可见文本的唯一出处</b>（阶段 12 第 2 步）。
 *
 * <h2>★ 为什么这些字面量必须有一个家</h2>
 *
 * <p>本项目有一条<b>不会报错的耦合</b>：代码里渲染出来的标记，与
 * {@code prompts/rag-answer-prompt.md} 里写的约束，<b>靠人工对齐</b>。
 * 例如提示词第 1 条写"优先依据 retrieveKnowledge 返回的【知识库片段】回答" ——
 * 若某天把片段头改成【知识片段】，<b>提示词就指向一个不存在的标记</b>：
 * 模型照常回答、日志全绿，只是那条约束<b>悄悄失效了</b>。
 *
 * <p>所以：
 * <ol>
 *   <li>标记字面量只住在这里（代码侧唯一出处）；</li>
 *   <li>{@code PromptFragmentsTest} 读那份 {@code .md}，断言<b>每个标记都真的出现在提示词里</b> ——
 *       把"人工对齐"变成"改一边另一边就红"。</li>
 * </ol>
 *
 * <h2>★ 审计报告里"3 处模型可见文本"的复核更正</h2>
 *
 * <p>它列的第三处是 {@code RelevanceBand.displayName()}（高/中/低）。复核后<b>这一条不成立</b>：
 * 那个方法有<b>两个</b>消费者，且性质不同 ——
 * <ul>
 *   <li>{@code KnowledgeRetrievalTool#render} → 拼进给模型看的片段文本；</li>
 *   <li>{@code ChatAppService} → 作为 {@code band} 字段<b>发给前端</b>（前端契约）。</li>
 * </ul>
 * 把它搬进 `prompt/` 等于把<b>前端契约</b>塞进"模型可见文本"里 —— 名字会指向别处（协议第 12 条规则 1）。
 * 所以它<b>留在枚举里</b>：它是"档位的显示名"，不是"提示词片段"。
 *
 * <p>同理，长期记忆那段"【长期记忆】… 使用规则"的渲染<b>暂不在这里</b>：
 * 按 C1 已定方案，它要随门面一起搬到 {@code PromptBuilder}（第 3 步），
 * 现在先搬一半会造成"两个地方都能渲染提示词"。
 */
public final class PromptFragments {

    private PromptFragments() {
    }

    /** 检索片段块的起始标记 —— 提示词第 1 条引用了它。 */
    public static final String KNOWLEDGE_FRAGMENT_HEADER = "【知识库片段】";

    /** 片段里"相关度"这个字段名 —— 提示词第 5 条引用了它（「若某片段的【相关度】为低…」）。 */
    public static final String RELEVANCE_FIELD_LABEL = "相关度";
}
