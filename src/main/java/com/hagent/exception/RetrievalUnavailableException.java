package com.hagent.exception;

import com.hagent.common.ErrorCode;

/**
 * 知识库检索<b>不可用</b> —— "这次没查到"与"这次查不了"必须用两件事表达。
 *
 * <h2>★ 为什么需要这个类型（一次真机事故换来的）</h2>
 *
 * <p>原来的做法是：检索失败 → 记一条 ERROR → <b>返回空列表</b>。
 * 于是在上层看来，"失败"与"没命中"完全一样：工具照常回一句
 * "知识库中没有找到与该问题相关的内容"，模型据此回答
 * "<b>知识库中未找到相关内容</b>"。
 *
 * <p>实测结果（阶段 10 联调）：由于检索一直抛异常（线程问题，见 {@code AgentPipeline}），
 * 每一次提问都得到"知识库未找到"，而<b>用户问的年假就在库里</b>。
 * 也就是说 —— <b>这个系统在诚实地撒谎</b>：它把系统故障说成了"资料不存在"。
 *
 * <h2>★ 为什么继承 {@link AgentException} 而不是 RuntimeException</h2>
 *
 * <p>它携带 {@link ErrorCode#RETRIEVAL_FAILED}。这个异常<b>正常情况下到不了 HTTP 层</b>
 * （工具会接住它，把"检索不可用"翻译成一句给模型看的话），
 * 但万一将来有人让它逃出去，{@code GlobalExceptionHandler} 会按
 * {@code 42003 / HTTP 500} 回给调用方 —— 而不是落进兜底的"服务内部错误"。
 * 顺带说明：<b>不需要</b>在 {@code FailureMapper} 里加规则 ——
 * 那个类只负责"模型调用失败"，它自己会明确拒绝非模型异常。
 *
 * <h2>★ 为什么"失败"要抛出，而"没命中"仍然返回空列表</h2>
 *
 * <p>这是本项目反复在讲的那条判据：<b>"失败"与"正常"同形，就必须分开</b>。
 * <pre>
 *   没命中 → return List.of()      ← 这是【结果】
 *   失败   → throw this            ← 这是【故障】，且原因要说得出
 * </pre>
 * 两件事走两条通道，上层才可能在<b>行为</b>上区分开（例如：故障时禁止模型说"知识库没有"）。
 */
public class RetrievalUnavailableException extends AgentException {

    /**
     * @param detail 人类可读的说明（建议带上查询串，便于日志里对应到具体那一次提问）
     * @param cause  原始异常（<b>必须保留</b>：它是"到底是 pgvector 还是 embedding"的唯一线索）
     */
    public RetrievalUnavailableException(String detail, Throwable cause) {
        super(ErrorCode.RETRIEVAL_FAILED, detail, cause);
    }
}
