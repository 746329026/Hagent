package com.hagent.dto;

/**
 * 同步对话响应体。
 *
 * <p><b>为什么是对象而不是 String</b>（这是一个有实际报错对应的决定）：
 * Controller 若直接返回 String，Spring 会走 {@code StringHttpMessageConverter}，
 * <b>绕过 JSON 序列化</b>，直接输出裸字符串 —— 你会拿不到想要的 JSON 结构。
 * 用 record 对象则走 Jackson 序列化，格式受控，字段可扩展。
 *
 * @param reply     模型的回答正文。<b>可能为空</b>（模型只思考没作答）。
 * @param reasoning 模型的<b>思考过程</b>（DeepSeek 的 {@code reasoningContent}）。
 *                  <b>可能为空</b> —— 它是模型提供方的独有字段，换掉 DeepSeek 就取不到。
 *
 *                  <p><b>★ 阶段 8 新增这个字段的理由</b>：流式路径（{@code /stream-sse}）
 *                  可以把思考逐片推给前端，但<b>刷新页面后思考就永久丢了</b> ——
 *                  会话记忆只存"问 + 最终答"（决策 32），不存思考。
 *                  阻塞路径带上它，前端才能在回看历史时也显示思考区。
 */
public record ChatResponse(String reply, String reasoning) {
}
