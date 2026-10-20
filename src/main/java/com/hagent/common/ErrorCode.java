package com.hagent.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码枚举 —— "早期立契约的复利"：第 2 阶段定死响应与错误码后，
 * 后面所有新接口都不需要改动它们。
 *
 * <p>分段规则：
 * <pre>
 *   0      成功
 *   4xxxx  客户端 / 参数类错误（可归因于调用方，排查无需看日志）
 *   5xxxx  服务端 / 系统类错误（需查日志与链路追踪）
 * </pre>
 *
 * <p>★ 预留策略：编号按<b>功能域</b>留空段，新功能直接在自己段内追加，
 * 不改动已有编号 —— 编号一旦对外发布就是契约，改号等于破坏调用方。
 *
 * <p>★ 为什么枚举里带 HttpStatus：业务码与 HTTP 状态码是两个维度，
 * 但必须一起决定。放在同一处，避免"加了错误码忘了设 HTTP 状态"导致全部返回 200。
 *
 * <p>说明：当前是最小可用集合（阶段 0 只需健康检查与基础错误），
 * 各功能域（会话 / RAG / 工具 / Agent / 长期记忆）的分段错误码
 * 会在对应阶段追加 —— 编号段位已经预留好，追加不需要改动已有代码。
 */
@Getter
public enum ErrorCode {

    // ===== 4xxxx 参数 / 客户端 =====
    INVALID_PARAM(40001, "请求参数不合法", HttpStatus.BAD_REQUEST),
    MISSING_PARAM(40002, "缺少必要参数", HttpStatus.BAD_REQUEST),
    // 访问了不存在的接口路径。
    // 为什么要单独一个码而不是落进兜底的 50000：路由不存在是**调用方的问题**
    // （拼错 URL、接口已下线、版本不匹配），不是服务端故障。
    // 返回 5xx 会让调用方以为"服务崩了"而去重试或告警 —— 而重试一个不存在的路径永远不会成功。
    API_NOT_FOUND(40004, "接口不存在", HttpStatus.NOT_FOUND),
    // 路径存在、但 HTTP 方法不对（例如用 GET 访问只接受 POST 的接口）。
    // ★ 这条是实测发现的：在浏览器地址栏访问 POST 接口，得到的是 500 —— 见 GlobalExceptionHandler。
    METHOD_NOT_ALLOWED(40005, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    // 请求的 Content-Type 不被接口接受（例如该发 application/json 却发了 form）。
    MEDIA_TYPE_NOT_SUPPORTED(40006, "请求格式不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    // ===== 41xxx 会话域（阶段 6 预留）=====
    SESSION_NOT_FOUND(41001, "会话不存在", HttpStatus.NOT_FOUND),
    SESSION_EXPIRED(41002, "会话已过期", HttpStatus.GONE),

    // ===== 42xxx RAG 知识库域（阶段 7 预留）=====
    RETRIEVAL_FAILED(42003, "知识检索失败", HttpStatus.INTERNAL_SERVER_ERROR),
    KNOWLEDGE_FORBIDDEN(42004, "无权访问该密级知识", HttpStatus.FORBIDDEN),

    // ===== 43xxx 工具调用域（阶段 8 预留）=====
    // ⚠️ 阶段 8 追加时，同时补 exception/FailureMapper 里的工具异常分支
    //    （ToolExecutionException / ToolCallLimitExceededException）。
    //    现在不提前加：没有真实抛出路径的映射是"看起来做完了"的假象。
    TOOL_NOT_FOUND(43001, "工具不存在", HttpStatus.BAD_REQUEST),
    TOOL_EXECUTION_FAILED(43002, "工具执行失败", HttpStatus.INTERNAL_SERVER_ERROR),

    // ===== 44xxx Agent 执行域（阶段 9 / 10 预留）=====
    AGENT_MAX_STEPS_EXCEEDED(44001, "达到最大执行步数，任务未完成", HttpStatus.OK),
    AGENT_INTERRUPTED(44002, "任务已中断，等待用户补充信息", HttpStatus.OK),

    // ===== 45xxx 长期记忆域（阶段 11 定义，当前无使用者）=====
    // ⚠️ 阶段 12 的"死成员清理"要处置这两条：要么接上，要么删（见 docs/架构问题.md 顶部复核结论）
    MEMORY_PATH_FORBIDDEN(45001, "记忆文件路径越界，已拒绝", HttpStatus.FORBIDDEN),
    MEMORY_CONTENT_REJECTED(45002, "记忆内容未通过审核", HttpStatus.BAD_REQUEST),

    // ===== 50xxx 系统 / 服务端 =====
    //
    // ★ 50xxx 内部再分四段 —— 这是实测逼出来的，不是预先设计：
    //   50000       本地未知故障（兜底）。真实原因未查明 → 必须查日志
    //   50001~50002 模型调用的"可重试"故障：模型调用失败 / 超时
    //   50004       基础设施不可用（存储等）
    //   50005~50007 上游模型提供方的"不可重试"故障：凭据 / 限流 / 上游挂掉
    //
    // ⚠️ 为什么必须把 50005~50007 单独分出来（本段的存在理由）：
    //   DeepSeek 返回 401 时，原先全部落进兜底 → 对外一律是
    //   {"code":50000,"message":"服务内部错误"}。
    //   客户端看到 500 会<b>重试</b>，而"API Key 无效"重试一万次也不会成功；
    //   运维也无法从错误码判断"是我方崩溃还是上游问题"。
    //   错误码的意义就是让调用方<b>不查日志也能决定下一步动作</b>。
    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    MODEL_CALL_FAILED(50001, "模型调用失败", HttpStatus.BAD_GATEWAY),
    MODEL_TIMEOUT(50002, "模型调用超时", HttpStatus.GATEWAY_TIMEOUT),
    STORAGE_UNAVAILABLE(50004, "存储服务不可用", HttpStatus.SERVICE_UNAVAILABLE),

    // 上游模型提供方的凭据问题 —— 归因于<b>我方配置</b>，不是调用方的错，也不是上游的错。
    // HTTP 取 502（而不是 500）：明确表达"问题出在链路的上游依赖"。
    MODEL_AUTH_FAILED(50005, "模型服务认证失败，请检查 API Key 配置", HttpStatus.BAD_GATEWAY),
    // 上游限流 —— 这是<b>唯一</b>真正"等一下重试就有用"的模型故障，所以单列。
    MODEL_RATE_LIMITED(50006, "模型服务限流，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    // 上游故障或网络不通 —— 我们没有错，上游或网络的问题。
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