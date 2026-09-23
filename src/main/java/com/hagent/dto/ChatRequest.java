package com.hagent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 同步对话请求体。
 *
 * <p>用 record 而不是 Lombok @Data：请求体是"进来后只读"的数据，
 * 不需要 setter，record 的不可变性正好表达这个约束。
 *
 * <p><b>字段的克制（一个刻意的设计决定）</b>：
 * 现在只放 {@code message} 一个字段 —— 会话 ID、历史消息、温度等<b>全部不加</b>。
 * 理由：DTO 的字段是接口契约，加了就不好删；而"会话"是阶段 6 才有的概念，
 * 现在加 conversationId 属于"备而不用"。
 * 等阶段 6 加会话时，只需在这里加一个字段、app 层多传一个参数，
 * Controller 的签名不变 —— 这正是"预留"的正确姿势：<b>不是提前加字段，
 * 而是把"将来加字段的成本"控制在最低。</b>
 *
 * @param message 用户输入。非空校验放在这里，让非法输入在进入业务代码之前就被拒绝。
 */
public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        String message
) {
}
