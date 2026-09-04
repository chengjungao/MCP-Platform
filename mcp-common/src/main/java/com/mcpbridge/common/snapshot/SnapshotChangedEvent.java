package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 快照变更广播消息（BR-6 / EXE-01）。
 *
 * <p>Redisson Topic 载荷：任一 Executor 节点发现控制面快照变化后广播，
 * 其余节点立即触发一次拉取，使「配置变更 → 全集群生效 ≤ 10s」不依赖轮询周期。
 *
 * @param clusterKey  发生变化的集群
 * @param revision    新的快照版本号
 * @param sourceNode  发起广播的节点 id（用于忽略自己发出的消息）
 * @param publishedAt 广播时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotChangedEvent(String clusterKey, long revision, String sourceNode, Instant publishedAt) {

    public static SnapshotChangedEvent of(String clusterKey, long revision, String sourceNode) {
        return new SnapshotChangedEvent(clusterKey, revision, sourceNode, Instant.now());
    }
}
