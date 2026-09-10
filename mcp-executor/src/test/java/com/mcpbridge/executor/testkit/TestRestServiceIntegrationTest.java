package com.mcpbridge.executor.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.executor.auth.UpstreamCredentials;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import com.mcpbridge.executor.upstream.RestRequest;
import com.mcpbridge.executor.upstream.UpstreamInvoker;
import com.mcpbridge.executor.upstream.UpstreamResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TestRestService × UpstreamInvoker 的真实网络冒烟。
 *
 * <p>与 {@code UpstreamInvokerTest} 的分工：那边对选址/URI 拼接做白盒断言、刻意不起假服务器
 * （测到的是 Reactor Netty 而非平台规则）；这边恰恰相反——用 {@link TestRestService} 起一个
 * 真实 HTTP 上游，让 {@link UpstreamInvoker} 完整走 exchange → 重试 → 响应读取的真链路，
 * 证明桥接层真的能把请求打到业务系统、拿到响应。两类测试互为补充，缺一不可。
 */
class TestRestServiceIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CircuitBreakerRegistry breakers = new CircuitBreakerRegistry();
    private final UpstreamInvoker invoker = new UpstreamInvoker(properties(), breakers);
    private final AtomicLong serverIds = new AtomicLong(500);

    private TestRestService upstream;

    @BeforeEach
    void startUpstream() {
        upstream = new TestRestService(0).start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.close();
    }

    @Test
    @DisplayName("GET 详情：真实 HTTP 打到假上游并拿回 200 与 JSON 体")
    void getOrderReturns200() {
        StepVerifier.create(invoker.invoke(server("getOrder", "GET"), tool("getOrder", "GET", "/api/orders/1"),
                        request("GET", "/api/orders/1"), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.isSuccess()).isTrue();
                    JsonNode body = readBody(response);
                    assertThat(body.path("id").asLong()).isEqualTo(1L);
                    assertThat(body.path("status").asText()).isEqualTo("PAID");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        assertThat(upstream.captured()).hasSize(1);
        TestRestService.CapturedRequest hit = upstream.captured().get(0);
        assertThat(hit.method()).isEqualTo("GET");
        assertThat(hit.path()).isEqualTo("/api/orders/1");
    }

    @Test
    @DisplayName("GET 不存在的订单：上游 404 原样透传，不算链路故障（isServerError=false）")
    void getMissingOrderReturns404() {
        StepVerifier.create(invoker.invoke(server("getOrder", "GET"), tool("getOrder", "GET", "/api/orders/999"),
                        request("GET", "/api/orders/999"), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(404);
                    assertThat(response.isSuccess()).isFalse();
                    assertThat(response.isServerError()).isFalse();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("POST 创建：请求体透传，上游 201 + Location，落库可查")
    void createOrderReturns201() {
        Map<String, Object> body = Map.of("customerId", 9L, "amount", 42.5);
        StepVerifier.create(invoker.invoke(server("createOrder", "POST"),
                        tool("createOrder", "POST", "/api/orders"),
                        request("POST", "/api/orders", body), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(201);
                    assertThat(response.headers().getFirst("Location")).isNotBlank();
                    JsonNode created = readBody(response);
                    assertThat(created.path("customerId").asLong()).isEqualTo(9L);
                    assertThat(created.path("status").asText()).isEqualTo("CREATED");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        TestRestService.CapturedRequest hit = upstream.captured().get(0);
        assertThat(hit.method()).isEqualTo("POST");
        assertThat(hit.body()).contains("\"customerId\"", "42.5");
        // 非幂等方法不重试，只打到上游一次
        assertThat(upstream.captured()).hasSize(1);
    }

    @Test
    @DisplayName("上游瞬断 503：幂等 GET 自动重试一次后拿到 200（failNext 只注入一次故障）")
    void retriesOnceAfterTransient503() {
        upstream.failNext(1, 503);

        StepVerifier.create(invoker.invoke(server("getOrder", "GET"), tool("getOrder", "GET", "/api/orders/1"),
                        request("GET", "/api/orders/1"), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.isSuccess()).isTrue();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // 第一次请求吃到注入的 503，重试的那次才是 200
        assertThat(upstream.captured()).hasSize(2);
    }

    @Test
    @DisplayName("列表查询参数按序透传，status 过滤生效（total=1）")
    void listOrdersPassesQueryThrough() {
        List<RestRequest.QueryParam> query = List.of(
                new RestRequest.QueryParam("status", "PAID"),
                new RestRequest.QueryParam("page", "1"),
                new RestRequest.QueryParam("size", "10"));
        StepVerifier.create(invoker.invoke(server("listOrders", "GET"), tool("listOrders", "GET", "/api/orders"),
                        request("GET", "/api/orders", query), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(readBody(response).path("total").asLong()).isEqualTo(1L);
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        TestRestService.CapturedRequest hit = upstream.captured().get(0);
        assertThat(hit.rawQuery()).isEqualTo("status=PAID&page=1&size=10");
    }

    @Test
    @DisplayName("第二个业务域（user）同样可调用：一进程多服务、多文档语义成立")
    void getUserFromSecondDomain() {
        StepVerifier.create(invoker.invoke(server("getUser", "GET"), tool("getUser", "GET", "/api/users/1"),
                        request("GET", "/api/users/1"), UpstreamCredentials.empty()))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(200);
                    JsonNode body = readBody(response);
                    assertThat(body.path("name").asText()).isEqualTo("alice");
                    assertThat(body.path("email").asText()).isEqualTo("alice@example.com");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("OpenAPI 文档端点可达：order/user 两份文档都能 serve 且含关键 operationId")
    void servesOpenApiDocs() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String orderDoc = fetch(client, upstream.baseUrl() + "/v3/api-docs?scope=order");
        assertThat(orderDoc).contains("\"getOrder\"", "\"createOrder\"", "\"/api/orders/{orderId}\"");

        String userDoc = fetch(client, upstream.baseUrl() + "/v3/api-docs?scope=user");
        assertThat(userDoc).contains("\"getUser\"", "\"createUser\"", "\"/api/users/{userId}\"");
    }

    // ------------------------------------------------------------------ 夹具

    private static String fetch(HttpClient client, String url) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private JsonNode readBody(UpstreamResponse response) {
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON: " + response.body(), e);
        }
    }

    private ServerSnapshot server(String serverName, String method) {
        UpstreamSnapshot upstreamSnapshot = new UpstreamSnapshot(List.of(upstream.baseUrl()),
                UpstreamSnapshot.LbStrategy.ROUND_ROBIN, List.of(),
                3_000L, 30_000L, 1, List.of(502, 503, 504),
                UpstreamSnapshot.CircuitBreaker.defaults());
        return new ServerSnapshot(
                serverIds.incrementAndGet(), 1L, serverName, "order", serverName, null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/order",
                null, null,
                List.of(com.mcpbridge.common.snapshot.UpstreamEntry.single("default", upstreamSnapshot)),
                List.of(), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static ToolSnapshot tool(String name, String method, String path) {
        // 幂等性交给方法判断（GET/PUT/DELETE 幂等、POST 不幂等），idempotent 字段置 false 以贴近解析器默认
        return new ToolSnapshot(name, null, null, method, path, method + " " + path,
                null, Map.of(), method.equals("POST"), false, null, false, "default", null);
    }

    private static RestRequest request(String method, String path) {
        return new RestRequest(method, path, null, Map.of(), null);
    }

    private static RestRequest request(String method, String path, List<RestRequest.QueryParam> query) {
        return new RestRequest(method, path, query, Map.of(), null);
    }

    private static RestRequest request(String method, String path, Object body) {
        return new RestRequest(method, path, null, Map.of(), body);
    }

    private static ExecutorProperties properties() {
        return new ExecutorProperties(
                new ExecutorProperties.Manager("http://localhost:8080", "token", null, "default",
                        Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofSeconds(10), Duration.ofSeconds(5)),
                new ExecutorProperties.Node("", "127.0.0.1", 9090, "0.1.0"),
                new ExecutorProperties.Redis(false, ExecutorProperties.Redis.Mode.SINGLE,
                        "redis://localhost:6379", null, null, 0, "mcp",
                        Duration.ofMinutes(30), Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExecutorProperties.Upstream(Duration.ofSeconds(3), Duration.ofSeconds(30), 1_048_576),
                new ExecutorProperties.Protocol("mcp", 30_000));
    }
}
