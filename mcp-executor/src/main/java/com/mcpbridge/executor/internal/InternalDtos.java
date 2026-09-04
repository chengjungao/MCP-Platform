package com.mcpbridge.executor.internal;

import java.util.List;
import java.util.Map;

/**
 * 内部通道 DTO：与 Manager 的 {@code /internal/v1/**} 一一对应。
 *
 * <p>刻意在 Executor 侧重新声明而不是共用 Manager 的 DTO 类——两个模块不该互相依赖实现细节，
 * 契约由字段名固定。Manager 侧字段增删时，这里会立刻在联调中暴露出来。
 */
public final class InternalDtos {

    private InternalDtos() {
    }

    /** 注册请求。protocolVersion 必须是 2026-07-28，否则 Manager 直接拒绝（决策 D1）。 */
    public record NodeRegisterRequest(
            String nodeKey,
            String clusterName,
            String host,
            int port,
            String version,
            String protocolVersion) {
    }

    /**
     * 注册响应。
     *
     * @param clusterId                 Manager 分配的集群 id，后续拉快照与心跳都用它
     * @param snapshotRevision          注册瞬间的集群版本，用于判断是否需要立刻全量拉取
     * @param heartbeatIntervalSeconds  Manager 下发的心跳周期
     */
    public record NodeRegisterResponse(
            Long nodeId,
            Long clusterId,
            String clusterName,
            String endpointTemplate,
            long snapshotRevision,
            long heartbeatIntervalSeconds,
            String protocolVersion) {
    }

    public record HeartbeatRequest(String nodeKey, long snapshotRevision, Map<String, Object> loadInfo) {
    }

    /**
     * @param changed               本节点快照是否落后；true 时应立即触发一次拉取而不是等下个周期
     * @param publishedPathSegments 当前集群已发布的 PATH 末段，用于节点自检「我该服务的端点齐不齐」
     */
    public record HeartbeatResponse(long revision, boolean changed, List<String> publishedPathSegments) {
    }

    /** 轻量轮询结果：只回版本与 etag，不回快照体。 */
    public record RevisionView(String clusterKey, long revision, String etag, int serverCount, int toolCount) {
    }
}