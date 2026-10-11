package com.hagent.advisor;

import com.hagent.config.AdvisorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * 调用链日志切面 —— 让"调模型"这件事**从黑盒变成可观测**。
 *
 * <p><b>它解决什么问题</b>：在没有这个切面之前，一次模型调用是看不见的 ——
 * 发出去的 prompt 是什么、模型实际回了什么、花了多久，全都不知道，出问题只能猜。
 * 本切面把这三样都记下来。
 *
 * <h2>★ 结构决策：为什么实现两个接口，而不是一个 BaseAdvisor</h2>
 *
 * <p>Spring AI 给了三种写法：
 * <pre>
 *   BaseAdvisor    —— 一个类同时管 call 与 stream
 *   CallAdvisor    —— 只管非流式
 *   StreamAdvisor  —— 只管流式
 * </pre>
 *
 * <p>本项目选 <b>CallAdvisor + StreamAdvisor 两个接口</b>，因为两种模式要的行为不同：
 * <ul>
 *   <li><b>非流式</b>：时序简单 —— 调用前记请求，调用返回后记响应，
 *       <b>两次日志都在真实时间点发生</b>。</li>
 *   <li><b>流式</b>：返回的是 {@link Flux}，数据是一小片一小片来的。
 *       如果在流"刚被创建"时就记响应，拿到的只是一个<b>还没开始流的对象</b>，
 *       日志里会是空的 —— 没有任何意义。</li>
 * </ul>
 *
 * <p>所以流式必须<b>等流读完之后再记</b>。为此使用
 * {@link ChatClientMessageAggregator}：它会订阅这个流，把逐片的内容聚合起来，
 * 等流结束时把聚合结果回调给我们。
 * <b>代价是它要把整段响应攒在内存里</b>——对一次对话来说完全可接受。
 *
 * <h2>★ 本切面记的是哪一版 prompt（阶段 6 后半修正的一处认知错误）</h2>
 *
 * <p><b>它在最外层，因此它记到的是【注入前】的 prompt</b> ——
 * 只有 system（若有）与本次用户消息，看不到历史注入与检索片段。
 *
 * <p>这是由切面链的转发方向决定的：{@code DefaultAroundAdvisorChain.nextCall}
 * 从队列里 pop 出下一个切面，把 <b>request 的新版本</b>继续往下交 ——
 * <b>内层的修改不会回流给外层</b>。所以外层切面在 before 侧看到的，
 * 永远是"还没被内层加工过"的那一版。
 *
 * <p>⚠️ <b>修正记录</b>：本类与 {@code AiConfig}、{@code ConversationMemoryAdvisor}
 * 的旧注释曾写着"order=0 在最外层，所以能看到注入后的完整 prompt"，
 * 方向恰好是反的。当时没有发现问题，是因为项目只有一个业务切面，
 * 而记忆切面的自身日志（{@code [记忆] 注入历史 N 条}）也能证明"注入发生了"，
 * 于是没人去核对"这行日志里的消息数对不对"。
 *
 * <p><b>现在两条日志各有分工</b>：
 * <pre>
 *   [PromptTraceAdvisor]     请求进链时的样子（≈ 用户原始输入）→ 确认"入口是干净的"
 *   [PromptAssemblyAdvisor]  模型真正收到的 prompt              → 确认"注入生效了"
 * </pre>
 * 后者的 order 是 {@code MIN+250}，即"最后一个改 prompt 的人"，
 * 所以它记到的才是模型真正收到的那一份。
 *
 * <h2>★ 参数取值依据</h2>
 *
 * <p><b>getOrder() = {@link AdvisorOrders#OUTER_TRACE}（{@code Integer.MIN_VALUE + 100}）</b>
 * <ul>
 *   <li>{@link org.springframework.core.Ordered} 的语义：<b>值越小越先执行</b>，
 *       而"先执行的切面在退出时最后完成"，因此取最小值表示本切面处于调用链最外层。</li>
 *   <li><b>为什么必须最外层</b>：它测的耗时（{@code after} 侧）要覆盖链上其它所有切面的开销 ——
 *       最外层的 after 最后执行，所以它量到的才是"这次请求花了多久"。</li>
 *   <li><b>为什么取 {@code MIN_VALUE + 100} 而不是 0</b>：
 *       框架的工具循环 {@code ToolCallingAdvisor} 在 {@code MIN+300}，
 *       它内层的切面会被跑 N 次（N = 工具轮数）。
 *       本项目要让所有自定义切面待在循环<b>之外</b>，所以统一挪到负数区间，
 *       详见 {@link AdvisorOrders}。
 *       <br>（旧注释写的是"留出负数区间给鉴权、限流"—— 那个理由已经作废：
 *       鉴权最终走的是<b>拦截器</b>而不是切面，理由见 {@code UserContextInterceptor}。）</li>
 * </ul>
 *
 * <p><b>截断长度</b>：见 {@link AdvisorProperties#getMaxLogLength()}，
 * 格式化规则在 {@link PromptLogFormat}（与 {@code PromptAssemblyAdvisor} 共用）。
 *
 * <h2>★ 与参考实现的区别（有意为之）</h2>
 *
 * <p>参考实现在 before 里把"原始用户输入"写进 {@code context}，
 * 供后序切面从 context 里读出来打印。这形成了一条<b>隐式的跨切面耦合</b>：
 * <ul>
 *   <li>context 的 key 是一个裸字符串，两个类靠人工对齐（拼错不会报错）；</li>
 *   <li>如果读取方单独注册、写入方没注册，读出来永远是 null，
 *       <b>日志里悄悄丢失信息，却不报任何错</b>。</li>
 * </ul>
 *
 * <p>本实现<b>不依赖 context 传递</b>：请求本来就在手里（{@code request.prompt()}），
 * 直接取即可，从根上消除这种隐式耦合。
 */
@Slf4j
public class PromptTraceAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String ADVISOR_NAME = "PromptTraceAdvisor";

    private final AdvisorProperties properties;

    public PromptTraceAdvisor(AdvisorProperties properties) {
        this.properties = properties;
    }

    // ========================================================================
    // 非流式：时序简单，before / after 都是真实时间点
    // ========================================================================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        logRequest("call", request);

        ChatClientResponse response = chain.nextCall(request);

        long cost = System.currentTimeMillis() - start;
        logResponse("call", response, cost);
        return response;
    }

    // ========================================================================
    // 流式：必须等流读完再记，否则记到的是"还没开始流的对象"
    // ========================================================================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        logRequest("stream", request);

        Flux<ChatClientResponse> stream = chain.nextStream(request);

        // 聚合器会订阅流、把逐片内容攒起来，结束时回调我们；
        // 它把处理后的流原样返回，因此不影响下游对流的消费。
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                stream,
                aggregated -> logResponse("stream", aggregated, System.currentTimeMillis() - start));
    }

    // ========================================================================
    // 日志记录
    // ========================================================================

    private void logRequest(String mode, ChatClientRequest request) {
        if (!properties.isEnabled()) {
            return;
        }
        var prompt = request.prompt();
        // 注意：直接取用 request.prompt()，不经过 context 传递 —— 避免隐式耦合。
        // getContents() 会给出这条 prompt 里所有消息拼起来的完整内容。
        //
        // ★ 本切面在 MIN+100（最外层），所以这里看到的是【注入前】的 prompt ——
        //   只有 system（若有）与本次用户消息，还没有历史与检索片段。
        //   原因见类注释"★ 本切面记的是哪一版 prompt"。
        log.info("[{}][{}] → 请求 | 消息数={} | 内容={}",
                ADVISOR_NAME, mode, prompt.getInstructions().size(),
                PromptLogFormat.truncate(prompt.getContents(), properties));
    }

    private void logResponse(String mode, ChatClientResponse response, long costMillis) {
        if (!properties.isEnabled()) {
            return;
        }
        String text = null;
        if (response != null && response.chatResponse() != null) {
            text = response.chatResponse().getResult().getOutput().getText();
        }
        log.info("[{}][{}] ← 响应 | 耗时={}ms | 内容={}",
                ADVISOR_NAME, mode, costMillis, PromptLogFormat.truncate(text, properties));
    }

    /**
     * 截断与单行化统一走 {@link PromptLogFormat}。
     *
     * <p>原先这里是本类的私有方法，阶段 6 后半抽出去了 —— 因为
     * {@code PromptAssemblyAdvisor} 也要打 prompt 文本，两处必须用同一条规则
     * （否则"一处改了另一处没改"会让两条日志口径分叉，而排查时正是靠对比它们）。
     */
    private String truncate(String text) {
        return PromptLogFormat.truncate(text, properties);
    }

    // ========================================================================
    // Advisor 元信息
    // ========================================================================

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    /**
     * 见类注释"★ 参数取值依据"。
     *
     * <p>取值来自 {@link AdvisorOrders#OUTER_TRACE}，而不是裸数字 ——
     * order 会进入框架的 observation 标签（见 {@link AdvisorOrders} 的类注释），
     * 裸数字会让监控里出现一个没人看得懂的量。
     */
    @Override
    public int getOrder() {
        return AdvisorOrders.OUTER_TRACE;
    }

    /** 便于将来在别处按类型判断，避免裸字符串比较。 */
    public static String advisorName() {
        return ADVISOR_NAME;
    }
}
