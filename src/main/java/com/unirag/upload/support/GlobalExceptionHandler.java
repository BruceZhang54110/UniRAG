package com.unirag.upload.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理器（contracts §1 错误模型）：业务异常按码映射 HTTP 状态，
 * 未知异常收敛为 500 INTERNAL_ERROR 且不泄漏内部细节（FR-010，响应只给可读原因，细节进服务端日志）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(statusOf(e.getCode()))
                .body(errorBody(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(Exception e) {
        return ResponseEntity.badRequest()
                .body(errorBody("VALIDATION_FAILED", "请求参数不合法: " + e.getMessage(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(errorBody("FILE_TOO_LARGE", "请求体超过 multipart 上限", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        // 只记录稳定类型，避免异常 message/堆栈携带配置原文或密钥（FR-010）。
        log.error("未处理异常（响应已收敛为 500）: type={}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR", "服务内部错误，请稍后重试或联系运维", null));
    }

    private HttpStatus statusOf(String code) {
        return switch (code) {
            case "TENANT_CODE_REQUIRED", "VALIDATION_FAILED", "UNSUPPORTED_FILE_TYPE" -> HttpStatus.BAD_REQUEST;
            case "TENANT_NOT_FOUND", "UPLOAD_NOT_FOUND", "DOCUMENT_NOT_FOUND",
                    "DOCUMENT_INGESTION_JOB_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "UPLOAD_EXPIRED" -> HttpStatus.GONE;
            case "UPLOAD_INCOMPLETE", "CHECKSUM_MISMATCH", "CONCURRENT_MODIFICATION" -> HttpStatus.CONFLICT;
            case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private Map<String, Object> errorBody(String code, String message, Object details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (details != null) {
            error.put("details", details);
        }
        return Map.of("error", error);
    }
}
