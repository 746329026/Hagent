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

    // ===== 41xxx 会话域（阶段 6 预留）=====
    SESSION_NOT_FOUND(41001, "会话不存在", HttpStatus.NOT_FOUND),
    SESSION_EXPIRED(41002, "会话已过期", HttpStatus.GONE),

    // ===== 42xxx RAG 知识库域（阶段 7 预留）=====
    RETRIEVAL_FAILED(42003, "知识检索失败", HttpStatus.INTERNAL_SERVER_ERROR),
    KNOWLEDGE_FORBIDDEN(42004, "无权访问该密级知识", HttpStatus.FORBIDDEN),

    // ===== 43xxx 工具调用域（阶段 8 预留）=====
    TOOL_NOT_FOUND(43001, "工具不存在", HttpStatus.BAD_REQUEST),
    TOOL_EXECUTION_FAILED(43002, "工具执行失败", HttpStatus.INTERNAL_SERVER_ERROR),

    // ===== 44xxx Agent 执行域（阶段 9 / 10 预留）=====
    AGENT_MAX_STEPS_EXCEEDED(44001, "达到最大执行步数，任务未完成", HttpStatus.OK),
    AGENT_INTERRUPTED(44002, "任务已中断，等待用户补充信息", HttpStatus.OK),

    // ===== 45xxx 长期记忆域（阶段 12 预留）=====
    MEMORY_PATH_FORBIDDEN(45001, "记忆文件路径越界，已拒绝", HttpStatus.FORBIDDEN),
    MEMORY_CONTENT_REJECTED(45002, "记忆内容未通过审核", HttpStatus.BAD_REQUEST),

    // ===== 5xxxx 系统 / 服务端 =====
    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    MODEL_CALL_FAILED(50001, "模型调用失败", HttpStatus.BAD_GATEWAY),
    MODEL_TIMEOUT(50002, "模型调用超时", HttpStatus.GATEWAY_TIMEOUT),
    STORAGE_UNAVAILABLE(50004, "存储服务不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}