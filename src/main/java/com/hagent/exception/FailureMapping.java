package com.hagent.exception;

import com.hagent.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 一次失败分类的结果 —— 告诉调用方"该怎么回应、该怎么记日志"。
 *
 * <p>为什么不直接返回 {@link ErrorCode}：<b>对外的内容</b>只能是错误码里那句固定话术
 * （异常消息可能含 URL、SQL、内部主机名，直接返回就是信息泄露），
 * <b>对内的内容</b>还需要定位细节（HTTP 状态码、异常类名、根因类名）。
 * 两者放进同一个返回值，是为了让"对外话术"与"对内细节"在同一条规则里<b>同时决定</b> ——
 * 分成两次调用迟早出现"两处判断不一致"。
 *
 * @param errorCode  对外返回的错误码（含 HTTP 状态码与固定话术）
 * @param httpStatus 冗余保存一份 HTTP 状态码，便于测试直接断言、读规则时不必跳转
 * @param logDetail  <b>已脱敏</b>的日志详情：只放状态码 / 状态文本 / 异常与根因类名，
 *                   <b>不放上游响应体全文</b>（它可能回显请求内容）；原始堆栈由
 *                   {@code GlobalExceptionHandler} 的 {@code log.error(..., e)} 完整记录
 * @param operatorHint 给运维/开发看的<b>处置建议</b>：这条日志出现时该去查什么
 */
public record FailureMapping(
        ErrorCode errorCode,
        HttpStatus httpStatus,
        String logDetail,
        String operatorHint) {

    /** 由错误码构造（HTTP 状态码从错误码里取，避免两处不一致）。 */
    public static FailureMapping of(ErrorCode errorCode, String logDetail, String operatorHint) {
        return new FailureMapping(errorCode, errorCode.getHttpStatus(), logDetail, operatorHint);
    }

    /**
     * 未纳入分类的异常。
     *
     * <p>刻意<b>不</b>静默给一个"看起来合理"的错误码：那会让"分类器漏了一条规则"
     * 这件事<b>永远不被发现</b>。这里返回 {@code INTERNAL_ERROR} 并让
     * {@code GlobalExceptionHandler} 记 ERROR + 明确的"需补规则"提示，缺口在第一次出现时就暴露。
     */
    public static FailureMapping unclassified(Throwable e) {
        return new FailureMapping(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getHttpStatus(),
                "未纳入分类的异常：类型=" + e.getClass().getName(),
                "FailureMapper 中缺少针对该异常类型的规则，请补充一条 —— "
                        + "否则它会一直以『服务内部错误』对外返回，掩盖真实原因。");
    }
}
