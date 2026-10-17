package com.hagent.controller;

import com.hagent.app.ConversationQueryService;
import com.hagent.dto.SessionDtos.RenameRequest;
import com.hagent.dto.SessionDtos.SessionDetail;
import com.hagent.dto.SessionDtos.SessionSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话的<b>列表 / 历史回放 / 改名</b>（阶段 10 新增）。
 *
 * <pre>
 *   GET   /chat/sessions              侧栏：我有哪些会话（最近的在前）
 *   GET   /chat/sessions/{id}/messages 点开一个会话：元信息 + 全部历史
 *   PATCH /chat/sessions/{id}         改名
 * </pre>
 *
 * <h2>★ 为什么单独一个 Controller，不塞进 {@link ChatController}</h2>
 *
 * <p>两者职责不同，而且<b>失败模式完全不同</b>：
 * <ul>
 *   <li>{@link ChatController} 是<b>写 + 调模型</b>：会花 token、会等几十秒、会返回 SSE 流；</li>
 *   <li>本类<b>不碰模型</b>：只读两张表，纳秒级返回。</li>
 * </ul>
 * 合在一起会让"对话接口"里混进一堆与模型无关的读接口，后人很难看出哪条路径在烧钱。
 *
 * <h2>★ 路径前缀选 {@code /chat/sessions} 的理由</h2>
 *
 * <p>{@code WebConfig} 把身份解析拦截器注册在 {@code /chat/**} 上，
 * 所以会话接口<b>天然在鉴权覆盖范围内</b> —— 不需要再去改拦截器配置。
 * 若另起一个 {@code /sessions} 前缀，就会多出一条"忘了加拦截器"的漏网路径。
 *
 * <h2>★ 返回值不显式包 {@code BaseResponse}</h2>
 *
 * <p>{@code GlobalResponseBodyAdvice} 会把 Controller 的返回值统一包成
 * {@code {code,message,data}}。这里返回裸 DTO 就是对的 ——
 * 只有需要返回<b>业务错误码</b>时才显式构造 {@code BaseResponse}，
 * 而错误路径由 {@code AgentException} + 全局异常处理器承担（{@code SESSION_NOT_FOUND} → 404）。
 */
@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationQueryService conversationQueryService;

    /**
     * 列出会话，最近的在前。
     *
     * @param limit 最多几条，默认 50；{@code <= 0} 时服务层用默认值。
     *              <b>有上限</b>是刻意的：侧栏一次只显示前 N 个，
     *              把几千个会话全序列化再传给浏览器是"没有消费者的数据"。
     */
    @GetMapping
    public List<SessionSummary> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return conversationQueryService.listSessions(limit);
    }

    /**
     * 一个会话的完整历史（元信息 + 全部消息，<b>不截断</b>）。
     *
     * <p>路径用 {@code /messages} 而不是直接 {@code GET /chat/sessions/{id}}：
     * 这样将来加"只取元信息"的接口时不必换路径，语义也更准（这个响应的主体是消息）。
     *
     * <p>会话不存在 → 404 + {@code SESSION_NOT_FOUND}；
     * 存在但没有消息 → 200 + 空列表。<b>这两件事对前端完全不同</b>
     * （前者该跳回首页，后者该显示一个空对话），所以必须靠会话目录区分，不能只看消息表。
     */
    @GetMapping("/{conversationId}/messages")
    public SessionDetail messages(@PathVariable String conversationId) {
        return conversationQueryService.detail(conversationId);
    }

    /**
     * 改名。
     *
     * <p>用 {@code PATCH} 而不是 {@code PUT}：改的是会话的一个字段（标题），
     * 不是"用新对象整体替换会话"。{@code PUT} 会让人以为提交体就是整个会话。
     *
     * <p>为什么需要它：标题默认取第一句用户消息，而它经常不是一个好名字
     * （"在吗"、"这个怎么弄"）。用户应当能改 —— 而改完<b>不能再被下一句话覆盖回去</b>
     * （这条由 {@code ConversationCatalog#touch} 的契约保证：已存在的会话不改标题）。
     */
    @PatchMapping("/{conversationId}")
    public SessionSummary rename(@PathVariable String conversationId,
                                 @Valid @org.springframework.web.bind.annotation.RequestBody RenameRequest request) {
        return conversationQueryService.rename(conversationId, request.title());
    }
}
