package com.mcpbridge.executor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcRequest;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.protocol.McpHeaders;
import com.mcpbridge.common.protocol.McpMethods;
import com.mcpbridge.common.protocol.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 协议守卫：Modern-only 的执行者（决策 D1 / EXE-08）。
 *
 * <p>平台<b>不做</b>协议版本协商。理由写在 ADR-0001 里，这里只说工程后果：
 * 守卫必须在任何业务逻辑之前跑完，并且对每一种 legacy 形态给出<b>可区分</b>的 reason，
 * 否则用户只会看到「连不上」，然后花一天时间抓包。
 *
 * <p>识别的 legacy 形态（任一命中即拒绝）：
 * <ol>
 *   <li>{@code initialize} / {@code notifications/initialized} 方法——2026-07-28 已取消握手（SEP-2567）；</li>
 *   <li>{@code Mcp-Session-Id} 头存在——无会话化后不应出现（SEP-2575）；</li>
 *   <li>{@code Mcp-Protocol-Version} 头不是 2026-07-28；</li>
 *   <li>请求体 {@code params.protocolVersion} 不是 2026-07-28；</li>
 *   <li>{@code _meta.sessionId} 存在。</li>
 * </ol>
 */
@Component
public class ProtocolGuard {

    private static final Logger log = LoggerFactory.getLogger(ProtocolGuard.class);

    /**
     * @param headers 请求头
     * @param request 已解析的 JSON-RPC 请求，可为 null（解析失败时先报 -32700）
     */
    public void requireModern(HttpHeaders headers, JsonRpcRequest request) {
        String sessionId = headers.getFirst(McpHeaders.SESSION_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            throw new LegacyProtocolException(null,
                    "请求携带 Mcp-Session-Id 头，属于会话化协议形态；本平台无会话（SEP-2575），请移除该头");
        }

        String headerVersion = headers.getFirst(McpHeaders.PROTOCOL_VERSION);
        if (headerVersion != null && !headerVersion.isBlank() && !McpProtocol.isSupported(headerVersion.trim())) {
            throw new LegacyProtocolException(headerVersion.trim(),
                    legacyHint(headerVersion.trim()) + "Mcp-Protocol-Version 头声明的版本不受支持");
        }

        if (request == null) {
            return;
        }
        if (McpMethods.isLegacy(request.method())) {
            throw new LegacyProtocolException(null,
                    "请求使用了 legacy 方法 " + request.method()
                            + "；2026-07-28 已取消 initialize 握手，直接调用 server/discover 获取能力即可");
        }

        JsonNode params = request.params();
        if (params != null) {
            JsonNode declared = params.get("protocolVersion");
            if (declared != null && declared.isTextual() && !McpProtocol.isSupported(declared.asText())) {
                throw new LegacyProtocolException(declared.asText(),
                        legacyHint(declared.asText()) + "params.protocolVersion 声明的版本不受支持");
            }
        }
        String metaSession = request.metaText("sessionId");
        if (metaSession != null && !metaSession.isBlank()) {
            throw new LegacyProtocolException(null,
                    "_meta.sessionId 属于会话化协议形态；本平台无会话，请移除该字段");
        }
    }

    /** 信封级校验：jsonrpc 必须是 "2.0"，method 必须非空。 */
    public void requireValidEnvelope(JsonRpcRequest request) {
        if (request == null) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_REQUEST,
                    "请求体为空");
        }
        if (!request.isValidEnvelope()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_REQUEST,
                    "非法的 JSON-RPC 2.0 信封：jsonrpc 必须为 \"2.0\" 且 method 不能为空");
        }
    }

    /**
     * 路由用的方法名（SEP-2243）：{@code Mcp-Method} 头优先于请求体里的 method。
     *
     * <p>头优先而不是体优先，是因为头可以在不解析请求体的情况下被网关与缓存读取；
     * 两者同时存在且不一致时以头为准，并记 WARN——这种客户端一定有 bug，要让它被发现。
     */
    public String resolveMethod(HttpHeaders headers, String bodyMethod) {
        String fromHeader = headers.getFirst(McpHeaders.METHOD);
        if (fromHeader == null || fromHeader.isBlank()) {
            return bodyMethod;
        }
        String headerMethod = fromHeader.trim();
        if (bodyMethod != null && !bodyMethod.isBlank() && !headerMethod.equals(bodyMethod)) {
            log.warn("Mcp-Method 头（{}）与请求体 method（{}）不一致，以头为准", headerMethod, bodyMethod);
        }
        return headerMethod;
    }

    /** 目标 tool 名：{@code Mcp-Name} 头优先于 {@code params.name}。 */
    public String resolveToolName(HttpHeaders headers, JsonRpcRequest request) {
        String fromHeader = headers.getFirst(McpHeaders.NAME);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return request == null ? null : request.paramText("name");
    }

    private static String legacyHint(String version) {
        return McpProtocol.isKnownLegacy(version)
                ? "该版本属于 legacy 协议，本平台已停止支持；"
                : "";
    }
}