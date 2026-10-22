package com.hagent.dto;

/**
 * 同步对话响应体。
 *
 * <p><b>为什么是对象而不是 String</b>：Controller 直接返回 String 时，Spring 会走
 * {@code StringHttpMessageConverter}，<b>绕过 JSON 序列化</b>输出裸字符串。
 *
 * @param reply     模型的回答正文。<b>可能为空</b>（模型只思考没作答）。
 * @param reasoning 模型的思考过程（DeepSeek 的 {@code reasoningContent}），
 *                  <b>可能为空</b> —— 提供方独有字段，换掉 DeepSeek 就取不到。
 *                  流式路径靠它让<b>刷新页面后仍能显示思考区</b>（会话记忆不存思考）。
 */
public record ChatResponse(String reply, String reasoning) {
}
