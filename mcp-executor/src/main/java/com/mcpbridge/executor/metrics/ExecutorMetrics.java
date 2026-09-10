package com.mcpbridge.executor.metrics;

import com.mcpbridge.executor.snapshot.SnapshotStore;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据面业务指标（OPS-01）。
 *
 * <p>Spring Boot 的自动指标（JVM 堆、HTTP 请求、连接池）只能回答「进程还活着吗」，
 * 而运维实际要问的问题一个都答不了：哪个 tool 在被调、上游慢不慢、熔断是不是开着、
 * 节点上跑的是哪个版本的快照。这个类只做一件事——把这些事实用固定名字暴露给 Prometheus。
 *
 * <h2>命名与单位</h2>
 * 名字遵循 Micrometer 约定，<b>不加</b> {@code _total} / {@code _seconds} 后缀：
 * Prometheus 侧会按类型自动补全（计数器 → {@code _total}，计时器 → {@code _seconds}）。
 * 手写后缀会导致导出成 {@code ..._total_total}。
 *
 * <h2>关于基数（cardinality）</h2>
 * tool 级指标带 {@code path_segment} + {@code tool} 两个标签，序列数是「Server 数 × tool 数」——
 * 可控，因为一个集群内单 Server 的 tool 数上限是几百。这里<b>刻意不打</b>
 * {@code orderId} / {@code trace_id} 这类来自请求参数的标签：那会让序列数随流量无限增长，
 * 是 Prometheus 被打挂最经典的原因。要追单笔调用请用审计日志的 traceId，不要用指标。
 *
 * <h2>状态类指标为什么是轮询刷新</h2>
 * 快照 revision、熔断状态这类值的变化频率远低于请求频率，用固定周期刷新换取热路径零开销
 * （每次 requests 都去写 gauge 等于在每个请求上多一次无意义的加法）。代价是最长
 * {@code metrics-refresh-interval-ms} 的滞后，对告警与看板无影响。计数与耗时是实时的。
 */
@Component
public class ExecutorMetrics {

    // ---- tool 调用（EXE-02 / EXE-03） ----
    public static final String TOOL_CALLS = "mcp_executor_tool_calls";
    public static final String TOOL_CALL_DURATION = "mcp_executor_tool_call_duration";
    public static final String TOOL_RESPONSE_TRUNCATED = "mcp_executor_tool_response_truncated";

    // ---- 上游（EXE-03 / EXE-04） ----
    public static final String UPSTREAM_REQUESTS = "mcp_executor_upstream_requests";
    public static final String UPSTREAM_DURATION = "mcp_executor_upstream_duration";
    public static final String UPSTREAM_FAILURES = "mcp_executor_upstream_failures";
    public static final String UPSTREAM_RETRIES = "mcp_executor_upstream_retries";
    public static final String LB_WEIGHTED_FALLBACK = "mcp_executor_lb_weighted_fallback";

    // ---- 熔断（EXE-04） ----
    public static final String CIRCUIT_TRIPS = "mcp_executor_circuit_open";
    public static final String CIRCUIT_REJECTIONS = "mcp_executor_circuit_rejections";
    public static final String CIRCUIT_STATE = "mcp_executor_circuit_state";

    // ---- 快照（EXE-01 / EXE-05） ----
    public static final String SNAPSHOT_REVISION = "mcp_executor_snapshot_revision";
    public static final String SNAPSHOT_READY = "mcp_executor_snapshot_ready";
    public static final String SNAPSHOT_SERVERS = "mcp_executor_snapshot_servers";
    public static final String SNAPSHOT_TOOLS = "mcp_executor_snapshot_tools";
    public static final String SNAPSHOT_APPLIED_REVISIONS = "mcp_executor_snapshot_applied_revisions";

    /**
     * 熔断状态的数值编码。
     *
     * <p>刻意不直接用 {@code CircuitBreakerRegistry.State.ordinal()}：那样指标语义就绑在
     * 枚举的<b>声明顺序</b>上，以后有人为了可读性把 HALF_OPEN 挪到前面，看板会静默地给出反的结论。
     * 这里做一次显式映射，改动枚举顺序不再影响对外契约。
     */
    public enum CircuitState {
        CLOSED(0), OPEN(1), HALF_OPEN(2);

        private final int code;

