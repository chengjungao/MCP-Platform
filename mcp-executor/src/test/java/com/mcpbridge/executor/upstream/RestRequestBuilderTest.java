package com.mcpbridge.executor.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.executor.mcp.McpErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP arguments → 上游 REST 调用的参数定位（EXE-02）。
 *
 * <p>这里出的错在上游看来只是「参数缺失」或「路径 404」，几乎不可能回溯到桥接层，
 * 因此每一类定位规则都要有独立的断言。
 */
class RestRequestBuilderTest {

    private final RestRequestBuilder builder = new RestRequestBuilder();

    @Test
    @DisplayName("path 与 query 参数各归其位")
    void dispatchesPathAndQueryParameters() {
        ToolSnapshot tool = tool("getOrder", "GET", "/orders/{orderId}",
                Map.of("orderId", "path", "verbose", "query"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"orderId\":\"A-1\",\"verbose\":true}"));

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/orders/A-1");
        assertThat(request.query()).containsExactly(new RestRequest.QueryParam("verbose", "true"));
        assertThat(request.hasBody()).isFalse();
    }

    @Test
    @DisplayName("GET 的未标注参数默认进查询串")
    void defaultsUnmappedParametersToQueryForGet() {
        ToolSnapshot tool = tool("listOrders", "GET", "/orders", Map.of(), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"status\":\"NEW\"}"));

