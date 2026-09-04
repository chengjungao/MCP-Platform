package com.mcpbridge.executor.auth;

import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.snapshot.AuthDSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcpbridge.executor.mcp.McpErrorException;

/**
 * 下行鉴权（Auth-D / EXE-06）：校验 MCP 客户端有没有资格调用这个 Server。
 *
 * <p>三种模式：
 * <ul>
 *   <li><b>NONE</b>：端点公开。用于内网可信环境，必须在 UI 上显式选择，默认不是它。</li>
 *   <li><b>STATIC_BEARER</b>：平台签发静态令牌，库里只存 sha256，比对时<b>常量时间</b>，
 *       防止按字节短路造成的时序侧信道。</li>
 *   <li><b>OAUTH2</b>：完整 OAuth 2.1 资源服务器（含 RFC 9728 元数据发现）属于 P1。
 *       P0 明确拒绝而不是假装支持——半实现的鉴权比没有鉴权更危险。</li>
 * </ul>
 *
 * <p>失败一律回「令牌无效」，不区分「没带」「格式错」「不在白名单」，
 * 避免给攻击者做枚举反馈。
 */
@Component
public class DownstreamAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DownstreamAuthenticator.class);

    private static final String BEARER_PREFIX = "Bearer ";

    public void authenticate(ServerSnapshot server, HttpHeaders headers) {
        AuthDSnapshot authD = server.authD();
        if (authD == null || authD.mode() == null || authD.mode() == AuthDSnapshot.Mode.NONE) {
            return;
        }
        if (authD.mode() == AuthDSnapshot.Mode.OAUTH2) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", "OAUTH2");
            data.put("resourceMetadataUrl", authD.resourceMetadataUrl());
            data.put("hint", "OAuth 2.1 下行鉴权属于 P1 能力，当前请使用 STATIC_BEARER 模式");
            throw McpErrorException.of(HttpStatus.NOT_IMPLEMENTED.value(), JsonRpcErrorCodes.UNAUTHORIZED,
                    "该 Server 配置了 OAuth 2.1 下行鉴权，当前版本尚未实现", data);
        }

        String token = bearerToken(headers);
        if (token == null) {
            throw unauthorized(server, "missing_bearer");
        }
        List<String> hashes = authD.bearerTokenHashes() == null ? List.of() : authD.bearerTokenHashes();
        if (hashes.isEmpty()) {
            // 配置了 STATIC_BEARER 却没有令牌：这是配置错误，必须 fail-closed 而不是放行
            log.error("Server {} 配置了 STATIC_BEARER 下行鉴权但没有任何令牌哈希，按拒绝处理", server.pathSegment());
            throw unauthorized(server, "no_token_configured");
        }
        byte[] actual = Hashing.sha256Hex(token).getBytes(StandardCharsets.UTF_8);
        for (String expected : hashes) {
            if (expected != null
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual)) {
                return;
            }
        }
        throw unauthorized(server, "token_mismatch");
    }

    /** 该 Server 要求的 scope 集合，P0 只做透传展示，不做强制校验（P1 随 OAuth 一起落地）。 */
    public List<String> requiredScopes(ServerSnapshot server) {
        return server.authD() == null || server.authD().scopes() == null
                ? List.of() : server.authD().scopes();
    }

    private static String bearerToken(HttpHeaders headers) {
        String header = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static McpErrorException unauthorized(ServerSnapshot server, String reason) {
        // reason 只进日志，不进响应体
        log.debug("下行鉴权失败 server={} reason={}", server.pathSegment(), reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "STATIC_BEARER");
        data.put("wwwAuthenticate", "Bearer");
        return McpErrorException.of(HttpStatus.UNAUTHORIZED.value(), JsonRpcErrorCodes.UNAUTHORIZED,
                "下行鉴权失败：缺少有效的 Bearer 令牌", data);
    }
}