package com.hagent.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码枚举：对外契约的一部分。
 *
 * <p>分段：{@code 0} 成功；{@code 4xxxx} 客户端 / 参数类错误（可归因于调用方，排查无需看日志）；
 * {@code 5xxxx} 服务端 / 系统类错误（需查日志与链路追踪）。
 *
 * <p>编号按<b>功能域</b>留空段追加，不改动已有编号 —— 编号对外发布即契约，改号等于破坏调用方。
 *
 * <p>为什么枚举里带 {@link HttpStatus}：业务码与 HTTP 状态码是两个维度，但必须一起决定。
 * 放在同一处，避免"加了错误码忘了设 HTTP 状态"导致全部返回 200。
 */
@Getter
public enum ErrorCode {

    // ===== 4xxxx 参数 / 客户端 =====
    INVALID_PARAM(40001, "请求参数不合法", HttpStatus.BAD_REQUEST),
    MISSING_PARAM(40002, "缺少必要参数", HttpStatus.BAD_REQUEST),
    // 访问了不存在的接口路径。单列而不落进兜底的 50000：路由不存在是调用方的问题
    // （拼错 URL、接口已下线、版本不匹配），返回 5xx 会让调用方以为服务崩了去重试或告警，
    // 而重试一个不存在的路径永远不会成功。
    API_NOT_FOUND(40004, "接口不存在", HttpStatus.NOT_FOUND),
    // 路径存在、但 HTTP 方法不对（例如用 GET 访问只接受 POST 的接口）。
    METHOD_NOT_ALLOWED(40005, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    // 请求的 Content-Type 不被接口接受。
    MEDIA_TYPE_NOT_SUPPORTED(40006, "请求格式不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // ===== 41xxx 会话域 =====
    SESSION_NOT_FOUND(41001, "会话不存在", HttpStatus.NOT_FOUND),
    SESSION_EXPIRED(41002, "会话已过期", HttpStatus.GONE),

    // ===== 42xxx RAG 知识库域 =====
    RETRIEVAL_FAILED(42003, "知识检索失败", HttpStatus.INTERNAL_SERVER_ERROR),
    KNOWLEDGE_FORBIDDEN(42004, "无权访问该密级知识", HttpStatus.FORBIDDEN),

    // ===== 43xxx 工具调用域 =====
    // 只加有真实抛出路径的错误码（异常分支见 exception/FailureMapper）。
    TOOL_NOT_FOUND(43001, "工具不存在", HttpStatus.BAD_REQUEST),
    TOOL_EXECUTION_FAILED(43002, "工具执行失败", HttpStatus.INTERNAL_SERVER_ERROR),

    // ===== 44xxx Agent 执行域 =====
    AGENT_MAX_STEPS_EXCEEDED(44001, "达到最大执行步数，任务未完成", HttpStatus.OK),
    AGENT_INTERRUPTED(44002, "任务已中断，等待用户补充信息", HttpStatus.OK),

    // ===== 45xxx 长期记忆域（当前无使用者）=====
    MEMORY_PATH_FORBIDDEN(45001, "记忆文件路径越界，已拒绝", HttpStatus.FORBIDDEN),
    MEMORY_CONTENT_REJECTED(45002, "记忆内容未通过审核", HttpStatus.BAD_REQUEST),

    // ===== 50xxx 系统 / 服务端 =====
    //
    //   50000       本地未知故障（兜底）。真实原因未查明 → 必须查日志
    //   50001~50002 模型调用的"可重试"故障：调用失败 / 超时
    //   50004       基础设施不可用（存储等）
    //   50005~50007 上游模型提供方的故障：凭据 / 限流 / 上游挂掉
    //
    // ★ 50005~50007 必须与兜底分开：客户端看到 50000 会重试，而"API Key 无效"重试一万次也不会成功；
    //   运维也无法从错误码判断"是我方崩溃还是上游问题"。
    //   错误码的意义就是让调用方不查日志也能决定下一步动作。
    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    MODEL_CALL_FAILED(50001, "模型调用失败", HttpStatus.BAD_GATEWAY),
    MODEL_TIMEOUT(50002, "模型调用超时", HttpStatus.GATEWAY_TIMEOUT),
    STORAGE_UNAVAILABLE(50004, "存储服务不可用", HttpStatus.SERVICE_UNAVAILABLE),

    // 上游凭据问题 —— 归因于我方配置，不是调用方的错也不是上游的错。
    // HTTP 取 502（而非 500）：明确表达"问题出在链路的上游依赖"。
    MODEL_AUTH_FAILED(50005, "模型服务认证失败，请检查 API Key 配置", HttpStatus.BAD_GATEWAY),
    // 上游限流 —— 唯一"等一下重试就有用"的模型故障，所以单列。
    MODEL_RATE_LIMITED(50006, "模型服务限流，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    // 上游故障或网络不通 —— 我们没有错。
    MODEL_UNAVAILABLE(50007, "模型服务不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}