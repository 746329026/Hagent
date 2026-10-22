package com.hagent.exception;

import com.hagent.common.BaseResponse;
import com.hagent.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 异常出口的唯一收口点。
 *
 * <p>它让"业务代码不写 try-catch"成为可能：业务代码只管抛异常（或靠 @Valid 触发框架校验异常），
 * 这里统一翻译成 BaseResponse 与正确的 HTTP 状态码。
 *
 * <p>四条设计要点（每条都有对应后果）：
 * <ol>
 *   <li><b>HTTP 状态码与业务码联动</b>：状态码取自 {@link ErrorCode}，
 *       避免"所有异常都返回 200，前端只能靠 code 判断"。</li>
 *   <li><b>4xxxx 记 WARN，5xxxx 记 ERROR</b>：参数错误是调用方的问题，
 *       不该污染错误告警；只有服务端错误才需要人介入。</li>
 *   <li><b>兜底异常绝不外泄细节</b>：异常消息常含 SQL、路径、内部主机名，直接返回就是信息泄露。</li>
 *   <li><b>上游失败不与本地故障混用错误码</b>：模型提供方的失败走 {@link FailureMapper} 单独分类，
 *       一律落进 {@code 50000} 会让客户端误重试、运维误判方向。</li>
 * </ol>
 *
 * <p>分工：本类只做<b>记日志</b>与<b>组织响应</b>；"该映射成哪个错误码"的规则全部在
 * {@link FailureMapper}（纯函数，可单测），新增一种失败只需改一处。
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

    // ===== 参数校验类（4xxxx）=====

    /** @Valid 校验 @RequestBody 失败 —— 空消息校验走的就是这里。 */
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

    // ===== 路由与协议级错误（4xxxx）=====

    /**
     * 访问了不存在的接口路径 → <b>404，而不是 500</b>。
     *
     * <p>Spring Boot 3.2+ 起"静态资源找不到"抛 {@link NoResourceFoundException}；
     * 不显式处理它就落进兜底的 {@code Exception.class}，任何拼错的 URL 都返回
     * {@code 50000 服务内部错误}。后果：调用方以为服务崩了去重试或告警
     * （重试一个不存在的路径永远不会成功）、每条 404 记一次 ERROR 埋掉真实故障、
     * 分不清"我调错了"与"服务坏了"。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        // 记 WARN 而不是 ERROR：路由不存在是调用方的问题，不该污染服务端告警；
        // 方法 + 路径足够定位，不需要堆栈。
        log.warn("[接口不存在] {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity.status(ErrorCode.API_NOT_FOUND.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.API_NOT_FOUND));
    }

    /**
     * HTTP 方法不被支持 → <b>405，而不是 500</b>（典型场景：浏览器地址栏访问 POST 接口，只能发 GET）。
     *
     * <p>与 404 同属同一类缺口：<b>Spring MVC 的协议级异常默认落进兜底，被报成"服务内部错误"</b>。
     * 同类缺口重复出现即机制化，所以 404 / 405 / 415 一并收口。
     *
     * <p>必须回 {@code Allow} 响应头：这是 HTTP 规范对 405 的硬性要求，告诉调用方
     * "这个路径到底接受哪些方法"；没有它调用方只知道错了、不知道该怎么改 ——
     * 这是 405 相对 500 的核心价值。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        log.warn("[请求方法不支持] 收到={} 该接口只接受={}", e.getMethod(),
                supported == null ? "未知" : supported);

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        // allow(...) 收 HttpMethod 变参而非 String，故用 getSupportedHttpMethods()（Set），
        // 而不是返回 String[] 的 getSupportedMethods()。
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(HttpMethod[]::new));
        }
        return builder.body(BaseResponse.failure(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 请求体格式（Content-Type）不被接受 → <b>415，而不是 500</b>（如该发 application/json 却发了表单）。
     *
     * <p>同属"协议级错误被报成服务端故障"一类；同样回 {@code Accept} 头告知该用什么格式
     * （与 405 回 {@code Allow} 同理）。
     */
    @ExceptionHandler(HttpMediaTypeException.class)
    public ResponseEntity<BaseResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeException e) {
        log.warn("[请求格式不支持] {} 支持的格式={}", e.getMessage(), e.getSupportedMediaTypes());

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.getHttpStatus());
        List<MediaType> supported = e.getSupportedMediaTypes();
        // HeadersBuilder 没有 accept(...) 方法，只能用 headers(Consumer<HttpHeaders>) 设。
        if (supported != null && !supported.isEmpty()) {
            builder.headers(headers -> headers.setAccept(supported));
        }
        return builder.body(BaseResponse.failure(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED));
    }

    // ===== 模型调用失败（50xxx 中的上游段）=====

    /**
     * 模型调用失败 —— 上游返回错误状态码，或请求根本没发出去。
     *
     * <p>必须 catch 四种异常（只 catch 前两种时端到端验证失败）：底层错误响应；
     * 请求没发出去（{@code WebClientRequestException} 不是前者子类，容易漏）；
     * 以及框架重试层包装后的 {@code NonTransientAiException} / {@code TransientAiException}
     * （实际被抛出的就是它们）。
     *
     * <p>更细的分段（凭据 / 限流 / 上游挂掉 / 超时）与"为什么 502 而不是 500"见
     * {@link FailureMapper} 与 {@link ErrorCode}；本方法只负责记日志 + 组织响应。
     */
    @ExceptionHandler({WebClientResponseException.class, WebClientRequestException.class,
            NonTransientAiException.class, TransientAiException.class})
    public ResponseEntity<BaseResponse<Void>> handleModelCallFailure(Exception e) {
        FailureMapping mapping = FailureMapper.map(e);

        // 记 ERROR 并带上原始堆栈 —— 脱敏只针对对外响应，日志必须完整，
        // 否则排查时只剩一句"模型调用失败"，等于没记。
        log.error("[模型调用失败] {} —— {}", mapping.logDetail(), mapping.operatorHint(), e);

        return ResponseEntity.status(mapping.httpStatus())
                .body(BaseResponse.failure(mapping.errorCode()));
    }

    // ===== 兜底（5xxxx）=====

    /**
     * 最后一道防线：任何未被显式处理的异常。
     *
     * <p>绝不把 {@code e.getMessage()} 返回给前端 —— 它可能含 SQL 片段、文件绝对路径、内部主机名。
     * 真实原因只进日志，对外统一话术。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleUnexpected(Exception e) {
        log.error("[未预期异常] 类型={} —— 需要补一条显式异常处理分支", e.getClass().getName(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.INTERNAL_ERROR));
    }
}