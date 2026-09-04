package com.mcpbridge.executor.upstream;

import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.executor.auth.UpstreamCredentials;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.mcp.McpErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负载均衡选址与 URI 拼接（EXE-03 / EXE-04）。
 *
 * <p>这两件事都不碰网络，却是上游调用里最容易出错、出错后最难定位的部分：
 * 选错节点表现为「偶发超时」，拼错 URI 表现为「上游 404」，两者都不会留下任何指向桥接层的线索。
 * 所以这里直接对 {@code pickBaseUrl} / {@code buildUri} 做同包白盒断言，
 * 而不是靠起一个假上游服务器去间接验证——那样测到的是 Reactor Netty，不是我们的规则。
 */
class UpstreamInvokerTest {

    private final CircuitBreakerRegistry breakers = new CircuitBreakerRegistry();
    private final UpstreamInvoker invoker = new UpstreamInvoker(properties(), breakers);

    // ------------------------------------------------------------------ 选址

    @Test
    @DisplayName("只有一个上游地址时直接返回，不进入任何计数逻辑")
    void returnsSingleBaseUrlWithoutCounting() {
        ServerSnapshot server = server(30L, upstream(
                UpstreamSnapshot.LbStrategy.WEIGHTED, List.of(5), List.of("http://only")));

        assertThat(invoker.pickBaseUrl(server, server.upstream(), server.upstream().baseUrls()))
                .isEqualTo("http://only");
    }

    @Test
    @DisplayName("ROUND_ROBIN 按顺序轮转，多实例部署时各节点承担均等流量")
    void rotatesRoundRobin() {
        ServerSnapshot server = server(31L, upstream(
                UpstreamSnapshot.LbStrategy.ROUND_ROBIN, List.of(),
                List.of("http://a", "http://b", "http://c")));

        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            picks.add(invoker.pickBaseUrl(server, server.upstream(), server.upstream().baseUrls()));
        }

