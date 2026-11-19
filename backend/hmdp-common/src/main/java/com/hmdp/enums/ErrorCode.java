package com.hmdp.enums;

/**
 * 当前阶段的基础响应码。
 *
 * BUSINESS_ERROR 保留旧业务失败的 HTTP 200 行为，
 * 各业务模块后续再逐步细化错误码和 HTTP 状态。
 */
public enum ErrorCode {

    SUCCESS(0, 200, null),

    BUSINESS_ERROR(10000, 200, "业务处理失败"),

    BAD_REQUEST(40000, 400, "请求参数错误"),

    UNAUTHORIZED(40100, 401, "请先登录"),

    INTERNAL_ERROR(50000, 500, "服务器异常");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}