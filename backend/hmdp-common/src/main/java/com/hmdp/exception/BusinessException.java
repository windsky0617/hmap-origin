package com.hmdp.exception;

import com.hmdp.enums.ErrorCode;

import java.util.Objects;

/**
 * 已明确分类、可以转换为约定响应的业务异常。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").getMessage());

        if (errorCode == ErrorCode.SUCCESS) {
            throw new IllegalArgumentException("成功码不能用于业务异常");
        }

        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}