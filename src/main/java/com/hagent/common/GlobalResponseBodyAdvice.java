package com.hagent.common;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hagent.stream.SseSupport;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装器：在 Controller 返回之后、真正写给浏览器之前，把返回值包装成
 * {@code {code,message,data}}，业务代码不必逐接口手写包装 —— 统一契约的机制层落地。
 *
 * <p>{@code beforeBodyWrite} 的四条分支，<b>顺序不能换</b>：
 * <pre>
 *   1. 放行流式    —— 是 SseEmitter / Publisher / text-event-stream？→ 原样返回
 *   2. 已经是响应   —— 是 BaseResponse？→ 原样返回（避免二次包装）
 *   3. String 特殊 —— 走 StringHttpMessageConverter？→ 手动序列化成 JSON
 *   4. 其余        —— 包成 BaseResponse.success
 * </pre>
 *
 * <p>为什么要放行第 1 条：SSE 是"一路往外吐数据"，一旦被包装成 {@code {code,message,data}}，
 * 流格式就被破坏，前端 EventSource 认不出来 —— 表现为"接口没反应"，而服务端可能不报错。
 * 判定由 {@link SseSupport#shouldBypassWrapping} 完成。
 *
 * <p>为什么 String 要特殊处理（第 3 条）：Spring 里 String 返回值走
 * {@link StringHttpMessageConverter}，绕过 Jackson 直接把字符串原样输出，
 * 不处理就变成"裸字符串"而非 JSON，所以要手动用 {@link ObjectMapper} 序列化并改写 Content-Type。
 *
 * <p>为什么限定 {@code basePackages = "com.hagent.controller"}：不限范围会连 Spring 内部
 * （错误页、actuator、静态资源）一起包，破坏它们的输出 —— 范围最小化。
 */
@RestControllerAdvice(basePackages = "com.hagent.controller")
public class GlobalResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    /** 用 Spring 容器管理的 ObjectMapper，而不是自己 new —— 保证序列化规则一致。 */
    private final ObjectMapper objectMapper;

    public GlobalResponseBodyAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 所有 Controller 返回值都参与包装。
        // 真正的"放行/不包装"判断在 beforeBodyWrite 里做，因为这里只有返回类型，拿不到实际返回的对象。
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {

        // ===== 1. 放行流式：这是流，绝不能包装 =====
        if (SseSupport.shouldBypassWrapping(body, selectedContentType)) {
            return body;
        }

        // ===== 2. 已经是统一响应：原样返回，避免二次包装 =====
        if (body instanceof BaseResponse<?>) {
            return body;
        }

        // ===== 3. String 特殊处理：StringHttpMessageConverter 会绕过 JSON =====
        if (selectedConverterType == StringHttpMessageConverter.class) {
            try {
                // 手动序列化成 JSON 并改写 Content-Type，否则前端会当纯文本。
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return objectMapper.writeValueAsString(BaseResponse.success(body));
            } catch (JacksonException e) {
                throw new IllegalStateException("响应序列化失败", e);
            }
        }

        // ===== 4. 其余：包成统一成功响应 =====
        return BaseResponse.success(body);
    }
}