        assertThat(request.query()).containsExactly(new RestRequest.QueryParam("status", "NEW"));
    }

    @Test
    @DisplayName("POST 的未标注参数默认聚合成 JSON 对象体")
    void aggregatesUnmappedParametersIntoBodyForPost() {
        ToolSnapshot tool = tool("createOrder", "POST", "/orders/{tenant}",
                Map.of("tenant", "path"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"tenant\":\"t1\",\"amount\":5}"));

        assertThat(request.path()).isEqualTo("/orders/t1");
        assertThat(request.body()).isInstanceOf(Map.class);
        assertThat(bodyMap(request)).containsEntry("amount", 5).doesNotContainKey("tenant");
    }

    @Test
    @DisplayName("名为 body 的参数代表整个请求体，不再被包一层")
    void treatsParameterNamedBodyAsWholeRequestBody() {
        ToolSnapshot tool = tool("submitRaw", "POST", "/raw", Map.of("body", "body"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"body\":{\"a\":[1,2]}}"));

        assertThat(bodyMap(request)).containsKey("a");
        assertThat(bodyMap(request)).doesNotContainKey("body");
    }

    @Test
    @DisplayName("header 与 cookie 参数分别落到请求头与 Cookie 头")
    void dispatchesHeaderAndCookieParameters() {
        ToolSnapshot tool = tool("call", "GET", "/x",
                Map.of("X-Tenant", "header", "SESSION", "cookie"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"X-Tenant\":\"t1\",\"SESSION\":\"abc\"}"));

        assertThat(request.headers()).containsEntry("X-Tenant", "t1").containsEntry("Cookie", "SESSION=abc");
    }

    @Test
    @DisplayName("路径值被百分号编码：空格不是加号，斜杠不得改变上游路径结构")
    void encodesPathValues() {
        ToolSnapshot tool = tool("getOrder", "GET", "/orders/{id}", Map.of("id", "path"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"id\":\"a b/c?d\"}"));

        assertThat(request.path()).isEqualTo("/orders/a%20b%2Fc%3Fd");
    }

    @Test
    @DisplayName("查询串里的结构化参数被序列化为 JSON 文本")
    void serializesStructuredQueryValue() {
        ToolSnapshot tool = tool("search", "GET", "/search", Map.of("filter", "query"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"filter\":{\"state\":\"OPEN\"}}"));

        assertThat(request.query().get(0).value()).isEqualTo("{\"state\":\"OPEN\"}");
    }

    @Test
    @DisplayName("缺少路径参数直接报 -32602，绝不拼出 /orders/null 去打上游")
    void rejectsMissingPathParameter() {
        ToolSnapshot tool = tool("getOrder", "GET", "/orders/{id}", Map.of("id", "path"), false, null);

        McpErrorException e = expectError(() -> builder.build(server(), tool, args("{}")));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
        assertThat(e.error().message()).contains("id");
    }

    @Test
    @DisplayName("按 inputSchema.required 校验必填项，一次性报全所有缺失")
    void reportsAllMissingRequiredParameters() {
        JsonNode schema = Json.tree("{\"type\":\"object\",\"required\":[\"a\",\"b\",\"c\"]}");
        ToolSnapshot tool = tool("create", "POST", "/x", Map.of(), false, schema);

        McpErrorException e = expectError(() -> builder.build(server(), tool, args("{\"a\":1}")));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
        assertThat(dataOf(e).get("missing")).isEqualTo(List.of("b", "c"));
    }

    @Test
    @DisplayName("显式 null 也算缺失必填项")
    void treatsExplicitNullAsMissingRequired() {
        JsonNode schema = Json.tree("{\"type\":\"object\",\"required\":[\"a\"]}");
        ToolSnapshot tool = tool("create", "POST", "/x", Map.of(), false, schema);

        assertThatThrownBy(() -> builder.build(server(), tool, args("{\"a\":null}")))
                .isInstanceOf(McpErrorException.class);
    }

    @Test
    @DisplayName("上游要求请求体但装配不出体时报 -32602")
    void rejectsMissingRequiredRequestBody() {
        ToolSnapshot tool = tool("create", "POST", "/orders", Map.of(), true, null);

        McpErrorException e = expectError(() -> builder.build(server(), tool, null));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
    }

    @Test
    @DisplayName("arguments 不是对象时报 -32602 并说明实际类型")
    void rejectsNonObjectArguments() {
        ToolSnapshot tool = tool("list", "GET", "/orders", Map.of(), false, null);

        McpErrorException e = expectError(() -> builder.build(server(), tool, Json.tree("[1,2]")));

        assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.INVALID_PARAMS);
        assertThat(dataOf(e)).containsEntry("actualType", "ARRAY");
    }

    @Test
    @DisplayName("未知位置标记回落到默认位置而不是抛错：文档漂移不该让端点直接不可用")
    void fallsBackOnUnknownParameterLocation() {
        ToolSnapshot tool = tool("list", "GET", "/orders", Map.of("status", "formData"), false, null);

        RestRequest request = builder.build(server(), tool, args("{\"status\":\"NEW\"}"));

        assertThat(request.query()).containsExactly(new RestRequest.QueryParam("status", "NEW"));
    }

    @Test
    @DisplayName("路径不带前导斜杠时补上，避免与 baseUrl 拼接出错")
    void prependsLeadingSlash() {
        ToolSnapshot tool = tool("ping", "GET", "ping", Map.of(), false, null);

        assertThat(builder.build(server(), tool, args("{}")).path()).isEqualTo("/ping");
    }

    @Test
    @DisplayName("无参 tool 产出空查询、空体，方法名统一大写")
    void buildsParameterlessRequest() {
        ToolSnapshot tool = tool("ping", "get", "/ping", Map.of(), false, null);

        RestRequest request = builder.build(server(), tool, null);

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/ping");
        assertThat(request.query()).isEmpty();
        assertThat(request.headers()).isEmpty();
        assertThat(request.hasBody()).isFalse();
    }

    // ------------------------------------------------------------------ 夹具

    private static ServerSnapshot server() {
        return new ServerSnapshot(1L, 1L, "order-service", "order", "订单服务", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/order",
                null, null, UpstreamSnapshot.defaults(List.of("http://up.local")),
                List.of(), List.of(), List.of(), 30_000, Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static ToolSnapshot tool(String name, String method, String path,
                                     Map<String, String> parameterIn,
                                     boolean bodyRequired, JsonNode inputSchema) {
        return new ToolSnapshot(name, null, null, method, path, method + " " + path,
                inputSchema, parameterIn, bodyRequired, false, null,
                ToolSnapshot.IDEMPOTENT_METHODS.contains(method), null);
    }

    private static JsonNode args(String json) {
        return Json.tree(json);
    }

    private static McpErrorException expectError(Runnable runnable) {
        try {
            runnable.run();
        } catch (McpErrorException e) {
            return e;
        }
        throw new AssertionError("期望抛出 McpErrorException");
    }

    /**
     * 请求体取回 Map 形态以便断言。
     *
     * <p>不能写成 {@code (Map<?, ?>) body}：那样 AssertJ 会给到 {@code MapAssert<?, ?>}，
     * {@code containsEntry("k", v)} 的键类型是通配符捕获，传 String 直接编译不过。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyMap(RestRequest request) {
        return (Map<String, Object>) request.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(McpErrorException e) {
        return e.error().data() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}