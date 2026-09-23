package com.hagent.dto;

/**
 * 同步对话响应体。
 *
 * <p><b>为什么是对象而不是 String</b>（这是一个有实际报错对应的决定）：
 * Controller 若直接返回 String，Spring 会走 {@code StringHttpMessageConverter}，
 * <b>绕过 JSON 序列化</b>，直接输出裸字符串 —— 你会拿不到想要的 JSON 结构。
 * 用 record 对象则走 Jackson 序列化，格式受控，字段可扩展。
 *
 * @param reply 模型回复文本。
 */
public record ChatResponse(String reply) {
}
