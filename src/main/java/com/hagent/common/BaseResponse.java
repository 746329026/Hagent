package com.hagent.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包装 —— 全项目所有非流式接口的唯一返回类型。
 *
 * <p>用 record 而不是 Lombok @Data：不可变数据载体，没有 setter ——
 * 响应对象天然不该被二次修改，用 record 是"把约束写进类型"。
 * 注意 record 生成的是 {@code code()} 而非 {@code getCode()}，
 * Jackson 仍序列化成 JSON 字段 {@code "code"}。
 *
 * @param code    业务码，0 表示成功
 * @param message 人类可读描述
 * @param data    业务数据，失败时为 null
 * @param <T>     数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaseResponse<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_MESSAGE = "success";

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> BaseResponse<T> success() {
        return success(null);
    }

    public static <T> BaseResponse<T> failure(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> BaseResponse<T> failure(ErrorCode errorCode, String detail) {
        String message = detail == null || detail.isBlank()
                ? errorCode.getMessage()
                : errorCode.getMessage() + ": " + detail;
        return new BaseResponse<>(errorCode.getCode(), message, null);
    }

    /**
     * 判断是否成功。
     *
     * <p>{@code @JsonIgnore} 不可省：Jackson 内省会把 {@code isSuccess()} 当成名为
     * {@code success} 的属性序列化进 JSON，污染 {code,message,data} 契约。
     * 这类"多出字段"不报错，只会让前端与契约悄悄不一致。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}