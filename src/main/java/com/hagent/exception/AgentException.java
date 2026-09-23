package com.hagent.exception;

import com.hagent.common.ErrorCode;
import lombok.Getter;

/**
 * Hagent 业务异常基类。
 *
 * <p>三个刻意的设计决定：
 * <ol>
 *   <li><b>继承 RuntimeException（非受检）</b>：业务异常必须能穿透多层调用栈
 *       直达全局处理器，不需要在每层 throws 声明。</li>
 *   <li><b>携带 ErrorCode 而非裸字符串</b>：错误码是契约，字符串只是描述。
 *       只有携带枚举，才能被前端分支、被监控统计。</li>
 *   <li><b>业务代码只管抛</b>：包装成统一响应是全局处理器的唯一职责，
 *       这是"业务代码不再写 try-catch"的落地方式。</li>
 * </ol>
 */
@Getter
public class AgentException extends RuntimeException {

    private final ErrorCode errorCode;

    public AgentException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public AgentException(ErrorCode errorCode, String detail) {
        super(detail == null || detail.isBlank()
                ? errorCode.getMessage()
                : errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    public AgentException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail == null || detail.isBlank()
                ? errorCode.getMessage()
                : errorCode.getMessage() + ": " + detail, cause);
        this.errorCode = errorCode;
    }

    /** 免去调用方 instanceof 判断，直接构造带原因的异常。 */
    public static AgentException of(ErrorCode errorCode, Throwable cause) {
        return new AgentException(errorCode, null, cause);
    }
}