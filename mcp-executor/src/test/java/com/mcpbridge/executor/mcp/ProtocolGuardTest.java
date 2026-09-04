package com.mcpbridge.executor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.jsonrpc.JsonRpcRequest;
import com.mcpbridge.common.jsonrpc.JsonRpcResponse;
import com.mcpbridge.common.protocol.McpHeaders;
import com.mcpbridge.common.protocol.McpMethods;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Modern-only 协议守卫（决策 D1 / EXE-08）。
 *
 * <p>这是平台对外承诺里最硬的一条：只做 2026-07-28，对 legacy 形态<b>显式拒绝并给出升级引导</b>。
 * 拒绝本身不难，难的是每一种 legacy 形态都要给出<b>可区分</b>的原因——
 * 否则用户只会看到「连不上」，然后花一整天抓包。所以这里逐个形态钉死。
 */
class ProtocolGuardTest {

    private static final JsonNode ID = Json.MAPPER.valueToTree(1);

    private final ProtocolGuard guard = new ProtocolGuard();

    // ------------------------------------------------------------------ 放行

    @Test
    @DisplayName("2026-07-28 请求正常放行")
    void acceptsModernRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.PROTOCOL_VERSION, McpProtocol.SUPPORTED_VERSION);

        assertThatCode(() -> guard.requireModern(headers, request(McpMethods.TOOLS_LIST, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未声明协议版本时放行：版本头是可选的，缺失不等于 legacy")
    void acceptsRequestWithoutProtocolVersionHeader() {
        assertThatCode(() -> guard.requireModern(new HttpHeaders(), request(McpMethods.TOOLS_CALL, null, null)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ legacy 形态拒绝

    @Test
    @DisplayName("携带 Mcp-Session-Id 头即判定为会话化 legacy 形态")
    void rejectsSessionIdHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.SESSION_ID, "sess-123");

        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(headers, request(McpMethods.TOOLS_LIST, null, null)));

        assertThat(e.reason()).contains("Mcp-Session-Id");
        assertThat(e.isKnownLegacy()).isFalse();
        assertThat(e.details())
                .containsEntry("legacySupported", false)
                .containsEntry("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION)
                .containsEntry("upgradeUrl", McpProtocol.UPGRADE_GUIDE_URL);
    }

    @Test
    @DisplayName("已知的旧版本号被识别为 legacy，并带上升级引导")
    void rejectsKnownLegacyProtocolVersionHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.PROTOCOL_VERSION, "2025-06-18");

        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(headers, request(McpMethods.TOOLS_LIST, null, null)));

        assertThat(e.detectedVersion()).isEqualTo("2025-06-18");
        assertThat(e.isKnownLegacy()).isTrue();

        JsonRpcResponse response = e.toResponse(ID);
        assertThat(response.isError()).isTrue();
        assertThat(response.error().code()).isEqualTo(JsonRpcErrorCodes.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(response.error().message()).contains(McpProtocol.UPGRADE_GUIDE_URL);
        assertThat(response.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("未知版本号同样拒绝，但埋点上区分于已知 legacy")
    void rejectsUnknownProtocolVersionHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.PROTOCOL_VERSION, "1999-01-01");

        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(headers, request(McpMethods.TOOLS_LIST, null, null)));

        assertThat(e.isKnownLegacy()).isFalse();
        assertThat(e.detectedVersion()).isEqualTo("1999-01-01");
    }

    @Test
    @DisplayName("initialize 握手方法被拒绝：2026-07-28 已取消握手（SEP-2567）")
    void rejectsLegacyInitializeMethod() {
        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(new HttpHeaders(), request(McpMethods.LEGACY_INITIALIZE, null, null)));

        assertThat(e.reason()).contains("initialize");
    }

    @Test
    @DisplayName("notifications/initialized 通知同样被拒绝")
    void rejectsLegacyInitializedNotification() {
        assertThatThrownBy(() -> guard.requireModern(new HttpHeaders(),
                request(McpMethods.LEGACY_INITIALIZED, null, null)))
                .isInstanceOf(LegacyProtocolException.class);
    }

    @Test
    @DisplayName("请求体里声明的 params.protocolVersion 也参与判定")
    void rejectsLegacyProtocolVersionInParams() {
        JsonNode params = Json.obj().put("protocolVersion", "2024-11-05");

        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(new HttpHeaders(), request(McpMethods.TOOLS_LIST, params, null)));

        assertThat(e.detectedVersion()).isEqualTo("2024-11-05");
        assertThat(e.isKnownLegacy()).isTrue();
    }

    @Test
    @DisplayName("_meta.sessionId 属于会话化形态，同样拒绝")
    void rejectsSessionIdInMeta() {
        JsonNode meta = Json.obj().put("sessionId", "abc");

        LegacyProtocolException e = expectLegacy(
                () -> guard.requireModern(new HttpHeaders(), request(McpMethods.TOOLS_LIST, null, meta)));

        assertThat(e.reason()).contains("_meta.sessionId");
    }

    @Test
    @DisplayName("请求体解析失败时仍先做基于头的 legacy 判定")
    void rejectsLegacyHeadersEvenWhenRequestBodyUnparseable() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.SESSION_ID, "sess-1");

        assertThatThrownBy(() -> guard.requireModern(headers, null))
                .isInstanceOf(LegacyProtocolException.class);
    }

    // ------------------------------------------------------------------ 信封校验

    @Test
    @DisplayName("jsonrpc 不是 2.0 时回 -32600")
    void rejectsWrongJsonRpcVersion() {
        McpErrorException e = expectError(() -> guard.requireValidEnvelope(
                new JsonRpcRequest("1.0", ID, McpMethods.TOOLS_LIST, null, null)));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_REQUEST);
        assertThat(e.httpStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("method 缺失时回 -32600")
    void rejectsMissingMethod() {
        McpErrorException e = expectError(() -> guard.requireValidEnvelope(
                new JsonRpcRequest("2.0", ID, null, null, null)));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_REQUEST);
    }

    @Test
    @DisplayName("请求体为空时回 -32600")
    void rejectsNullRequest() {
        assertThat(expectError(() -> guard.requireValidEnvelope(null)).error().code())
                .isEqualTo(JsonRpcErrorCodes.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------ 头路由（SEP-2243）

    @Test
    @DisplayName("Mcp-Method 头优先于请求体 method，使网关无需解析请求体即可路由")
    void resolveMethodPrefersHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.METHOD, McpMethods.TOOLS_CALL);

        assertThat(guard.resolveMethod(headers, McpMethods.TOOLS_LIST)).isEqualTo(McpMethods.TOOLS_CALL);
    }

    @Test
    @DisplayName("没有 Mcp-Method 头时回落到请求体 method")
    void resolveMethodFallsBackToBody() {
        assertThat(guard.resolveMethod(new HttpHeaders(), McpMethods.TOOLS_LIST)).isEqualTo(McpMethods.TOOLS_LIST);
    }

    @Test
    @DisplayName("头为空白时按未提供处理")
    void resolveMethodIgnoresBlankHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.METHOD, "   ");

        assertThat(guard.resolveMethod(headers, McpMethods.PING)).isEqualTo(McpMethods.PING);
    }

    @Test
    @DisplayName("Mcp-Name 头优先于 params.name")
    void resolveToolNamePrefersHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(McpHeaders.NAME, "fromHeader");
        JsonRpcRequest request = request(McpMethods.TOOLS_CALL, Json.obj().put("name", "fromBody"), null);

        assertThat(guard.resolveToolName(headers, request)).isEqualTo("fromHeader");
    }

    @Test
    @DisplayName("没有 Mcp-Name 头时回落到 params.name")
    void resolveToolNameFallsBackToParams() {
        JsonRpcRequest request = request(McpMethods.TOOLS_CALL, Json.obj().put("name", "fromBody"), null);

        assertThat(guard.resolveToolName(new HttpHeaders(), request)).isEqualTo("fromBody");
    }

    @Test
    @DisplayName("两处都没有 tool 名时返回 null，由分派层报 -32602")
    void resolveToolNameReturnsNullWhenAbsent() {
        assertThat(guard.resolveToolName(new HttpHeaders(), request(McpMethods.TOOLS_CALL, null, null))).isNull();
        assertThat(guard.resolveToolName(new HttpHeaders(), null)).isNull();
    }

    // ------------------------------------------------------------------ 夹具

    private static JsonRpcRequest request(String method, JsonNode params, JsonNode meta) {
        return new JsonRpcRequest("2.0", ID, method, params, meta);
    }

    private static LegacyProtocolException expectLegacy(Runnable runnable) {
        try {
            runnable.run();
        } catch (LegacyProtocolException e) {
            return e;
        }
        throw new AssertionError("期望拒绝 legacy 协议形态，但请求被放行了");
    }

    private static McpErrorException expectError(Runnable runnable) {
        try {
            runnable.run();
        } catch (McpErrorException e) {
            return e;
        }
        throw new AssertionError("期望抛出 McpErrorException");
    }
}