        CircuitState(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static CircuitState of(CircuitBreakerRegistry.State state) {
            return switch (state) {
                case OPEN -> CircuitState.OPEN;
                case HALF_OPEN -> CircuitState.HALF_OPEN;
                case CLOSED -> CircuitState.CLOSED;
            };
        }
    }

    /** tool 调用的终局。上游返回 4xx/5xx 不算平台故障，因此单列一个 outcome。 */
    public enum ToolOutcome {
        SUCCESS("success"),
        UPSTREAM_ERROR("upstream_error"),
        PLATFORM_ERROR("platform_error");

        private final String tag;

        ToolOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final SnapshotStore snapshotStore;
    private final CircuitBreakerRegistry breakers;

    /**
     * 状态类指标的载体。放在字段里而不是每次构造新对象：Micrometer 的 Gauge 对 state 持有的是
     * <b>弱引用</b>，如果只把 holder 传给 builder 而不留强引用，一次 GC 之后 gauge 会静默变成 NaN。
     */
    private final AtomicLong snapshotRevision = new AtomicLong();
    private final AtomicInteger snapshotReady = new AtomicInteger();
    private final AtomicLong snapshotServers = new AtomicLong();
    private final AtomicLong snapshotTools = new AtomicLong();
    private final AtomicLong snapshotAppliedRevisions = new AtomicLong();

    private final MultiGauge circuitState;

    public ExecutorMetrics(MeterRegistry registry, SnapshotStore snapshotStore, CircuitBreakerRegistry breakers) {
        this.registry = registry;
        this.snapshotStore = snapshotStore;
        this.breakers = breakers;

        Gauge.builder(SNAPSHOT_REVISION, snapshotRevision, AtomicLong::get)
                .description("本节点当前加载的发布快照 revision；0 表示还没同步过")
                .register(registry);
        Gauge.builder(SNAPSHOT_READY, snapshotReady, AtomicInteger::get)
                .description("快照是否已成功同步过至少一次：1=就绪 0=未就绪（端点会返回 503）")
                .register(registry);
        Gauge.builder(SNAPSHOT_SERVERS, snapshotServers, AtomicLong::get)
                .description("快照内的 MCP Server 数")
                .register(registry);
        Gauge.builder(SNAPSHOT_TOOLS, snapshotTools, AtomicLong::get)
                .description("快照内的 tool 总数")
                .register(registry);
        Gauge.builder(SNAPSHOT_APPLIED_REVISIONS, snapshotAppliedRevisions, AtomicLong::get)
                .description("本进程启动以来实际应用的快照次数（用于发现「轮询在跑但 revision 一直没动」）")
                .register(registry);

        // 按 service 动态展开：熔断 key 是 serverId:serviceId，数量随已发布的对象增长，用 MultiGauge
        // 才能让「不再出现的 key」在下一轮被移除，而不是永远留一条假数据。
        this.circuitState = MultiGauge.builder(CIRCUIT_STATE)
                .description("上游熔断状态：0=CLOSED 1=OPEN 2=HALF_OPEN（按固定周期刷新）")
                .register(registry);
    }

    /** 计时起点。返回 null 安全的 sample，调用方无需判空。 */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    // ---------------------------------------------------------------- tool 调用

    /**
     * 记录一次 tool 调用的终局。
     *
     * <p>耗时与结果分开记：耗时不含 outcome 标签，否则同一个 tool 的 P99 会被切成三份，
     * 看板上再也看不出「这个接口本来就慢」。
     */
    public void recordToolCall(String pathSegment, String tool, ToolOutcome outcome, Timer.Sample sample) {
        Tags tags = Tags.of("path_segment", safe(pathSegment), "tool", safe(tool));
        registry.counter(TOOL_CALLS, tags.and("outcome", outcome.tag())).increment();
        stop(sample, TOOL_CALL_DURATION, tags);
    }

    public void recordTruncatedResponse(String pathSegment, String tool) {
        registry.counter(TOOL_RESPONSE_TRUNCATED,
                "path_segment", safe(pathSegment), "tool", safe(tool)).increment();
    }

    // ---------------------------------------------------------------- 上游

