package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(
            BusinessException exception) {

        ErrorCode errorCode = exception.getErrorCode();

        if (errorCode.getHttpStatus() >= 500) {
            log.error("业务异常被分类为服务端错误", exception);
        }

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(
            Exception exception) {

        log.error("未处理的服务端异常", exception);

        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        if (statusCode.is5xxServerError()) {
            log.error("Spring MVC 请求处理异常", exception);
        }

        int code = statusCode.value() * 100;
        String message = getHttpErrorMessage(statusCode);

        return super.handleExceptionInternal(
                exception,
                Result.fail(code, message),
                headers,
                statusCode,
                request
        );
    }

    private String getHttpErrorMessage(HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR.getMessage();
        }

        return switch (statusCode.value()) {
            case 400 -> ErrorCode.BAD_REQUEST.getMessage();
            case 401 -> ErrorCode.UNAUTHORIZED.getMessage();
            case 404 -> "请求的资源不存在";
            case 405 -> "请求方法不支持";
            case 413 -> "上传内容过大";
            case 415 -> "请求内容类型不支持";
            default -> "请求处理失败";
        };
    }
}







/*
package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
*/