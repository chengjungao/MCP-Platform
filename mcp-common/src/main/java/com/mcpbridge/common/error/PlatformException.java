package com.mcpbridge.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台业务异常。控制面统一异常处理器把它翻译为 {@code ApiResponse}，
 * 数据面把 {@link ErrorCode#UPSTREAM_CALL_FAILED} 等翻译为 JSON-RPC 错误或 {@code isError} 工具结果。
 */
public class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public PlatformException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, Map.of());
    }

    public PlatformException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, null, details);
    }

    public PlatformException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, Map.of());
    }

    public PlatformException(ErrorCode errorCode, String message, Throwable cause, Map<String, Object> details) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return errorCode.httpStatus();
    }

    /** 结构化诊断信息，例如解析失败的位置与缺失字段（REG-01 要求「不白屏」）。 */
    public Map<String, Object> details() {
        return details;
    }

    public static PlatformException notFound(String what, Object id) {
        return new PlatformException(ErrorCode.NOT_FOUND, what + " 不存在: " + id,
                Map.of("type", what, "id", String.valueOf(id)));
    }

    public static PlatformException forbidden(String message) {
        return new PlatformException(ErrorCode.FORBIDDEN, message);
    }

    public static PlatformException conflict(String message, Map<String, Object> details) {
        return new PlatformException(ErrorCode.CONFLICT, message, details);
    }

    public static PlatformException validation(String message, Map<String, Object> details) {
        return new PlatformException(ErrorCode.VALIDATION_FAILED, message, details);
    }
}
