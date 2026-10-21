package com.hagent.observability;

/**
 * 一轮对话的<b>结局</b> —— 只有三个值，因为"这一轮到底算成没成"只有三种答案。
 *
 * <h2>★ 为什么没有 {@code RUNNING}</h2>
 *
 * <p>本项目<b>不在轮次开始时写一行、收尾时再 UPDATE</b>，而是收尾时<b>一次性 INSERT</b>
 * （见 {@code db/chat_turn.sql} 的"为什么主键是 UUID"）。
 * 于是不存在"进行中的行"，也就不需要 {@code RUNNING} 这个状态。
 *
 * <p>代价写在诚实清单里：<b>进程被 kill 的轮次不会留下任何记录</b>。
 * 换来的是零 UPDATE、零僵尸行、零清理任务 —— 而"进程被杀"这件事，日志本来就是更好的证据。
 *
 * <h2>★★ 判定矩阵（实现在 {@link TurnStats#finish} 里，只此一处）</h2>
 *
 * <pre>
 *   信号 CANCEL（客户端断开 / SSE 超时）        → CANCELLED
 *   信号 ON_ERROR（模型/工具/框架抛错）          → FAILED
 *   工作流放弃（finish_reason = stepFailed）     → FAILED   ← 回答里写着"未完成"，不能算成功
 *   一轮下来一个字都没有（answer 为空）          → FAILED   ← "跑完了"与"答出来了"不是一回事
 *   其余（含 finish_reason = maxSteps）          → SUCCESS
 * </pre>
 *
 * <p><b>为什么 {@code maxSteps} 算 SUCCESS</b>：那是我们自己的<b>防御出口</b>
 * （模型绕圈时用已累积内容收尾），回答可能被截断但确实产出了。
 * 把它并入 FAILED 会让"截断率"与"故障率"混在一个数里；
 * 分开的正确方式是看 {@code finish_reason} —— <b>状态与原因是两个维度</b>，不压平成一个布尔。
 *
 * <p><b>为什么 CANCELLED 不并入 FAILED</b>：用户关页面是正常操作，不是系统故障。
 * 混进去会让成功率看起来无缘无故地差，而且掩盖真正的失败。
 */
public enum TurnStatus {

    /** 正常收尾：有回答，且流程没有放弃。 */
    SUCCESS,

    /** 失败：抛错，或流程自己放弃了（工作流某步失败），或一个字的回答都没有。 */
    FAILED,

    /** 中断：客户端断开或 SSE 超时，本轮没有走完（<b>不是故障</b>）。 */
    CANCELLED
}