    /**
     * 记录一次<b>真正发出去</b>的上游尝试。
     *
     * <p>按「尝试」而不是「调用」计数：一次 tool 调用可能因重试发出多个请求，
     * 看板上要能看出「上游实际承受了多少流量」。总请求数 = {@code requests} 的次数（已含重试）。
     *
     * @param service {@code serverId:serviceId}，与熔断 key 同构，看板上可以直接与熔断状态对齐
     * @param status  HTTP 状态码
     */
    public void recordUpstreamAttempt(String service, int status) {
        registry.counter(UPSTREAM_REQUESTS, "service", safe(service), "status_class", statusClass(status)).increment();
        if (status >= 500) {
            registry.counter(UPSTREAM_FAILURES, "service", safe(service), "kind", "server_error").increment();
        } else if (status >= 400) {
            registry.counter(UPSTREAM_FAILURES, "service", safe(service), "kind", "client_error").increment();
        }
    }

    /**
     * 请求根本没拿到响应（连接被拒、DNS 失败、读超时、上游提前断连）。
     *
     * <p>与「返回了 5xx」分开统计：前者要查网络与实例存活，后者要查上游应用本身，处置动作完全不同。
     */
    public void recordUpstreamTransportFailure(String service) {
        registry.counter(UPSTREAM_REQUESTS, "service", safe(service), "status_class", "none").increment();
        registry.counter(UPSTREAM_FAILURES, "service", safe(service), "kind", "transport").increment();
    }

    /**
     * 记录整个 {@code invoke} 的耗时，<b>含重试</b>。
     *
     * <p>不含重试的耗时是好看的数字，但调用方实际等的是含重试的那一个——SLO 要按后者定。
     */
    public void recordUpstreamDuration(String service, Timer.Sample sample) {
        stop(sample, UPSTREAM_DURATION, Tags.of("service", safe(service)));
    }

    public void recordUpstreamRetry(String service) {
        registry.counter(UPSTREAM_RETRIES, "service", safe(service)).increment();
    }

    /** WEIGHTED 配置了但权重不可用（缺失 / 长度不符 / 全 0），本次退回轮询。 */
    public void recordWeightedFallback(String pathSegment) {
        registry.counter(LB_WEIGHTED_FALLBACK, "path_segment", safe(pathSegment)).increment();
    }

    // ---------------------------------------------------------------- 熔断

    /** 熔断被打开（不是被拒）。持续增长说明某个上游在反复抖动。 */
    public void recordCircuitTrip(String service) {
        registry.counter(CIRCUIT_TRIPS, "service", safe(service)).increment();
    }

    /** 请求因熔断打开而被直接拒绝，没有发出去。 */
    public void recordCircuitRejection(String service) {
        registry.counter(CIRCUIT_REJECTIONS, "service", safe(service)).increment();
    }

    // ---------------------------------------------------------------- 状态刷新

    /**
     * 固定周期把所有「状态类」指标推到最新。
     *
     * <p>公开方法是为了让测试能直接驱动它，不需要真的等一个调度周期。
     */
    @Scheduled(fixedDelayString = "${mcp.executor.metrics-refresh-interval-ms:15000}")
    public void refreshStateMetrics() {
        SnapshotStore.Status status = snapshotStore.status();
        snapshotReady.set(status.ready() ? 1 : 0);
        snapshotRevision.set(status.revision());
        snapshotServers.set(status.serverCount());
        snapshotTools.set(status.toolCount());
        snapshotAppliedRevisions.set(status.appliedRevisions());
        refreshCircuitStates();
    }

    private void refreshCircuitStates() {
        Map<String, CircuitBreakerRegistry.State> states = breakers.states();
        List<MultiGauge.Row<?>> rows = new ArrayList<>(states.size());
        states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rows.add(MultiGauge.Row.of(
                        Tags.of("service", entry.getKey()),
                        CircuitState.of(entry.getValue()).code())));
        // overwrite=true：本轮没出现的 key 会被移除，避免已下线 Server 的熔断状态永远挂在看板上
        circuitState.register(rows, true);
    }

    // ---------------------------------------------------------------- 内部工具

    private void stop(Timer.Sample sample, String name, Tags tags) {
        if (sample == null) {
            return;
        }
        sample.stop(Timer.builder(name).tags(tags).register(registry));
    }

    private static String statusClass(int status) {
        if (status <= 0) {
            return "none";
        }
        return (status / 100) + "xx";
    }

    private static String safe(String value) {
        // 空标签值在 Prometheus 里是合法的，但排查时很难分辨「空」与「忘了打标签」，给个显式占位
        return value == null || value.isBlank() ? "-" : value;
    }
}
