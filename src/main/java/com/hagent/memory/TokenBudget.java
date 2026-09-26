package com.hagent.memory;

/**
 * 本次加载历史时愿意付出的预算上限。
 *
 * <p><b>为什么要把预算做成参数，而不是藏在实现里</b>：
 * 会话模式与工作流模式对上下文的需求完全不同 ——
 * 会话短、交互频繁、用户在意响应速度；工作流长、多步、每步结果都要带下去。
 * 如果预算写死在实现内部，调用方就<b>无法按场景区分</b>，
 * 而且"这次会带多少历史"这件事在调用点<b>不可见</b>，成本也就不可控。
 *
 * <p><b>★ 预算的口径：只管历史，不含 system 提示词与本次问题</b>
 * （这是刻意的设计，见 {@link ConversationMemory#load}）：
 * <ul>
 *   <li>system 提示词是固定开销，与历史无关；</li>
 *   <li><b>本次问题必须永远保留</b> —— 把它算进预算，就有"截断时把当前问题丢掉"的风险。
 *       排除在预算之外，就从设计上杜绝了这种可能。</li>
 * </ul>
 *
 * @param maxTokens   历史消息的 token 上限（主限制，直接对应成本）
 * @param maxMessages 历史消息的条数上限（兜底限制）
 */
public record TokenBudget(int maxTokens, int maxMessages) {

    /**
     * 为什么还要一个"条数上限"：
     * 纯 token 限制有个漏洞 —— 大量<b>超短消息</b>（"嗯""好""继续"）单独看都不占 token，
     * 但每条消息在 prompt 里都有角色标记等<b>固定开销</b>，条目数一多，开销同样可观。
     * 因此两个限制<b>同时生效，谁先到就用谁</b>。
     */
    public TokenBudget {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须为正数，当前=" + maxTokens);
        }
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages 必须为正数，当前=" + maxMessages);
        }
    }

    /** 会话模式：短、交互频繁，预算小。 */
    public static TokenBudget conversation(int maxTokens, int maxMessages) {
        return new TokenBudget(maxTokens, maxMessages);
    }

    /** 工作流 / Agent 模式：长、多步，预算大。 */
    public static TokenBudget workflow(int maxTokens, int maxMessages) {
        return new TokenBudget(maxTokens, maxMessages);
    }
}
