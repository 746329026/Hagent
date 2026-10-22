package com.hagent.memory;

/**
 * 本次加载历史时愿意付出的预算上限。
 *
 * <p><b>为什么预算要做成参数而不是藏在实现里</b>：会话短、交互频繁；工作流长、多步、每步结果
 * 都要带下去 —— 写死在实现内部，调用方就无法按场景区分，而且"这次会带多少历史"在调用点不可见，
 * 成本也就不可控。
 *
 * <p><b>预算的口径：只管历史，不含 system 提示词与本次问题</b>
 * （见 {@link ConversationMemory#load}）：system 提示词是固定开销；而本次问题<b>必须永远保留</b>，
 * 把它算进预算就有"截断时把当前问题丢掉"的风险，排除在外就从设计上杜绝了。
 *
 * @param maxTokens   历史消息的 token 上限（主限制，直接对应成本）
 * @param maxMessages 历史消息的条数上限（兜底限制）
 */
public record TokenBudget(int maxTokens, int maxMessages) {

    /**
     * 为什么还要一个"条数上限"：纯 token 限制有个漏洞 —— 大量<b>超短消息</b>（"嗯""好""继续"）
     * 正文都不占 token，但每条在 prompt 里都有角色标记等<b>固定开销</b>，条目一多同样可观。
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
}
