package com.ltc.rag.common;

import com.ltc.rag.auth.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常出口：所有失败都返回 {code, message, requestId}，
 * 调用方（Agent）可以按 code 做分支，而不是去正则匹配 message。
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> onApiException(ApiException e) {
        if (e.status().is5xxServerError()) {
            log.error("业务异常 code={} msg={}", e.code(), e.getMessage(), e);
        } else {
            log.warn("业务异常 code={} msg={}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.status())
                .body(new ApiError(e.code(), e.getMessage(), AuthContext.requestId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(ErrorHandler::describe)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", detail, AuthContext.requestId()));
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> onBadRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", rootMessage(e), AuthContext.requestId()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> onTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("PAYLOAD_TOO_LARGE", "文件超过上传上限，请拆分或调整 MAX_UPLOAD_MB", AuthContext.requestId()));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> onMultipart(MultipartException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_MULTIPART", rootMessage(e), AuthContext.requestId()));
    }

    /** 访问了不存在的路径/静态资源：返回 404，别让兜底处理器把它变成 500 并刷错误日志。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> onNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "资源不存在：" + e.getResourcePath(), AuthContext.requestId()));
    }

    /** 兜底：不把堆栈/SQL 细节暴露给调用方，但 requestId 能让我们在日志里找到它。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception e) {
        log.error("未处理异常 requestId={}", AuthContext.requestId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务内部错误，请凭 requestId 排查", AuthContext.requestId()));
    }

    private static String describe(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? e.getClass().getSimpleName() : t.getMessage();
    }
}
