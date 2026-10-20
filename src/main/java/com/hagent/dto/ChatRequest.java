package com.hagent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 同步对话请求体。
 *
 * <p>用 record 而不是 Lombok @Data：请求体是"进来后只读"的数据，
 * 不需要 setter，record 的不可变性正好表达这个约束。
 *
 * @param message        用户输入。非空校验放在这里，让非法输入在进入业务代码之前就被拒绝。
 * @param conversationId 会话标识。<b>可选</b>：
 *                       <ul>
 *                         <li>不传 → 服务端生成一个随机 UUID（新建会话），
 *                             并通过响应头 {@code X-Conversation-Id} 返回给前端；</li>
 *                         <li>传入 → 续用该会话（此时模型能看到之前的历史）。</li>
 *                       </ul>
 *                       <p><b>为什么由服务端生成而不是前端自己造</b>：
 *                       前端生成会带来两个真实风险 ——
 *                       ① 重复 ID 导致历史互相覆盖；
 *                       ② <b>越权</b>：攻击者可猜/遍历 ID 读到别人的会话（IDOR）。
 *                       所以服务端用<b>随机 UUID</b>（不可预测），而不是自增序号。
 * @param mode           记忆模式（阶段 10 新增，<b>可选</b>）：{@code chat}（默认）/ {@code workflow}。
 *                       <p><b>它决定什么</b>：本次对话愿意为历史付多少预算
 *                       （{@code chat-max-*} 5k token vs {@code workflow-max-*} 50k token，
 *                       差一个数量级）。所以它不是一个"风格开关"，是<b>成本开关</b>。
 *                       <p><b>为什么放在请求里</b>：只有 HTTP 层知道"这次属于哪种场景"。
 *                       但这个值<b>只在会话第一次创建时生效</b>，之后由会话目录记住
 *                       （{@code chat_conversation.mode}）——
 *                       否则客户端可以每轮换一次模式，让预算来回跳且事后查不到原因。
 *                       <p><b>写错了会怎样</b>：不是静默退回默认值，而是
 *                       <b>HTTP 400</b>（{@code mode 取值非法}）——
 *                       静默退回会让"打错一个字母"表现为"模型忽然开始忘事"。
 */
public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        String message,

        String conversationId,

        String mode
) {
}
