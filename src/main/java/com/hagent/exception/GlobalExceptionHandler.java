package com.hagent.exception;

import com.hagent.common.BaseResponse;
import com.hagent.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 异常出口的唯一收口点。
 *
 * <p><b>它让"业务代码不写 try-catch"成为可能</b>：
 * 业务代码只管抛异常（或靠 @Valid 触发框架的校验异常），
 * 统一在这里翻译成 BaseResponse 与正确的 HTTP 状态码。
 *
 * <p><b>设计要点（每个都有对应依据）</b>：
 * <ol>
 *   <li><b>HTTP 状态码与业务码联动</b>：状态码取自 ErrorCode，
 *       避免"所有异常都返回 200，前端只能靠 code 判断"。</li>
 *   <li><b>4xxxx 记 WARN，5xxxx 记 ERROR</b>：参数错误是调用方的问题，
 *       不该污染错误告警；只有服务端错误才需要人介入。</li>
 *   <li><b>兜底异常绝不外泄细节</b>：真实堆栈只进日志，对外统一话术。
 *       异常消息常含 SQL、路径、内部主机名，直接返回给前端就是信息泄露。</li>
 * </ol>
 *
 * <p><b>为什么在阶段 1 就补上（而不是等"统一契约"阶段）</b>：
 * 实测发现 —— 空消息被 @NotBlank 正确拦截后，返回的是
 * {"timestamp":"...","status":400,"error":"Bad Request","path":"/api/chat"}
 * 这是 Spring 的默认错误结构，不是我们的 {code,message,data} 契约。
 * 一个接口已经对外，异常却走另一套格式 = 契约在此处断裂，必须补上。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：错误码已明确，直接使用。 */
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<BaseResponse<Void>> handleAgentException(AgentException e) {
        ErrorCode code = e.getErrorCode();
        if (code.getCode() >= 50000) {
            log.error("[业务异常-服务端] code={} msg={}", code.getCode(), e.getMessage(), e);
        } else {
            log.warn("[业务异常-客户端] code={} msg={}", code.getCode(), e.getMessage());
        }
        return ResponseEntity.status(code.getHttpStatus())
                .body(BaseResponse.failure(code));
    }

    // ==================== 参数校验类（4xxxx）====================

    /** @Valid 校验 @RequestBody 失败 —— 本项目空消息校验走的就是这里。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[参数校验失败] {}", detail);
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INVALID_PARAM, detail));
    }

    /** @Validated 校验方法参数失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("[参数约束失败] {}", detail);
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INVALID_PARAM, detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<BaseResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[缺少参数] {}", e.getParameterName());
        return ResponseEntity.status(ErrorCode.MISSING_PARAM.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.MISSING_PARAM, e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<BaseResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[参数类型不匹配] name={} value={}", e.getName(), e.getValue());
        String required = e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName();
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INVALID_PARAM, e.getName() + " 期望类型 " + required));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[请求体不可读] {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INVALID_PARAM, "请求体不是合法 JSON"));
    }

    // ==================== 兜底（5xxxx）====================

    /**
     * 最后一道防线：任何未被显式处理的异常。
     *
     * <p>★ 绝不把 e.getMessage() 返回给前端 —— 它可能包含 SQL 片段、
     * 文件绝对路径、内部主机名。真实原因只进日志，对外统一话术。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleUnexpected(Exception e) {
        log.error("[未预期异常] 类型={} —— 需要补一条显式异常处理分支", e.getClass().getName(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INTERNAL_ERROR));
    }
}