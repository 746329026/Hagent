package com.hagent.exception;

import com.hagent.common.ErrorCode;

/**
 * 知识库检索<b>不可用</b> —— "这次没查到"与"这次查不了"必须用两件事表达。
 *
 * <p>曾把检索失败记一条 ERROR 后<b>返回空列表</b>，于是在上层看来"失败"与"没命中"完全一样：
 * 工具照常回一句"知识库中没有找到相关内容"，模型据此回答"知识库中未找到"——
 * 检索一直抛异常时，每次提问都得到"未找到"，而用户问的内容就在库里。
 * 等于把系统故障说成了"资料不存在"。
 *
 * <p>继承 {@link AgentException} 以携带 {@link ErrorCode#RETRIEVAL_FAILED}：正常情况下它到不了 HTTP 层
 * （工具会接住它，把"检索不可用"翻译成一句给模型看的话），但万一逃出去，
 * {@code GlobalExceptionHandler} 会按 {@code 42003 / HTTP 500} 回给调用方，
 * 而不是落进兜底的"服务内部错误"。也<b>不需要</b>在 {@code FailureMapper} 加规则
 * （那里只负责模型调用失败）。
 *
 * <p>"失败"抛出、"没命中"仍返回空列表：{@code 没命中 → return List.of()} 是【结果】，
 * {@code 失败 → throw this} 是【故障】；两件事走两条通道，上层才能在行为上区分
 * （例如：故障时禁止模型说"知识库没有"）。
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
