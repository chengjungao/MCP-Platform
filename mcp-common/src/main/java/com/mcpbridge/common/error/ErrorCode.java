package com.mcpbridge.common.error;

/**
 * 平台统一错误码。HTTP 状态用于 Manager 的 REST 响应；
 * Executor 的 JSON-RPC 响应另用 {@code com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes}。
 */
public enum ErrorCode {

    /** 请求体或参数校验失败。 */
    VALIDATION_FAILED(400),
    /** 未登录或令牌无效。 */
    UNAUTHENTICATED(401),
    /** 已登录但缺少权限点，或跨部门越权（MGM-04：越权返回 403）。 */
    FORBIDDEN(403),
    /** 资源不存在。 */
    NOT_FOUND(404),
    /** 唯一性冲突，如共享集群内 PATH 末段重复（BR-3）。 */
    CONFLICT(409),
    /** 协议版本不受支持（决策 D1：Modern-only）。 */
    UNSUPPORTED_PROTOCOL_VERSION(400),
    /** Swagger/OpenAPI 文档解析失败，details 携带结构化诊断（REG-01）。 */
    PARSE_FAILED(422),
    /** 状态机不允许的操作，如对未配置完备的 Server 发布。 */
    INVALID_STATE(409),
    /** 上游 REST 调用失败（超时/熔断/5xx）。 */
    UPSTREAM_CALL_FAILED(502),
    /** 平台内部错误。 */
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
