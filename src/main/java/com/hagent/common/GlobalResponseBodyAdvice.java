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
 * 统一响应包装器 —— 阶段 2 的核心。
 *
 * <p><b>它做什么</b>：在所有 Controller 方法返回之后、真正写给浏览器之前，
 * 把返回值包装成 {@code {code,message,data}}。于是业务代码不需要在每个接口手写
 * 包装 —— 这是"统一契约"的机制层落地。
 *
 * <p><b>beforeBodyWrite 里的四条分支，按顺序执行（顺序不能换）</b>：
 * <pre>
 *   1. 放行流式    —— 是 SseEmitter / Publisher / text-event-stream？→ 原样返回
 *   2. 已经是响应   —— 是 BaseResponse？→ 原样返回（避免二次包装）
 *   3. String 特殊 —— 走 StringHttpMessageConverter？→ 手动序列化成 JSON
 *   4. 其余        —— 包成 BaseResponse.success
 * </pre>
 *
 * <h2>★ 为什么要放行（第 1 条分支的完整依据）</h2>
 *
 * <p>流式返回（SSE）是"一路往外吐数据"，如果被本类包装成
 * {@code {code,message,data}}，流的格式就被破坏，前端 EventSource 认不出来 ——
 * 表现为"接口没反应"，而服务端可能不报错。
 *
 * <p>所以第 1 条分支调用 {@link SseSupport#shouldBypassWrapping}，
 * 判断"这个返回值是不是流"，是则原样返回、绝不包装。
 * 这是放行判定**第一次被真正调用**的地方。
 *
 * <h2>★ 为什么 String 要特殊处理（第 3 条分支）</h2>
 *
 * <p>Spring 里 String 的返回值走 {@link StringHttpMessageConverter}，
 * <b>绕过 Jackson 的 JSON 序列化</b>，直接把字符串原样输出。
 * 如果不处理，Controller 返回的 String 会变成一个"裸字符串"，而不是 JSON。
 * 所以这里手动用 {@link ObjectMapper} 序列化，并把 Content-Type 改成 JSON。
 *
 * <h2>★ 关键设计：只包 Controller，不包别的</h2>
 *
 * <p>{@code @RestControllerAdvice(basePackages = "com.hagent.controller")}
 * 把作用范围限制在 Controller 包。若不限制，会连 Spring 内部
 * （错误页、actuator、静态资源）一起包 —— 那会破坏它们的输出。
 * 这是"范围最小化"的落地。
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
        // 真正的"放行/不包装"判断在 beforeBodyWrite 里做（见下），
        // 因为这里拿不到"实际返回的对象"，只有返回类型。
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
                // 手动序列化成 JSON，并改写 Content-Type，
                // 否则前端会把它当纯文本而不是 JSON。
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
