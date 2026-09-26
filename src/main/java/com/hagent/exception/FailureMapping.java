package com.hagent.exception;

import com.hagent.common.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 一次失败分类的结果 —— 告诉调用方"该怎么回应、该怎么记日志"。
 *
 * <h2>为什么不直接返回 {@link ErrorCode}</h2>
 *
 * <p>因为"回应给客户端的内容"和"记进日志的内容"<b>必须分开</b>：
 * <ul>
 *   <li>对外：只能是 {@link ErrorCode} 里那句固定话术 —— 异常消息可能含
 *       URL、SQL、内部主机名，直接返回就是信息泄露；</li>
 *   <li>对内：需要足够定位问题的细节（HTTP 状态码、异常类名、根因类名）。</li>
 * </ul>
 * 把两者放进同一个返回值，是为了让"对外话术"和"对内细节"在同一条规则里被<b>同时决定</b> ——
 * 若分成两次调用（先取错误码、再取日志文本），迟早出现"两处判断不一致"。
 *
 * <h2>字段说明</h2>
 *
 * @param errorCode  对外返回的错误码（含 HTTP 状态码与固定话术）
 * @param httpStatus 冗余保存一份 HTTP 状态码，方便测试直接断言、也便于阅读规则时不跳转
 * @param logDetail  <b>已脱敏</b>的日志详情。只放状态码 / 状态文本 / 异常与根因类名。
 *                   <b>不放上游响应体全文</b>——它可能回显请求内容。
 *                   原始堆栈由 {@code GlobalExceptionHandler} 的 {@code log.error(..., e)} 完整记录
 * @param operatorHint 给运维/开发看的<b>处置建议</b>：这条日志出现时该去查什么。
 *                     它的价值在于把"我该怎么排查"从人脑记忆变成代码里的说明
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
     * <p>刻意<b>不</b>静默给一个"看起来合理"的错误码：那样会让"分类器漏了一条规则"
     * 这件事<b>永远不被发现</b>。这里返回 {@code INTERNAL_ERROR} 并让
     * {@code GlobalExceptionHandler} 记 ERROR + 明确的"需补规则"提示，
     * 使缺口在第一次出现时就暴露。
     */
    public static FailureMapping unclassified(Throwable e) {
        return new FailureMapping(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getHttpStatus(),
                "未纳入分类的异常：类型=" + e.getClass().getName(),
                "FailureMapper 中缺少针对该异常类型的规则，请补充一条 —— "
                        + "否则它会一直以『服务内部错误』对外返回，掩盖真实原因。");
    }
}
