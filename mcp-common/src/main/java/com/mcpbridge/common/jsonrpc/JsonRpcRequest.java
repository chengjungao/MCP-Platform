package com.mcpbridge.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 请求信封。
 *
 * <p>2026-07-28 的请求携带 {@code _meta} 信封（用于 trace/扩展），legacy 请求没有；
 * Executor 的协议守卫把「无 {@code _meta} 且带 initialize/Mcp-Session-Id」判定为 legacy 形态。
 *
 * @param jsonrpc 固定 "2.0"
 * @param id      请求 id；为 null 或 JSON null 时是通知（不需要响应）
 * @param method  方法名，见 {@code McpMethods}
 * @param params  方法参数
 * @param meta    {@code _meta} 信封，W3C Trace Context 等由此透传
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") JsonNode id,
        @JsonProperty("method") String method,
        @JsonProperty("params") JsonNode params,
        @JsonProperty("_meta") JsonNode meta) {

    public static final String VERSION = "2.0";

    /** 通知（无 id）不需要响应。 */
    public boolean isNotification() {
        return id == null || id.isNull();
    }

    /** 是否为合法的 JSON-RPC 2.0 请求外形。 */
    public boolean isValidEnvelope() {
        return VERSION.equals(jsonrpc) && method != null && !method.isBlank();
    }

    public JsonNode param(String name) {
        return params == null ? null : params.get(name);
    }

    public String paramText(String name) {
        JsonNode node = param(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    public String metaText(String name) {
        return meta == null || meta.get(name) == null ? null : meta.get(name).asText();
    }

    /** 从 {@code _meta} 中读取 traceId（OPS-02：全链路 trace）。 */
    public String traceId() {
        String fromMeta = metaText("traceId");
        return fromMeta != null ? fromMeta : metaText("traceparent");
    }
}
