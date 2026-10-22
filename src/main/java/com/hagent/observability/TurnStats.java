package com.hagent.observability;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

import java.util.UUID;

/**
 * 一轮对话的<b>账本</b> —— 边跑边记，收尾时产出一个不可变的 {@link TurnRecord}。
 *
 * <p>为什么必须是可变对象而不是攒局部变量：账要在<b>三处</b>被记（每次模型调用的次数 / usage /
 * 首 token、每次工具执行的耗时与次数、工作流规划），这三处跨了十几个方法、并且循环是<b>递归</b>的，
 * 而 Java 的 lambda 只能捕获 effectively-final 变量 —— 累积器必须"活"在递归之外。
 *
 * <p>与 {@code AgentPipeline.LoopState} 的分工：LoopState 是"这次对话的内容"（消息、文本、思考、步号），
 * 本类是"这一轮的账"（耗时、次数、token）—— 放一起会让工作流每步新建 LoopState 时把账本一起重置。
 *
 * <p>计数器不需要加锁：Reactor 同一条链上的 onNext/onComplete 是<b>串行</b>的（切换线程不代表并发），
 * 普通字段够用。唯一可能被两个线程同时碰的是 {@link #finish}（正常收尾一次、取消时
 * {@code doFinally} 一次），所以它 {@code synchronized} 且<b>幂等</b>：第一次算出来的就是最终结果。
 *
 * <p>只收 {@code Usage}、不碰 {@code ChatResponse}：这样"供应商耦合集中在
 * {@code textOf} / {@code reasoningOf} 两个取值方法"这条性质才守得住 —— 换模型时这里一个字都不用改。
 */
public final class TurnStats {

    /** 错误信息入库上限（与 {@code chat_turn.error_message VARCHAR(512)} 对齐）。 */
    private static final int ERROR_MESSAGE_MAX = 512;

    private final String turnId;
    private final String conversationId;
    private final String userId;
    private final String role;
    private final String mode;
    private final long startNanos;

    private long firstTokenNanos = -1;
    private long toolNanos;
    private int modelCalls;
    /** 其中<b>真的拿到 usage</b> 的调用次数；{@code modelCalls - usageCalls} 就是缺失数。 */
    private int usageCalls;
    private int toolCalls;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer planSteps;
    private String finishReason;
    private String answer;
    private String failureMessage;

    /** 已收尾的结果；非 null = 已经算过了（幂等的标志）。 */
    private TurnRecord finished;

    /**
     * @param conversationId 会话标识
     * @param userId         调用方标识（匿名时传 {@code UserContext.ANONYMOUS.userId()}）
     * @param role           角色名
     * @param mode           模式<b>线上名</b>（{@code chat} / {@code workflow}）
     */
    public TurnStats(String conversationId, String userId, String role, String mode) {
        this.turnId = UUID.randomUUID().toString();
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.mode = mode;
        this.startNanos = System.nanoTime();
    }

    /**
     * 轮次标识 —— <b>在轮次开始时就有值</b>，所以工具审计能在中途带上它；
     * 这也是"收尾时一次性 INSERT"能成立的前提。
     */
    public String turnId() {
        return turnId;
    }

    /**
     * 记一次模型调用的<b>开始</b>（chat 的每一步、工作流的规划与每一步都算）。
     *
     * <p>在"开始"就计数而不是等流完成：调用可能失败（连接断了、上游 5xx、客户端中途断开），
     * 而失败的那次同样花了钱。于是"没拿到 usage 的次数"是派生的（{@code modelCalls - usageCalls}）——
     * 失败的调用天然落在"没拿到"里，不需要第三个计数器，也就不会漂移。
     */
    public void noteModelCallStart() {
        modelCalls++;
    }

    /**
     * 记一次模型调用的<b>用量</b>（在这一调用完成时传最后一片的 usage）。
     *
     * <p>流式路径上 usage <b>只出现在收尾片</b>：每片都调会把同一个数累加 N 次（184 片会让成本虚高两个量级）。
     * {@code EmptyUsage} 与 {@code null} 一律视为"没拿到"：前者 {@code getPromptTokens()} 返回 0，
     * 按数值累加会把"没数据"记成"这轮花了 0 token"。
     */
    public void noteModelUsage(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        if (prompt == null && completion == null) {
            return;
        }
        usageCalls++;
        promptTokens = add(promptTokens, prompt);
        completionTokens = add(completionTokens, completion);
        totalTokens = add(totalTokens, usage.getTotalTokens());
    }

    /** 第一片 Token/Reasoning 到达（幂等：只有第一次生效）。 */
    public void noteFirstToken() {
        if (firstTokenNanos < 0) {
            firstTokenNanos = System.nanoTime();
        }
    }

