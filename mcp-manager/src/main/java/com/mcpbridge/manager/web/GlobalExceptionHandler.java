package com.mcpbridge.manager.web;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.web.dto.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制面统一异常出口。
 *
 * <p>目标只有一个：<b>前端永远拿到同构的 JSON</b>，不会收到 Spring 的 HTML 错误页或裸堆栈。
 * 因此这里把「业务异常」「参数校验失败」「框架级 4xx」「未预期 5xx」四类都收敛到 {@link ApiResponse}。
 *
 * <p>5xx 只记录日志与 traceId，不把异常消息回传（避免泄露内部结构，SEC-02）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：状态码与错误码由 {@link ErrorCode} 决定，details 携带结构化诊断。 */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handlePlatform(PlatformException e) {
        // 4xx 属于预期内的业务拒绝，不打堆栈，避免日志被淹没
        if (e.httpStatus() >= 500) {
            log.error("业务异常 code={} msg={}", e.errorCode(), e.getMessage(), e);
        } else {
            log.debug("业务拒绝 code={} msg={} details={}", e.errorCode(), e.getMessage(), e.details());
        }
        return ResponseEntity.status(e.httpStatus())
                .body(ApiResponse.error(e.errorCode(), e.getMessage(), e.details()));
    }

    /** {@code @Valid} 请求体校验失败：把字段错误整理成 map，前端逐字段提示（REG-01「不白屏」）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, "请求参数校验未通过", fields));
    }

    /** {@code @Validated} 方法级参数（如 {@code @PathVariable}）校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            fields.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED, "请求参数校验未通过", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED,
                "请求体无法解析，请检查 JSON 格式与字段类型", Map.of("reason", String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED,
                "缺少必填参数：" + e.getParameterName(), Map.of("field", e.getParameterName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED,
                "参数类型不正确：" + e.getName(), Map.of("field", e.getName(), "value", String.valueOf(e.getValue()))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiResponse.error(ErrorCode.VALIDATION_FAILED, "不支持的请求方法：" + e.getMethod(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiResponse.error(ErrorCode.VALIDATION_FAILED, "上传文件超过大小上限（REG-01：默认 20MB）", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "接口不存在：" + e.getResourcePath(), null));
    }

    /**
     * 方法级 {@code @PreAuthorize} 拒绝。
     * 注意：过滤链层的拒绝由 SecurityConfig 的 accessDeniedHandler 处理，走不到这里。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, "缺少权限或跨部门越权", null));
    }

    /** 完整性约束冲突：翻译成 409，并按约束类型区分提示（PostgreSQL 错误消息关键词）。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        log.warn("数据完整性约束冲突：{}", cause);
        String message;
        if (cause.contains("violates foreign key")) {
            message = "数据冲突：引用的数据不存在或已被删除，请刷新后重试";
        } else if (cause.contains("violates not-null")) {
            message = "数据冲突：必填字段缺失";
        } else if (cause.contains("violates unique") || cause.contains("duplicate key")) {
            message = "数据冲突：唯一约束被违反，请检查名称 / PATH 末段 / 编码是否重复";
        } else {
            message = "数据冲突：完整性约束校验未通过";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ErrorCode.CONFLICT, message, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请联系管理员并提供时间点", null));
    }
}