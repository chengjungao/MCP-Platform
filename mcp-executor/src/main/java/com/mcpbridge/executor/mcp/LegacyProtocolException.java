package com.mcpbridge.executor.mcp;

import com.mcpbridge.common.jsonrpc.JsonRpcResponse;
import com.mcpbridge.common.protocol.McpProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * legacy 协议形态被识别后的拒绝信号（EXE-08 / 决策 D1）。
 *
 * <p>单独成一个异常类型而不是复用 {@link McpErrorException}，是因为它的响应体有固定形状：
 * 必须带 {@code supportedProtocolVersion}、{@code detectedProtocolVersion}、{@code legacySupported=false}
 * 与 {@code upgradeUrl}。客户端拿到这个响应就能自动给出「请升级」的提示，
 * 而不是看到一个语焉不详的 -32601。
 */
public class LegacyProtocolException extends RuntimeException {

    private final String detectedVersion;
    private final String reason;

    public LegacyProtocolException(String detectedVersion, String reason) {
        super("拒绝 legacy 协议形态：" + reason);
        this.detectedVersion = detectedVersion;
        this.reason = reason;
    }

    public String detectedVersion() {
        return detectedVersion;
    }

    public String reason() {
        return reason;
    }

    /** 是否为平台已知的 legacy 版本号（用于埋点区分「旧版本」与「未知版本」）。 */
    public boolean isKnownLegacy() {
        return McpProtocol.isKnownLegacy(detectedVersion);
    }

    public JsonRpcResponse toResponse(com.fasterxml.jackson.databind.JsonNode id) {
        return JsonRpcResponse.unsupportedProtocolVersion(id, detectedVersion, reason);
    }

    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detectedProtocolVersion", detectedVersion);
        details.put("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION);
        details.put("legacySupported", false);
        details.put("reason", reason);
        details.put("upgradeUrl", McpProtocol.UPGRADE_GUIDE_URL);
        return details;
    }
}