package com.mcpbridge.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 错误对象。
 *
 * @param code    错误码，见 {@link JsonRpcErrorCodes}
 * @param message 人类可读说明；legacy 拒绝场景需附「仅支持 2026-07-28 协议」与升级引导
 * @param data    可选结构化补充信息（如 supportedProtocolVersion / upgradeUrl / traceId）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    public static JsonRpcError of(int code, String message, Object data) {
        return new JsonRpcError(code, message, data);
    }

    public static JsonRpcError invalidRequest(String message) {
        return of(JsonRpcErrorCodes.INVALID_REQUEST, message);
    }

    public static JsonRpcError invalidParams(String message) {
        return of(JsonRpcErrorCodes.INVALID_PARAMS, message);
    }

    public static JsonRpcError methodNotFound(String method) {
        return of(JsonRpcErrorCodes.METHOD_NOT_FOUND, "不支持的方法: " + method);
    }

    public static JsonRpcError internal(String message) {
        return of(JsonRpcErrorCodes.INTERNAL_ERROR, message);
    }
}
