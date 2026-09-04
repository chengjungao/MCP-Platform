package com.mcpbridge.executor.snapshot;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.config.NodeIdentity;
import com.mcpbridge.executor.internal.InternalDtos;
import com.mcpbridge.executor.state.SharedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 节点注册与心跳（PUB-02 / OPS-01）。
 *
 * <p>注册不是「上报一次就完事」，它是<b>获取集群归属</b>的唯一途径：Manager 按令牌的 sha256
 * 反查集群，节点自己说了不算。因此没注册成功就不知道 clusterId，也就拉不到快照——
 * 这也是为什么心跳任务在「未注册」状态下会顺手重试注册：Manager 晚启动时 Executor 能自愈。
 *
 * <p>心跳同时承担「变更推送的兜底」：Manager 在响应里回 {@code changed}，
 * 为 true 时立刻触发一次快照拉取，把发布生效延迟从「一个轮询周期」压到「一个心跳周期」。
 */
@Service
public class NodeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(NodeLifecycleService.class);

    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);

    private final WebClient managerWebClient;
    private final ExecutorProperties properties;
    private final NodeIdentity nodeIdentity;
    private final SnapshotStore snapshotStore;
    private final SnapshotSyncService snapshotSyncService;
    private final SharedState sharedState;

    private final AtomicReference<InternalDtos.NodeRegisterResponse> registration = new AtomicReference<>();
    private final AtomicReference<Duration> heartbeatInterval = new AtomicReference<>(DEFAULT_HEARTBEAT);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public NodeLifecycleService(WebClient managerWebClient,
                                ExecutorProperties properties,
                                NodeIdentity nodeIdentity,
                                SnapshotStore snapshotStore,
                                SnapshotSyncService snapshotSyncService,
                                SharedState sharedState) {
        this.managerWebClient = managerWebClient;
        this.properties = properties;
        this.nodeIdentity = nodeIdentity;
        this.snapshotStore = snapshotStore;
        this.snapshotSyncService = snapshotSyncService;
        this.sharedState = sharedState;
    }

    /** 应用就绪后立刻注册并拉一次快照，避免等到第一个调度周期才对外可用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        register();
        snapshotSyncService.poll(true);
    }

    public boolean register() {
        InternalDtos.NodeRegisterRequest request = new InternalDtos.NodeRegisterRequest(
                nodeIdentity.nodeKey(),
                properties.manager().clusterName(),
                properties.node().host(),
                properties.node().port(),
                properties.node().version(),
                // 决策 D1：只声明 2026-07-28。Manager 会拒绝任何其它版本
                McpProtocol.SUPPORTED_VERSION);
        try {
            InternalDtos.NodeRegisterResponse response = managerWebClient.post()
                    .uri("/internal/v1/nodes/register")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(InternalDtos.NodeRegisterResponse.class)
                    .block(properties.manager().readTimeout());
            if (response == null || response.clusterId() == null) {
                lastError.set("Manager 返回的注册响应缺少 clusterId");
                log.error("节点注册失败：{}", lastError.get());
                return false;
            }
            registration.set(response);
            lastError.set(null);
            if (response.heartbeatIntervalSeconds() > 0) {
                heartbeatInterval.set(Duration.ofSeconds(response.heartbeatIntervalSeconds()));
            }
            snapshotSyncService.bindCluster(response.clusterId());
            log.info("节点已注册 nodeKey={} cluster={}(id={}) revision={} 心跳周期={} 端点模板={}",
                    nodeIdentity.nodeKey(), response.clusterName(), response.clusterId(),
                    response.snapshotRevision(), heartbeatInterval.get(), response.endpointTemplate());
            return true;
        } catch (WebClientResponseException e) {
            lastError.set("HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
            if (isUnsupportedProtocol(e)) {
                // 这是配置/版本错误，重试不会自愈，必须让运维在日志里一眼看到
                log.error("Manager 拒绝了本节点的协议版本（决策 D1：Modern-only）。请确认 Executor 与 Manager 版本一致。响应：{}",
                        e.getResponseBodyAsString());
            } else {
                log.warn("节点注册失败，将在下一个心跳周期重试：{}", lastError.get());
            }
            return false;
        } catch (RuntimeException e) {
            lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("节点注册失败，将在下一个心跳周期重试：{}", lastError.get());
            return false;
        }
    }

    public void heartbeat() {
        InternalDtos.NodeRegisterResponse current = registration.get();
        if (current == null) {
            // 未注册：把心跳周期当作注册重试周期，Manager 后启动时 Executor 能自愈
            register();
            return;
        }
        try {
            InternalDtos.HeartbeatRequest request = new InternalDtos.HeartbeatRequest(
                    nodeIdentity.nodeKey(), snapshotStore.revision(), loadInfo());
            InternalDtos.HeartbeatResponse response = managerWebClient.post()
                    .uri("/internal/v1/nodes/heartbeat")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(InternalDtos.HeartbeatResponse.class)
                    .block(properties.manager().readTimeout());
            lastError.set(null);
            if (response != null && response.changed()) {
                log.info("心跳告知快照已落后 local={} remote={}，立即拉取",
                        snapshotStore.revision(), response.revision());
                snapshotSyncService.poll(true);
            }
        } catch (WebClientResponseException e) {
            lastError.set("HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
            // 409/INVALID_STATE 意味着 Manager 侧节点记录被清掉了（例如换库或手工清理），重新注册即可
            registration.set(null);
            log.warn("心跳被拒绝，将重新注册：{}", lastError.get());
        } catch (RuntimeException e) {
            lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("心跳上报失败：{}", lastError.get());
        }
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval.get();
    }

    public boolean isRegistered() {
        return registration.get() != null;
    }

    public Map<String, Object> status() {
        InternalDtos.NodeRegisterResponse current = registration.get();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeKey", nodeIdentity.nodeKey());
        status.put("registered", current != null);
        status.put("clusterId", current == null ? null : current.clusterId());
        status.put("clusterName", current == null ? properties.manager().clusterName() : current.clusterName());
        status.put("endpointTemplate", current == null ? null : current.endpointTemplate());
        status.put("protocolVersion", McpProtocol.SUPPORTED_VERSION);
        status.put("heartbeatIntervalSeconds", heartbeatInterval.get().toSeconds());
        status.put("sharedStateMode", sharedState.mode());
        status.put("sharedStateIsShared", sharedState.isShared());
        status.put("lastError", lastError.get());
        return status;
    }

    /** 上报给 Manager 的负载信息，用于运维判断节点是否健康；不参与任何流量调度决策。 */
    private Map<String, Object> loadInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("host", properties.node().host());
        info.put("port", properties.node().port());
        info.put("version", properties.node().version());
        info.put("protocolVersion", McpProtocol.SUPPORTED_VERSION);
        info.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        Runtime runtime = Runtime.getRuntime();
        info.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        info.put("snapshotRevision", snapshotStore.revision());
        info.put("publishedServers", snapshotStore.current().serverCount());
        info.put("sharedState", sharedState.mode());
        return info;
    }

    private static boolean isUnsupportedProtocol(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body != null && (body.contains(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION.name())
                || body.contains(String.valueOf(McpProtocol.SUPPORTED_VERSION)));
    }
}