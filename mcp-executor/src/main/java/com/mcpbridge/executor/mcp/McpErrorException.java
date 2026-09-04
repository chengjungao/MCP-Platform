package com.mcpbridge.executor.mcp;

import com.mcpbridge.common.jsonrpc.JsonRpcError;

/**
 * 协议级错误：既要回 JSON-RPC {@code error} 对象，又要给一个合理的 HTTP 状态码。
 *
 * <p>两者的对应关系是刻意设计的：
 * <ul>
 *   <li>{@code -32600/-32601/-32602} → 400：客户端请求本身有问题</li>
 *   <li>{@code -32001} Server 未发布 → 404</li>
 *   <li>{@code -32004} 下行鉴权失败 → 401</li>
 *   <li>{@code -32003} 上游失败 → 502（网关语义）</li>
 *   <li>{@code -32022} 协议版本不支持 → 400</li>
 * </ul>
 * 注意：<b>工具执行失败（上游 4xx/5xx）不走这里</b>，那是 {@code result.isError=true}，
 * 因为对 MCP 客户端来说「工具跑了但返回错误」是正常结果，不是协议故障。
 */
public class McpErrorException extends RuntimeException {

    private final JsonRpcError error;
    private final int httpStatus;

    public McpErrorException(int httpStatus, JsonRpcError error) {
        super(error == null ? null : error.message());
        this.error = error;
        this.httpStatus = httpStatus;
    }

    public static McpErrorException of(int httpStatus, int code, String message) {
        return new McpErrorException(httpStatus, JsonRpcError.of(code, message));
    }

    public static McpErrorException of(int httpStatus, int code, String message, Object data) {
        return new McpErrorException(httpStatus, JsonRpcError.of(code, message, data));
    }

    public JsonRpcError error() {
        return error;
    }

    public int httpStatus() {
        return httpStatus;
    }
}