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
 *   <li><b>上游失败不与本地故障混用错误码</b>：模型提供方的失败走
 *       {@link FailureMapper} 单独分类（凭据 / 限流 / 上游挂掉 / 超时），
 *       而不是一律落进 {@code 50000 服务内部错误} —— 后者会让客户端误重试、运维误判方向。</li>
 * </ol>
 *
 * <p><b>分工</b>：本类只做两件事 —— <b>记日志</b>与<b>组织响应</b>；
 * "该映射成哪个错误码"的规则全部集中在 {@link FailureMapper}（纯函数，可单测）。
 * 这样新增一种失败只需改一处，且能被测试覆盖。
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

    // ==================== 路由不存在（4xxxx）====================

    /**
     * 访问了不存在的接口路径 → <b>404，而不是 500</b>。
     *
     * <p><b>★ 这条分支是实测发现的既有缺陷</b>：Spring Boot 3.2+ 起，
     * "静态资源找不到"会抛 {@link NoResourceFoundException}。
     * 在本类补这条分支之前，它<b>落进了兜底的 {@code Exception.class}</b>，
     * 于是任何拼错的 URL 都返回：
     * <pre>
     *   HTTP 500  {"code":50000,"message":"服务内部错误"}
     * </pre>
     * 危害有三条：
     * <ol>
     *   <li><b>误导调用方</b>：以为服务崩了，于是重试或触发告警 ——
     *       而重试一个不存在的路径永远不会成功；</li>
     *   <li><b>污染错误日志</b>：每条 404 都记一次 ERROR 并带完整堆栈，
     *       真正的服务端故障会被这些噪声埋掉；</li>
     *   <li><b>掩盖接口下线</b>：调用方分不清"我调错了"和"服务坏了"。</li>
     * </ol>
     *
     * <p><b>值得记录的一点</b>：这个缺陷是<b>兜底处理器自己报出来的</b> ——
     * 它每次都会打印"类型=xxx —— 需要补一条显式异常处理分支"。
     * 这正是那条设计的目的：让"未分类的异常"可被发现，而不是静默返回 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleNoResourceFound(NoResourceFoundException e) {
        // 记 WARN 而不是 ERROR：路由不存在是调用方的问题，不该污染服务端告警。
        // 方法 + 路径足够定位（谁调了哪个不存在的地址），不需要堆栈。
        log.warn("[接口不存在] {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity.status(ErrorCode.API_NOT_FOUND.getHttpStatus())
                .body(BaseResponse.failure(ErrorCode.API_NOT_FOUND));
    }

    /**
     * HTTP 方法不被支持 → <b>405，而不是 500</b>。
     *
     * <p><b>★ 这条分支同样是被兜底处理器报出来的</b>，触发场景极其常见：
     * 在浏览器地址栏访问一个 <b>POST 接口</b>（浏览器只能发 GET），
     * 于是得到 {@code HTTP 500 {"code":50000,"message":"服务内部错误"}} ——
     * 而真相只是"方法用错了"。
     *
     * <p>危害与 404 那条完全一样：调用方以为服务崩了，去重试、去看服务端日志、去排查数据库……
     * 而真正该做的事只是把请求换成 POST。
     *
     * <p><b>★ 必须回 {@code Allow} 响应头</b>：这是 HTTP 规范对 405 的硬性要求 ——
     * 它告诉调用方"这个路径到底接受哪些方法"。没有它，调用方只知道错了、不知道该怎么改，
     * 只能去翻文档或源码。这是 405 相对 500 的**核心价值**，不能省。
     *
     * <h3>为什么和 404 一起补，而不是等下次再报</h3>
     *
     * <p>这已经是<b>同一个缺口的第二次出现</b>（第一次是 404）。两者本质是同一件事：
     * <b>Spring MVC 的协议级异常默认落进兜底，被报成了"服务内部错误"</b>。
     * 属于本项目第 ⑥ 条规则的适用情形 —— 同一类错误重复 ≥2 次，必须机制化。
     * 所以 415（请求体格式不支持）也一并补上，它们是同一类里最常见的三个。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        log.warn("[请求方法不支持] 收到={} 该接口只接受={}", e.getMethod(),
                supported == null ? "未知" : supported);

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        // Allow 头是 405 的规范要求：告诉调用方"该用什么方法"。
        // 注意 allow(...) 收的是 HttpMethod 变参，不是 String —— 所以用 getSupportedHttpMethods()
        // 而不是 getSupportedMethods()（后者返回 String[]）。
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(HttpMethod[]::new));
        }
        return builder.body(BaseResponse.failure(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 请求体格式（Content-Type）不被接受 → <b>415，而不是 500</b>。
     *
     * <p>典型场景：接口要 {@code application/json}，但调用方发了表单或纯文本。
     * 与 404 / 405 同属"协议级错误被报成服务端故障"这一类，故一并补上。
     *
     * <p>同样回 {@code Accept} 响应头告知该用什么格式（与 405 回 {@code Allow} 同理）。
     * 注意 {@code HeadersBuilder} <b>没有</b> {@code accept(...)} 方法，
     * 必须用 {@code headers(Consumer<HttpHeaders>)} 设 —— 这是查过签名才写的，不是凭记忆。
     */
    @ExceptionHandler(HttpMediaTypeException.class)
    public ResponseEntity<BaseResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeException e) {
        log.warn("[请求格式不支持] {} 支持的格式={}", e.getMessage(), e.getSupportedMediaTypes());

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.getHttpStatus());
        List<MediaType> supported = e.getSupportedMediaTypes();
        if (supported != null && !supported.isEmpty()) {
            builder.headers(headers -> headers.setAccept(supported));
        }
        return builder.body(BaseResponse.failure(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED));
    }

    // ==================== 模型调用失败（50xxx 中的上游段）====================

    /**
     * 模型调用失败 —— 上游（DeepSeek）返回错误状态码，或请求根本没发出去。
     *
     * <p><b>★ 这条分支是实测逼出来的，不是预先设计</b>：
     * 用无效 API Key 跑 {@code POST /chat} 时，对外返回
     * {@code HTTP 500 {"code":50000,"message":"服务内部错误"}}，
     * 而真实原因是上游 401。让"凭据无效"看起来像"我方崩溃"，
     * 会误导客户端去重试、也让运维无法按错误码定位。
     *
     * <p><b>为什么状态码取 502 而不是 500</b>：问题出在链路的上游依赖，
     * 不是本服务内部。502 让网关与监控能把"我方故障"和"上游故障"分开统计。
     *
     * <p><b>★ 为什么要 catch 四种异常</b>（第一版只 catch 了前两种，验证失败）：
     * <pre>
     *   WebClientResponseException  底层直接收到错误响应
     *   WebClientRequestException   请求没发出去（不是上者的子类，容易漏）
     *   NonTransientAiException     ★ 框架重试层包装 —— 实际抛出的就是它
     *   TransientAiException        同上，但属于"可重试"那一类
     * </pre>
     * 更细的分段（凭据 / 限流 / 上游挂掉 / 超时）由 {@link FailureMapper} 决定 ——
     * 规则集中在那一处，本方法只负责"记日志 + 组织响应"。
     */
    @ExceptionHandler({WebClientResponseException.class, WebClientRequestException.class,
            NonTransientAiException.class, TransientAiException.class})
    public ResponseEntity<BaseResponse<Void>> handleModelCallFailure(Exception e) {
        FailureMapping mapping = FailureMapper.map(e);

        // 记 ERROR 并带上原始堆栈 —— 脱敏只针对【对外响应】，日志必须完整，
        // 否则排查时只剩一句"模型调用失败"，等于没记。
        log.error("[模型调用失败] {} —— {}", mapping.logDetail(), mapping.operatorHint(), e);

        return ResponseEntity.status(mapping.httpStatus())
                .body(BaseResponse.failure(mapping.errorCode()));
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