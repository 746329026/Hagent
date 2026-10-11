package com.hagent.advisor;

/**
 * 拼装 prompt 所需的 <b>context key 常量</b> —— <b>切面之间</b>传递数据的唯一出处。
 *
 * <h2>★ 为什么它放在 {@code advisor} 包，而不是单独一个 {@code prompt} 包</h2>
 *
 * <p>第一版把它和 {@code PromptProperties} 一起放在 {@code com.hagent.prompt}，
 * 结果是<b>同一个能力被劈成两个包</b>：做事的三个切面在 {@code advisor}，
 * 而它们之间约定的 key 在 {@code prompt}。
 *
 * <p>判据很简单：<b>谁用它，它就跟谁走</b>。实测它的全部使用者都在
 * {@code com.hagent.advisor}（{@code MemoryReadAdvisor} 写、
 * {@code RagRetrievalAdvisor} 写、{@code PromptAssemblyAdvisor} 读），
 * 没有任何 {@code advisor} 之外的消费者 —— 所以它属于这里。
 * （{@code PromptProperties} 则按本项目"配置类放 {@code config}"的惯例归 {@code config}。）
 *
 * <p>这与协议第 7 条同源：<b>不为还没有使用者的地方预留结构</b>。
 * 将来若真有非切面代码要往 context 里放片段，再搬走也只是改一个 import。
 *
 * <h2>★ 为什么需要这个类（而不是各切面自己写字符串）</h2>
 *
 * <p>本项目的切面之间通过 {@code ChatClientRequest.context()} 这个
 * <b>扁平的 {@code Map<String,Object>}</b> 传递"请求级数据"。
 * 它有一个必须正视的弱点：<b>编译器管不到 key</b>。
 *
 * <pre>
 *   MemoryReadAdvisor   : context.put("hagent_prompt_memory", history)
 *   PromptAssemblyAdvisor: context.get("hagent_prompt_memorys")   ← 拼错一个字母
 *   → 编译通过、运行不报错、注入的历史永远为空
 * </pre>
 *
 * <p>{@code PromptTraceAdvisor} 的类注释批评过参考实现的同一个问题
 * （"context 的 key 是一个裸字符串，两个类靠人工对齐，拼错不会报错"）。
 * 本项目无法取消 context（切面只能靠它拿请求级数据），
 * 但可以做到<b>key 只有一个出处</b>：所有读写都引用这里的常量。
 *
 * <p><b>诚实说明这仍是"运行期才暴露"的弱保证</b>：常量能防拼写错误，
 * 防不住"类型放错"（往 {@link #MEMORY_MESSAGES} 里放了一个 String）。
 * 因此每个读取点都做类型检查并在不符时<b>明确告警</b>，而不是静默当空 ——
 * 见 {@code PromptAssemblyAdvisor} 的取值方法。
 *
 * <h2>★ 命名规则</h2>
 *
 * <p>全小写 + 下划线 + {@code hagent_} 前缀，与框架常量
 * {@code ChatMemory.CONVERSATION_ID}（{@code "chat_memory_conversation_id"}）
 * 和 {@code MemoryMode.CONTEXT_KEY} 同风格。
 * 加前缀是为了避免与框架将来新增的 key 撞名 ——
 * context 是扁平 Map，<b>撞名会静默覆盖</b>。
 */
public final class PromptFragmentKeys {

    private PromptFragmentKeys() {
        // 常量类，禁止实例化
    }

    /**
     * 记忆读取的产出：{@code List<Message>}。
     *
     * <p>由 {@code MemoryReadAdvisor} 写入，由 {@code PromptAssemblyAdvisor} 读出。
     * <b>值必须是 {@code List<Message>}</b>，不是字符串 ——
     * 历史是带角色的消息，压成文本会丢掉角色信息（模型会分不清哪句是谁说的）。
     */
    public static final String MEMORY_MESSAGES = "hagent_prompt_memory_messages";

    /**
     * 检索的产出：{@code List<RetrievedChunk>}。
     *
     * <p>由 {@code RagRetrievalAdvisor} 写入，由 {@code PromptAssemblyAdvisor} 读出。
     *
     * <p><b>★ 这个 key 同时是"检索是否已发生"的标志</b>，有两重含义，必须一起看：
     * <ul>
     *   <li><b>key 不存在</b> → 检索切面没跑（例如 enabled=false，
     *       或该调用路径没有挂链）→ 拼装时不注入任何片段，也<b>不应告警</b>；</li>
     *   <li><b>key 存在但列表为空</b> → 检索跑了但没命中 →
     *       这是<b>正常结果</b>，但要记一条 WARN（用户问的东西不在知识库里，
     *       而回答质量会因此下降，值得可见）。</li>
     * </ul>
     *
     * <p>"存在但为空"这一状态是刻意保留的：若用 {@code null} 表示"没跑"、
     * 空列表表示"没命中"，两者在 {@code HashMap} 里是可以区分的
     * —— 但只有在一开始就决定"永远放一个列表（哪怕是空的）"时才成立。
     * 所以 {@code RagRetrievalAdvisor} <b>总是</b>写这个 key。
     */
    public static final String RAG_FRAGMENTS = "hagent_prompt_rag_fragments";
}
