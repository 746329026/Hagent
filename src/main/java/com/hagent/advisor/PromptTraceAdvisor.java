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
 * <h2>★ 参数取值依据</h2>
 *
 * <p><b>getOrder() = 0（最外层）</b>
 * <ul>
 *   <li>{@link org.springframework.core.Ordered} 的语义：<b>值越小越先执行</b>，
 *       而"先执行的切面在退出时最后完成"，因此 0 表示本切面处于调用链最外层。</li>
 *   <li>当前项目只有这一个切面，取值不影响任何输出 —— 这一点是实事求是的。</li>
 *   <li>它真正开始起作用是在<b>有第二个切面之后</b>：届时 order 决定
 *       "日志记到的是<b>注入前</b>的 prompt 还是<b>注入后</b>的 prompt"。
 *       比如 RAG 检索结果注入、记忆注入都是通过切面做的，
 *       日志在外层就看不到那些注入内容。</li>
 *   <li>选 0 的含义是：<b>保证日志是最外层、能看到最完整的请求</b>。
 *       将来若发现"看不到 RAG 注入的内容"，就把 order 调大往内层挪。</li>
 * </ul>
 *
 * <p><b>截断长度</b>：见 {@link AdvisorProperties#getMaxLogLength()}。
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
        // getContents() 会给出这条 prompt 里所有消息拼起来的完整内容，
        // 这正是"模型实际会收到什么"。
        log.info("[{}][{}] → 请求 | 消息数={} | 内容={}",
                ADVISOR_NAME, mode, prompt.getInstructions().size(), truncate(prompt.getContents()));
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
                ADVISOR_NAME, mode, costMillis, truncate(text));
    }

    /**
     * 截断长文本。
     *
     * <p>为什么要截断而不是全打：一次 RAG 调用可能带几千字检索结果，
     * 全量打进 INFO 日志会让日志文件暴涨，且把用户输入与内部文档内容大量写进日志
     * （参考实现正是这么做的，属于手册点名的"日志脱敏"问题）。
     * 需要看全文时用 {@code hagent.advisor.trace.log-full-content=true} 临时打开。
     */
    private String truncate(String text) {
        if (text == null) {
            return "<null>";
        }
        // 换行会让一条日志散成多行，排查时反而难读，所以先压成一行
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (properties.isLogFullContent()) {
            return oneLine;
        }
        int max = properties.getMaxLogLength();
        if (max > 0 && oneLine.length() > max) {
            return oneLine.substring(0, max) + "...[截断，共 " + oneLine.length() + " 字符]";
        }
        return oneLine;
    }

    // ========================================================================
    // Advisor 元信息
    // ========================================================================

    @Override
    public String getName() {
        return ADVISOR_NAME;
    }

    /**
     * 见类注释"参数取值依据"。0 = 最外层。
     *
     * <p>不用 {@code Ordered.HIGHEST_PRECEDENCE}（Integer.MIN_VALUE）而用 0：
     * 留出负数区间给"必须最先执行的基础设施切面"（如未来的鉴权、限流），
     * 0 是"业务切面的最外层"。
     */
    @Override
    public int getOrder() {
        return 0;
    }

    /** 便于将来在别处按类型判断，避免裸字符串比较。 */
    public static String advisorName() {
        return ADVISOR_NAME;
    }
}
