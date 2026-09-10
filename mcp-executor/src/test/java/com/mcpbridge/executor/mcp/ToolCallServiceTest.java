package com.mcpbridge.executor.mcp;

import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.TraceContext;
import com.mcpbridge.executor.auth.UpstreamCredentialProvider;
import com.mcpbridge.executor.auth.UpstreamCredentials;
import com.mcpbridge.executor.metrics.ExecutorMetrics;
import com.mcpbridge.executor.testkit.TestMetrics;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import com.mcpbridge.executor.upstream.RestRequest;
import com.mcpbridge.executor.upstream.RestRequestBuilder;
import com.mcpbridge.executor.upstream.UpstreamInvoker;
import com.mcpbridge.executor.upstream.UpstreamResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tool 调用的编排：链路下推、指标记账、401 作废令牌（EXE-02 / EXE-03 / OPS-01 / OPS-02）。
 *
 * <p>这里用 mock 而不是起真上游，是因为要断言的恰好是「平台对下游做了什么」：
 * 传给 {@link UpstreamInvoker} 的 traceparent 长什么样、指标记成了哪个 outcome、
 * 什么情况下该作废缓存令牌。这些事实在真链路里被 Reactor Netty 的日志淹掉，白盒断言更可靠。
 */
class ToolCallServiceTest {

    private final RestRequestBuilder requestBuilder = mock(RestRequestBuilder.class);
    private final UpstreamInvoker invoker = mock(UpstreamInvoker.class);
    private final UpstreamCredentialProvider credentialProvider = mock(UpstreamCredentialProvider.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ExecutorMetrics metrics =
            TestMetrics.create(registry, new com.mcpbridge.executor.snapshot.SnapshotStore(),
                    new CircuitBreakerRegistry());
    private final ToolCallService service =
            new ToolCallService(requestBuilder, invoker, credentialProvider, metrics);

    private final ServerSnapshot server = server();
    private final ToolSnapshot tool = tool();

    @Test
    @DisplayName("OPS-02：发给上游的是本跳派生的子上下文——trace-id 不变、span-id 换新")
    void passesChildTraceContextUpstream() {
        TraceContext inbound = TraceContext.parse(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").orElseThrow();
        stubUpstream(new UpstreamResponse(200, new HttpHeaders(), "{\"ok\":true}", false));

        StepVerifier.create(service.call(server, tool, null, inbound))
                .assertNext(result -> assertThat(result.path("isError").asBoolean()).isFalse())
                .expectComplete()
                .verify();

        String traceparent = captureTraceparent();
        assertThat(traceparent).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-").endsWith("-01");
        assertThat(traceparent).as("span-id 必须是新的：它是「本次调用」在链路里的身份")
                .doesNotContain("00f067aa0ba902b7");
    }

    @Test
    @DisplayName("OPS-02：没有入站 trace 时不注入头，而不是注入一个空值")
    void omitsHeaderWhenTraceIsAbsent() {
        stubUpstream(new UpstreamResponse(200, new HttpHeaders(), "{}", false));

        StepVerifier.create(service.call(server, tool, null, null))
                .expectNextCount(1)
                .expectComplete()
                .verify();

        assertThat(captureTraceparent()).isNull();
    }

    @Test
    @DisplayName("OPS-01：上游 4xx/5xx 记 upstream_error，平台自身异常记 platform_error，两者不混淆")
    void recordsOutcomeSeparatelyFromPlatformErrors() {
        stubUpstream(new UpstreamResponse(503, new HttpHeaders(), "boom", false));

        StepVerifier.create(service.call(server, tool, null, null))
                .assertNext(result -> assertThat(result.path("isError").asBoolean()).isTrue())
                .expectComplete()
                .verify();

        assertThat(counter("upstream_error")).isEqualTo(1.0);
        assertThat(counter("platform_error")).isZero();
        assertThat(counter("success")).isZero();

        // 同一 tool 的一次平台异常（令牌获取失败）落到另一个 outcome
        when(credentialProvider.resolve(any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("令牌端点不可达")));
        StepVerifier.create(service.call(server, tool, null, null))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(counter("platform_error")).isEqualTo(1.0);
        assertThat(counter("upstream_error")).as("平台异常不该被算成上游错误").isEqualTo(1.0);
    }

    @Test
    @DisplayName("OPS-01：响应被截断时计数——客户端拿到的是不完整数据，必须可被观测")
    void recordsTruncatedResponse() {
        stubUpstream(new UpstreamResponse(200, new HttpHeaders(), "{\"a\":", true));

        StepVerifier.create(service.call(server, tool, null, null))
                .assertNext(result -> assertThat(result.path("_meta").path("responseTruncated").asBoolean()).isTrue())
                .expectComplete()
                .verify();

        assertThat(registry.get(ExecutorMetrics.TOOL_RESPONSE_TRUNCATED)
                .tag("path_segment", "order").tag("tool", "get_order").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("401 才作废上行令牌：403 是权限错配，作废只会把令牌端点打成筛子")
    void invalidatesTokenOnlyOn401() {
        stubUpstream(new UpstreamResponse(401, new HttpHeaders(), "unauthorized", false));
        StepVerifier.create(service.call(server, tool, null, null)).expectNextCount(1).expectComplete().verify();
        verify(credentialProvider).invalidate(server, tool);

        org.mockito.Mockito.reset(credentialProvider);
        stubUpstream(new UpstreamResponse(403, new HttpHeaders(), "forbidden", false));
        StepVerifier.create(service.call(server, tool, null, null)).expectNextCount(1).expectComplete().verify();
        verify(credentialProvider, never()).invalidate(any(), any());
    }

    // ------------------------------------------------------------------ 夹具

    private void stubUpstream(UpstreamResponse response) {
        when(requestBuilder.build(any(), any(), any()))
                .thenReturn(new RestRequest("GET", "/api/orders/1", null, Map.of(), null));
        when(credentialProvider.resolve(any(), any())).thenReturn(Mono.just(UpstreamCredentials.empty()));
        when(invoker.invoke(any(), any(), any(), any(), any())).thenReturn(Mono.just(response));
    }

    private String captureTraceparent() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(invoker).invoke(any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    /**
     * 读计数。用 {@code find} 而不是 {@code get}：某个 outcome 尚未出现过时 meter 根本不存在，
     * 而「没有这个 meter」正是「计数为 0」的表达，不该让断言以异常形式失败。
     */
    private double counter(String outcome) {
        io.micrometer.core.instrument.Counter found = registry.find(ExecutorMetrics.TOOL_CALLS)
                .tag("path_segment", "order").tag("tool", "get_order").tag("outcome", outcome)
                .counter();
        return found == null ? 0.0 : found.count();
    }

    private static ServerSnapshot server() {
        return new ServerSnapshot(7L, 1L, "order-svc", "order", "订单服务", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/order",
                null, null,
                List.of(com.mcpbridge.common.snapshot.UpstreamEntry.single("default",
                        UpstreamSnapshot.defaults(List.of("http://up.local")))),
                List.of(tool()), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static ToolSnapshot tool() {
        return new ToolSnapshot("get_order", null, null, "GET", "/api/orders/1", "GET /api/orders/1",
                null, Map.of(), false, false, null, true, "default", null);
    }
}