    /**
     * 记一段工具执行。
     *
     * @param nanos 这一段的总耗时（框架的 {@code executeToolCalls} 是整体调用，
     *              一次可能执行多个工具，所以这里只有"批次"粒度）
     * @param calls 这一段执行了几个工具
     */
    public void noteToolPhase(long nanos, int calls) {
        toolNanos += Math.max(0, nanos);
        toolCalls += Math.max(0, calls);
    }

    /** 工作流计划步数（chat 不调用 → 落库为 NULL）。 */
    public void notePlanSteps(int steps) {
        if (steps > 0) {
            planSteps = steps;
        }
    }

    /** 收尾原因与完整回答（用于判定"一个字的回答都没有"）。 */
    public void noteFinal(String finishReason, String answer) {
        this.finishReason = finishReason;
        this.answer = answer;
    }

    /** 记一个<b>业务性</b>失败原因（例如工作流放弃）。先写的那个保留。 */
    public void noteFailure(String message) {
        if (failureMessage == null) {
            failureMessage = truncate(message);
        }
    }

    /** 记一个<b>异常</b>（转成可入库的一行）。 */
    public void noteError(Throwable error) {
        if (error == null) {
            return;
        }
        noteFailure(error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    /**
     * 收尾：产出一轮的完整账（<b>幂等</b>）。
     *
     * @param observed 调用方观察到的信号折算出来的结局：
     *                 {@code SUCCESS}（流正常完成）/ {@code FAILED}（onError）/ {@code CANCELLED}（取消）
     * @return 本轮唯一的那一条记录（第二次调用返回第一次的结果）
     */
    public synchronized TurnRecord finish(TurnStatus observed) {
        if (finished != null) {
            return finished;
        }
        long finishedNanos = System.nanoTime();
        long elapsedMs = Math.max(0, (finishedNanos - startNanos) / 1_000_000);
        Long firstTokenMs = firstTokenNanos < 0
                ? null : Math.max(0, (firstTokenNanos - startNanos) / 1_000_000);

        TurnStatus status = decide(observed);
        String errorMessage = status == TurnStatus.SUCCESS ? null : errorOf(status);
        int usageMissingCalls = Math.max(0, modelCalls - usageCalls);

        finished = new TurnRecord(turnId, conversationId, userId, role, mode, status,
                finishReason, elapsedMs, firstTokenMs, modelCalls, toolCalls,
                toolMs(), promptTokens, completionTokens, totalTokens, usageMissingCalls,
                planSteps, errorMessage);
        return finished;
    }

    /** 到目前为止本轮调了几次模型（日志用；落库走 {@link #finish}）。 */
    public int modelCalls() {
        return modelCalls;
    }

    /**
     * 到目前为止本轮花了多少毫秒 —— 给"← 响应"那行日志用。
     *
     * <p>与 {@link #finish} 是<b>同一个式子</b>：各算一遍就会出现"日志 7270ms、库里 7305ms"的漂移。
     */
    public long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /**
     * 整轮的用量合计 —— 给 {@code Final.usage} 用（它不再是"最后一次调用"的量）。
     *
     * <p>一次都没拿到时返回 {@link EmptyUsage} 而不是 {@code null}：与框架"没数据用 EmptyUsage 表示"
     * 的约定一致，日志里一眼能看出是"没数据"。
     */
    public Usage totalUsage() {
        if (totalTokens == null && promptTokens == null && completionTokens == null) {
            return new EmptyUsage();
        }
        return new DefaultUsage(promptTokens, completionTokens, totalTokens);
    }

    // 内部

    /** 判定矩阵 —— 见 {@link TurnStatus} 的类注释（全项目只有这一处实现）。 */
    private TurnStatus decide(TurnStatus observed) {
        if (observed == TurnStatus.CANCELLED) {
            return TurnStatus.CANCELLED;
        }
        if (observed == TurnStatus.FAILED) {
            return TurnStatus.FAILED;
        }
        if (failureMessage != null) {
            // 流程自己放弃了（工作流第 k 步失败）—— 用户拿到的是"未完成说明"，不是成功
            return TurnStatus.FAILED;
        }
        if (answer == null || answer.isBlank()) {
            // "跑完了"与"答出来了"不是一回事：模型只思考没作答时，回答是空的
            return TurnStatus.FAILED;
        }
        return TurnStatus.SUCCESS;
    }

    private String errorOf(TurnStatus status) {
        if (failureMessage != null) {
            return failureMessage;
        }
        if (status == TurnStatus.CANCELLED) {
            return "客户端断开（或 SSE 超时），本轮未完成";
        }
        return "本轮没有任何回答内容（模型未作答）";
    }

    private long toolMs() {
        return toolNanos / 1_000_000;
    }

    private static Integer add(Integer sum, Integer value) {
        if (value == null) {
            return sum;
        }
        return sum == null ? value : sum + value;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MESSAGE_MAX
                ? text : text.substring(0, ERROR_MESSAGE_MAX) + "…";
    }
}
