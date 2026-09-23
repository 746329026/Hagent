package com.hagent.controller;

import com.hagent.app.ChatAppService;
import com.hagent.common.BaseResponse;
import com.hagent.dto.ChatRequest;
import com.hagent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话接口 —— 阶段 1 的核心入口。
 *
 * <p><b>薄控制器原则</b>：只做三件事 ——
 * <ol>
 *   <li>接收并校验参数（{@code @Valid} 触发 ChatRequest 上的 {@code @NotBlank}）；</li>
 *   <li>调用 app 层；</li>
 *   <li>把结果包进统一响应返回。</li>
 * </ol>
 * 没有任何业务判断、没有循环、不直接接触模型。
 *
 * <p><b>统一响应的形态</b>：最终返回
 * <pre>
 *   {"code":0,"message":"success","data":{"reply":"..."}}
 * </pre>
 * 其中 data 就是 {@link ChatResponse}。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAppService chatAppService;

    /**
     * 同步对话。
     *
     * <p>POST 而非 GET：请求要带消息体（JSON），且消息可能较长。
     * 语义上"发起一次对话"是产生副作用（消耗 token、可能触发后续工具），
     * 用 POST 比 GET 更合适（GET 还受 URL 长度限制、会被缓存）。
     */
    @PostMapping
    public BaseResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return BaseResponse.success(chatAppService.chat(request.message()));
    }
}
