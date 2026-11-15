package com.hmdp.dto;

import com.hmdp.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;
    private Integer code;

    /**
     * 兼容原有四参数构造方式。
     */
    public Result(Boolean success, String errorMsg, Object data, Long total) {
        this(
                success,
                errorMsg,
                data,
                total,
                Boolean.TRUE.equals(success)
                        ? ErrorCode.SUCCESS.getCode()
                        : ErrorCode.BUSINESS_ERROR.getCode()
        );
    }

    public static Result ok() {
        return new Result(
                true, null, null, null, ErrorCode.SUCCESS.getCode()
        );
    }

    public static Result ok(Object data) {
        return new Result(
                true, null, data, null, ErrorCode.SUCCESS.getCode()
        );
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(
                true, null, data, total, ErrorCode.SUCCESS.getCode()
        );
    }

    public static Result fail(String errorMsg) {
        return fail(ErrorCode.BUSINESS_ERROR.getCode(), errorMsg);
    }

    public static Result fail(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static Result fail(int code, String errorMsg) {
        if (code <= 0) {
            throw new IllegalArgumentException("失败响应必须使用正整数错误码");
        }

        return new Result(false, errorMsg, null, null, code);
    }
}