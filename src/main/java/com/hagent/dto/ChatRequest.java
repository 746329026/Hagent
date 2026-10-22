package com.hagent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 同步对话请求体。
 *
 * <p>用 record 而不是 Lombok @Data：请求体是"进来后只读"的数据，
 * 不需要 setter，record 的不可变性正好表达这个约束。
 *
 * @param message        用户输入。非空校验放在这里，让非法输入在进入业务代码之前就被拒绝。
 * @param conversationId 会话标识，<b>可选</b>：不传 → 服务端生成随机 UUID（新建会话）并经响应头
 *                       {@code X-Conversation-Id} 返回；传入 → 续用该会话（模型能看到之前的历史）。
 *                       <p><b>为什么必须服务端生成</b>：前端生成会让重复 ID 覆盖历史，且 ID 可猜
 *                       可遍历就能读别人的会话（IDOR 越权）—— 所以是随机 UUID，不是自增序号。
 * @param mode           记忆模式，<b>可选</b>：{@code chat}（默认）/ {@code workflow}。
 *                       <p>它是<b>成本开关</b>不是风格开关：决定本次对话愿意为历史付多少预算
 *                       （{@code chat-max-*} 5k token vs {@code workflow-max-*} 50k，差一个数量级）。
 *                       放在请求里因为只有 HTTP 层知道"这次属于哪种场景"，但它<b>只在会话第一次
 *                       创建时生效</b>，之后由会话目录记住（{@code chat_conversation.mode}）——
 *                       否则客户端可以每轮换一次模式，让预算来回跳且事后查不到原因。
 *                       <p>写错了是 <b>HTTP 400</b>，不静默退回默认值 ——
 *                       否则"打错一个字母"会表现为"模型忽然开始忘事"。
 */
public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        String message,

        String conversationId,

        String mode
) {
}
