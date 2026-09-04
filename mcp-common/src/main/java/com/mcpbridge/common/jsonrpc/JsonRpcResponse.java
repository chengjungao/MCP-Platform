package com.mcpbridge.common.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.protocol.McpProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 响应信封。{@code result} 与 {@code error} 互斥。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, JsonNode id, Object result, JsonRpcError error) {

    public static JsonRpcResponse ok(JsonNode id, Object result) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, result, null);
    }

    public static JsonRpcResponse error(JsonNode id, JsonRpcError error) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, null, error);
    }

    public static JsonRpcResponse error(JsonNode id, int code, String message) {
        return error(id, JsonRpcError.of(code, message));
    }

    public boolean isError() {
        return error != null;
    }

    /**
     * 构造 legacy 拒绝响应（EXE-08 验收项 ②③）：
     * 错误码 {@code -32022}，附「仅支持 2026-07-28 协议」说明与升级引导 URL。
     *
     * @param detectedVersion 客户端声明或推断出的协议版本，可为 null
     * @param reason          判定为 legacy 的依据（initialize 方法 / Mcp-Session-Id 头 / 旧版本号）
     */
    public static JsonRpcResponse unsupportedProtocolVersion(JsonNode id, String detectedVersion, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION);
        data.put("detectedProtocolVersion", detectedVersion);
        data.put("reason", reason);
        data.put("upgradeUrl", McpProtocol.UPGRADE_GUIDE_URL);
        data.put("legacySupported", false);
        return error(id, new JsonRpcError(
                JsonRpcErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported Protocol Version: 本平台仅支持 MCP " + McpProtocol.SUPPORTED_VERSION
                        + "，不支持 " + (detectedVersion == null ? "legacy" : detectedVersion)
                        + " 协议形态（" + reason + "）。请升级客户端，详见 " + McpProtocol.UPGRADE_GUIDE_URL,
                data));
    }
}
