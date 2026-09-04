package com.mcpbridge.common.jsonrpc;

/**
 * JSON-RPC 2.0 标准错误码 + 平台自定义错误码。
 *
 * <p>{@link #UNSUPPORTED_PROTOCOL_VERSION} 由 EXE-08 明确指定为 {@code -32022}，
 * 用于拒绝 legacy（2025-11-25 及更早）客户端请求。
 */
public final class JsonRpcErrorCodes {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** 不支持的协议版本（决策 D1：Modern-only，仅支持 2026-07-28）。 */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** 目标 Server（PATH 末段）未发布或已下线。 */
    public static final int SERVER_NOT_FOUND = -32001;

    /** Tool 不存在或已停用。 */
    public static final int TOOL_NOT_FOUND = -32002;

    /** 上行 REST 调用失败（超时 / 熔断 / 上游 5xx）。 */
    public static final int UPSTREAM_ERROR = -32003;

    /** 下行跳（Auth-D）鉴权失败。 */
    public static final int UNAUTHORIZED = -32004;

    private JsonRpcErrorCodes() {
    }
}
