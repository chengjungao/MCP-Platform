package com.mcpbridge.executor.metrics;

import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.executor.snapshot.SnapshotStore;
import com.mcpbridge.executor.testkit.TestMetrics;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据面业务指标（OPS-01）。
 *
 * <p>指标的价值全在「名字与标签是否稳定」：名字改了看板就断，标签多一个少一个序列就对不上。
 * 所以这里断言的是<b>对外的契约</b>（指标名、标签键、标签值），而不是内部实现——
 * 重构内部结构不该让这些断言变红，但改错一个标签值必须让它变红。
 */
class ExecutorMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final CircuitBreakerRegistry breakers = new CircuitBreakerRegistry();
    private final SnapshotStore snapshotStore = new SnapshotStore();
    private final ExecutorMetrics metrics = TestMetrics.create(registry, snapshotStore, breakers);

    @Test
    @DisplayName("tool 调用按 outcome 分开计数：上游 4xx/5xx 不算平台故障")
    void countsToolCallsByOutcome() {
        metrics.recordToolCall("order", "get_order", ExecutorMetrics.ToolOutcome.SUCCESS, null);
        metrics.recordToolCall("order", "get_order", ExecutorMetrics.ToolOutcome.UPSTREAM_ERROR, null);
        metrics.recordToolCall("order", "get_order", ExecutorMetrics.ToolOutcome.PLATFORM_ERROR, null);

        assertThat(counter(ExecutorMetrics.TOOL_CALLS,
                "path_segment", "order", "tool", "get_order", "outcome", "success")).isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.TOOL_CALLS,
                "path_segment", "order", "tool", "get_order", "outcome", "upstream_error")).isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.TOOL_CALLS,
                "path_segment", "order", "tool", "get_order", "outcome", "platform_error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tool 耗时单独成表且不带 outcome 标签——否则同一接口的 P99 会被切成三份")
    void toolDurationIsNotSplitByOutcome() {
        metrics.recordToolCall("order", "get_order", ExecutorMetrics.ToolOutcome.SUCCESS, metrics.startTimer());

        assertThat(registry.find(ExecutorMetrics.TOOL_CALL_DURATION)
                .tag("path_segment", "order").tag("tool", "get_order").timer()).isNotNull();
        assertThat(registry.find(ExecutorMetrics.TOOL_CALL_DURATION).timers())
                .hasSize(1)
                .allSatisfy(timer -> assertThat(timer.getId().getTag("outcome")).isNull());
    }

    @Test
    @DisplayName("响应被截断要能计数：客户端拿到的是不完整数据，不该只留在日志里")
    void countsTruncatedResponses() {
        metrics.recordTruncatedResponse("order", "get_order");

        assertThat(counter(ExecutorMetrics.TOOL_RESPONSE_TRUNCATED,
                "path_segment", "order", "tool", "get_order")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("上游尝试按状态码归类，4xx/5xx 同时落入 failures")
    void bucketsUpstreamAttemptsByStatusClass() {
        metrics.recordUpstreamAttempt("10:default", 200);
        metrics.recordUpstreamAttempt("10:default", 404);
        metrics.recordUpstreamAttempt("10:default", 503);

        assertThat(counter(ExecutorMetrics.UPSTREAM_REQUESTS, "service", "10:default", "status_class", "2xx"))
                .isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.UPSTREAM_REQUESTS, "service", "10:default", "status_class", "4xx"))
                .isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.UPSTREAM_REQUESTS, "service", "10:default", "status_class", "5xx"))
                .isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.UPSTREAM_FAILURES, "service", "10:default", "kind", "client_error"))
                .isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.UPSTREAM_FAILURES, "service", "10:default", "kind", "server_error"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("连接失败归为 transport 且 status_class=none——「没发出去」与「返回 5xx」处置动作不同")
    void separatesTransportFailuresFromHttpErrors() {
        metrics.recordUpstreamTransportFailure("10:default");

        assertThat(counter(ExecutorMetrics.UPSTREAM_REQUESTS, "service", "10:default", "status_class", "none"))
                .isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.UPSTREAM_FAILURES, "service", "10:default", "kind", "transport"))
                .isEqualTo(1.0);
        assertThat(registry.find(ExecutorMetrics.UPSTREAM_FAILURES).tag("kind", "server_error").counters())
                .isEmpty();
    }

    @Test
    @DisplayName("重试单独计数：上游实际承受的流量 = requests（已含重试），重试率要能算出来")
    void countsRetriesSeparately() {
        metrics.recordUpstreamRetry("10:default");
        metrics.recordUpstreamRetry("10:default");

        assertThat(counter(ExecutorMetrics.UPSTREAM_RETRIES, "service", "10:default")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("WEIGHTED 权重退化到轮询要能计数——配了权重却没生效必须被发现")
    void countsWeightedFallback() {
        metrics.recordWeightedFallback("order");

        assertThat(counter(ExecutorMetrics.LB_WEIGHTED_FALLBACK, "path_segment", "order")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("熔断状态以 MultiGauge 展开，数值编码 0=CLOSED 1=OPEN 2=HALF_OPEN")
    void exposesCircuitStateAsGauge() {
        String key = CircuitBreakerRegistry.key(9L, "default");
        UpstreamSnapshot.CircuitBreaker aggressive = new UpstreamSnapshot.CircuitBreaker(1, 60_000L, 2);

        // 先全量刷新一次，让 CLOSED 也出现在看板上——「从来没打开过」和「指标没报」是两回事
        breakers.allow(key, aggressive);
        metrics.refreshStateMetrics();
        assertThat(gauge(ExecutorMetrics.CIRCUIT_STATE, "service", key)).isEqualTo(0.0);

        breakers.onFailure(key, aggressive);
        metrics.refreshStateMetrics();
        assertThat(gauge(ExecutorMetrics.CIRCUIT_STATE, "service", key)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("熔断被拒与熔断打开分开计数：前者是「请求没发出去」，后者是「上游真的挂了」")
    void countsCircuitTripAndRejectionSeparately() {
        metrics.recordCircuitTrip("9:default");
        metrics.recordCircuitRejection("9:default");
        metrics.recordCircuitRejection("9:default");

        assertThat(counter(ExecutorMetrics.CIRCUIT_TRIPS, "service", "9:default")).isEqualTo(1.0);
        assertThat(counter(ExecutorMetrics.CIRCUIT_REJECTIONS, "service", "9:default")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("快照指标由 refresh 推到最新：revision / 就绪 / 规模三项对上 status()")
    void refreshesSnapshotGaugesFromStore() {
        metrics.refreshStateMetrics();
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_READY)).isZero();
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_REVISION)).isZero();

        snapshotStore.replace(new PublishedSnapshot(7L, "\"7\"", Instant.parse("2026-09-03T00:00:00Z"),
                "shared:default", List.of(server(10L, "order", tool("getOrder"), tool("createOrder")))));
        metrics.refreshStateMetrics();

        assertThat(gauge(ExecutorMetrics.SNAPSHOT_READY)).isEqualTo(1.0);
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_REVISION)).isEqualTo(7.0);
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_SERVERS)).isEqualTo(1.0);
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_TOOLS)).isEqualTo(2.0);
        assertThat(gauge(ExecutorMetrics.SNAPSHOT_APPLIED_REVISIONS)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("空标签值用 '-' 占位，避免与「忘了打标签」混淆")
    void replacesBlankTagValuesWithPlaceholder() {
        metrics.recordToolCall(null, "  ", ExecutorMetrics.ToolOutcome.SUCCESS, null);

        assertThat(counter(ExecutorMetrics.TOOL_CALLS,
                "path_segment", "-", "tool", "-", "outcome", "success")).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ 取数辅助

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private static ServerSnapshot server(long serverId, String pathSegment, ToolSnapshot... tools) {
        return new ServerSnapshot(serverId, 1L, "svc-" + serverId, pathSegment, "标题", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/" + pathSegment,
                null, null,
                List.of(com.mcpbridge.common.snapshot.UpstreamEntry.single("default",
                        UpstreamSnapshot.defaults(List.of("http://up.local")))),
                List.of(tools), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static ToolSnapshot tool(String name) {
        return new ToolSnapshot(name, null, null, "GET", "/x", "GET /x",
                null, Map.of(), false, false, null, true, "default", null);
    }
}