        assertThat(picks).containsExactly("http://a", "http://b", "http://c", "http://a", "http://b", "http://c");
    }

    @Test
    @DisplayName("WEIGHTED 按权重分配：[1,3] 在 8 次调用里得到 2:6")
    void distributesByWeight() {
        ServerSnapshot server = server(32L, upstream(
                UpstreamSnapshot.LbStrategy.WEIGHTED, List.of(1, 3),
                List.of("http://light", "http://heavy")));

        assertThat(tally(server, 8))
                .containsEntry("http://light", 2)
                .containsEntry("http://heavy", 6);
    }

    @Test
    @DisplayName("权重全为 0 时退回轮询而不是报错：配错的权重不该让已发布端点直接不可用")
    void fallsBackToRoundRobinWhenWeightsAreAllZero() {
        ServerSnapshot server = server(33L, upstream(
                UpstreamSnapshot.LbStrategy.WEIGHTED, List.of(0, 0),
                List.of("http://a", "http://b")));

        assertThat(tally(server, 8)).containsEntry("http://a", 4).containsEntry("http://b", 4);
    }

    @Test
    @DisplayName("权重数量与地址数量不一致时退回轮询")
    void fallsBackToRoundRobinWhenWeightsLengthMismatch() {
        ServerSnapshot server = server(34L, upstream(
                UpstreamSnapshot.LbStrategy.WEIGHTED, List.of(1),
                List.of("http://a", "http://b")));

        assertThat(tally(server, 8)).containsEntry("http://a", 4).containsEntry("http://b", 4);
    }

    @Test
    @DisplayName("负权重按 0 处理，不会让累积判断错位而选中不该选的节点")
    void clampsNegativeWeightToZero() {
        ServerSnapshot server = server(35L, upstream(
                UpstreamSnapshot.LbStrategy.WEIGHTED, List.of(-5, 2),
                List.of("http://a", "http://b")));

        assertThat(tally(server, 4)).containsEntry("http://b", 4).doesNotContainKey("http://a");
    }

    // ------------------------------------------------------------------ URI 拼接

    @Test
    @DisplayName("查询值里的空格编码为 %20 而不是 +：+ 只在表单编码里代表空格")
    void encodesQueryValuesWithPercent20() {
        RestRequest request = request("/orders/a%20b", new RestRequest.QueryParam("q", "hello world"));

        assertThat(invoker.buildUri("http://up.local/", request, Map.of("api_key", "k1")))
                .isEqualTo("http://up.local/orders/a%20b?q=hello%20world&api_key=k1");
    }

    @Test
    @DisplayName("查询值里的花括号被编码，URI 不再被当作模板二次展开（关键回归）")
    void encodesBracesSoUriIsNotTreatedAsTemplate() {
        RestRequest request = request("/x", new RestRequest.QueryParam("note", "a{b}c"));

        String uri = invoker.buildUri("http://up.local", request, Map.of());

        assertThat(uri).isEqualTo("http://up.local/x?note=a%7Bb%7Dc");
        // 若把这样的字符串交给 WebClient.uri(String)，"{b}" 会被当成待填充的模板变量，
        // 直接抛 "Not enough variable values"。这里断言它已经是合法的、不含模板占位符的 URI。
        assertThat(URI.create(uri).toString()).isEqualTo(uri);
        assertThat(uri).doesNotContain("{");
    }

    @Test
    @DisplayName("路径本身已带查询串时用 & 续接，不会产生两个 ?")
    void usesAmpersandWhenPathAlreadyHasQuery() {
        RestRequest request = request("/search?q=1");

        assertThat(invoker.buildUri("http://up.local", request, Map.of("api_key", "k")))
                .isEqualTo("http://up.local/search?q=1&api_key=k");
    }

    @Test
    @DisplayName("baseUrl 末尾多余的斜杠被吃掉，避免出现 //orders")
    void stripsTrailingSlashesFromBaseUrl() {
        assertThat(invoker.buildUri("http://up.local///", request("/orders"), Map.of()))
                .isEqualTo("http://up.local/orders");
    }

    @Test
    @DisplayName("无查询参数且无上行凭据时不产生问号")
    void omitsQueryStringWhenNoParameters() {
        RestRequest request = new RestRequest("GET", "/orders", null, Map.of(), null);

        assertThat(invoker.buildUri("http://up.local", request, null)).isEqualTo("http://up.local/orders");
    }

    @Test
    @DisplayName("上行凭据值为 null 时编码为空串，不写出字面量 null")
    void encodesNullCredentialValueAsEmpty() {
        Map<String, String> credentials = new HashMap<>();
        credentials.put("api_key", null);

        assertThat(invoker.buildUri("http://up.local", request("/x"), credentials))
                .isEqualTo("http://up.local/x?api_key=");
    }

    // ------------------------------------------------------------------ 调用前的硬校验

    @Test
    @DisplayName("上游地址为空时回 -32003，不发起任何请求")
    void rejectsWhenNoBaseUrlConfigured() {
        ServerSnapshot server = server(40L, UpstreamSnapshot.defaults(List.of()));

        StepVerifier.create(invoker.invoke(server, tool("getOrder"), request("/orders"), UpstreamCredentials.empty()))
                .expectErrorSatisfies(t -> {
                    McpErrorException e = expectMcp(t);
                    assertThat(e.httpStatus()).isEqualTo(502);
                    assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.UPSTREAM_ERROR);
                    assertThat(e.error().message()).contains("order");
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("上游地址全是空白串时同样视为未配置")
    void rejectsWhenAllBaseUrlsBlank() {
        ServerSnapshot server = server(41L, UpstreamSnapshot.defaults(List.of("  ", "")));

        StepVerifier.create(invoker.invoke(server, tool("getOrder"), request("/orders"), UpstreamCredentials.empty()))
                .expectErrorSatisfies(t -> assertThat(expectMcp(t).error().code())
                        .isEqualTo(JsonRpcErrorCodes.UPSTREAM_ERROR))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("熔断已打开时直接拒绝，并在 data 里说明原因与剩余熔断时长")
    void rejectsWhenCircuitBreakerOpen() {
        UpstreamSnapshot upstream = new UpstreamSnapshot(List.of("http://up.local"),
                UpstreamSnapshot.LbStrategy.ROUND_ROBIN, List.of(), 3_000L, 30_000L, 1, List.of(502),
                new UpstreamSnapshot.CircuitBreaker(1, 60_000L, 1));
        ServerSnapshot server = server(42L, upstream);
        breakers.onFailure(42L, upstream.circuitBreaker());

        assertThat(breakers.states()).containsEntry(42L, CircuitBreakerRegistry.State.OPEN);

        StepVerifier.create(invoker.invoke(server, tool("getOrder"), request("/orders"), UpstreamCredentials.empty()))
                .expectErrorSatisfies(t -> {
                    McpErrorException e = expectMcp(t);
                    assertThat(e.error().code()).isEqualTo(JsonRpcErrorCodes.UPSTREAM_ERROR);
                    assertThat(dataOf(e))
                            .containsEntry("circuitBreaker", "OPEN")
                            .containsEntry("openMs", 60_000L);
                })
                .verify(Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ 夹具

    private Map<String, Integer> tally(ServerSnapshot server, int calls) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < calls; i++) {
            counts.merge(invoker.pickBaseUrl(server, server.upstream(), server.upstream().baseUrls()),
                    1, Integer::sum);
        }
        return counts;
    }

    private static RestRequest request(String path, RestRequest.QueryParam... query) {
        return new RestRequest("GET", path, List.of(query), Map.of(), null);
    }

    private static UpstreamSnapshot upstream(UpstreamSnapshot.LbStrategy strategy,
                                             List<Integer> weights,
                                             List<String> baseUrls) {
        return new UpstreamSnapshot(baseUrls, strategy, weights, 3_000L, 30_000L, 1,
                List.of(502, 503, 504), UpstreamSnapshot.CircuitBreaker.defaults());
    }

    private static ServerSnapshot server(long serverId, UpstreamSnapshot upstream) {
        return new ServerSnapshot(serverId, 1L, "order-service", "order", "订单服务", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/order",
                null, null, upstream, List.of(), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static ToolSnapshot tool(String name) {
        return new ToolSnapshot(name, null, null, "GET", "/orders", "GET /orders",
                null, Map.of(), false, false, null, true, null);
    }

    private static ExecutorProperties properties() {
        return new ExecutorProperties(
                new ExecutorProperties.Manager("http://localhost:8080", "token", null, "default",
                        Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofSeconds(10), Duration.ofSeconds(5)),
                new ExecutorProperties.Node("", "127.0.0.1", 9090, "0.1.0"),
                new ExecutorProperties.Redis(false, "redis://localhost:6379", null, 0, "mcp",
                        Duration.ofMinutes(30), Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExecutorProperties.Upstream(Duration.ofSeconds(3), Duration.ofSeconds(30), 1_048_576),
                new ExecutorProperties.Protocol("mcp", 30_000));
    }

    private static McpErrorException expectMcp(Throwable throwable) {
        assertThat(throwable).isInstanceOf(McpErrorException.class);
        return (McpErrorException) throwable;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(McpErrorException e) {
        return e.error().data() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}