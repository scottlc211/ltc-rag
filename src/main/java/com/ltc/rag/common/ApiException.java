package com.ltc.rag.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定的错误码字符串，便于调用方（Agent）做分支处理。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException internal(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message, cause);
    }

    public static ApiException badGateway(String code, String message, Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, code, message, cause);
    }
}
