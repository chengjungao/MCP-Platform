package com.mcpbridge.executor.web;

import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.snapshot.NodeLifecycleService;
import com.mcpbridge.executor.snapshot.SnapshotStore;
import com.mcpbridge.executor.state.SharedState;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点自检端点（OPS-01）。
 *
 * <p>{@code /healthz} 是给负载均衡与编排系统探活用的，判定标准刻意保守：
 * <b>只有「从未成功同步过快照」才算不健康</b>。Redis 退化、心跳失败、Manager 不可达
 * 都只是 degraded 而不摘节点——它们都不影响已发布端点继续服务（R7），
 * 而一次误摘会把局部抖动放大成整个集群容量骤降。
 *
 * <p>Actuator 的 {@code /actuator/health} 依然存在，但它反映的是「进程活着」；
 * 这个端点反映的是「能不能对外提供 MCP 服务」，两者不是一回事。
 */
@RestController
public class StatusController {

    private final SnapshotStore store;
    private final NodeLifecycleService lifecycle;
    private final CircuitBreakerRegistry breakers;
    private final SharedState sharedState;
    private final ExecutorProperties properties;

    public StatusController(SnapshotStore store,
                            NodeLifecycleService lifecycle,
                            CircuitBreakerRegistry breakers,
                            SharedState sharedState,
                            ExecutorProperties properties) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.breakers = breakers;
        this.sharedState = sharedState;
        this.properties = properties;
    }

    /** 全量诊断信息：排查「为什么这个端点不通」时看这一个就够。 */
    @GetMapping("/executor/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("node", lifecycle.status());
        status.put("snapshot", store.status());
        status.put("publishedPathSegments", store.pathSegments());
        status.put("sharedState", Map.of("mode", sharedState.mode(), "shared", sharedState.isShared()));
        status.put("circuitBreakers", breakers.states());
        status.put("protocol", Map.of(
                "pathPrefix", properties.protocol().pathPrefix(),
                "defaultListTtlMs", properties.protocol().defaultListTtlMs()));
        return status;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthz() {
        SnapshotStore.Status snapshot = store.status();
        Map<String, Object> node = lifecycle.status();
        boolean registered = Boolean.TRUE.equals(node.get("registered"));
        boolean degraded = !registered || !sharedState.isShared() || snapshot.lastError() != null;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot.ready() ? (degraded ? "DEGRADED" : "UP") : "NOT_READY");
        body.put("snapshotReady", snapshot.ready());
        body.put("registered", registered);
        body.put("clusterKey", snapshot.clusterKey());
        body.put("revision", snapshot.revision());
        body.put("serverCount", snapshot.serverCount());
        body.put("toolCount", snapshot.toolCount());
        body.put("staleSeconds", snapshot.staleSeconds());
        body.put("sharedStateMode", sharedState.mode());
        body.put("lastError", snapshot.lastError());

        HttpStatus status = snapshot.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }
}