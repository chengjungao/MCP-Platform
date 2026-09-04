package com.mcpbridge.manager.web.dto;

import com.mcpbridge.common.error.ErrorCode;

import java.time.Instant;
import java.util.Map;

/**
 * 控制面统一响应信封。前端只判 {@code success} + {@code code}，避免各处解析不同结构。
 *
 * @param success   是否成功
 * @param code      业务码，成功为 {@code OK}，失败为 {@link ErrorCode#name()}
 * @param message   人类可读说明
 * @param data      业务数据
 * @param details   结构化诊断（如解析失败的错误位置与缺失字段，REG-01）
 * @param timestamp 服务端时间
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Map<String, Object> details,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", null, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, "OK", message, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, code.name(), message, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiResponse<>(false, code.name(), message, null, details, Instant.now());
    }
}