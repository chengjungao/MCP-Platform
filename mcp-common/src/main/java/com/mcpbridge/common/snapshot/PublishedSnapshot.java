package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 某个 Executor 集群可见的发布快照集合（EXE-01）。
 *
 * <p>增量同步协议：Executor 带 {@code If-None-Match: <etag>} 轮询，控制面在无变化时返回 304；
 * 有变化时返回完整快照与新的 {@code revision}/{@code etag}。
 *
 * @param revision    单调递增的版本号（同一集群内）
 * @param etag        快照指纹，用于条件请求
 * @param generatedAt 生成时间
 * @param clusterKey  集群标识（shared/private 集群的路径空间彼此独立，BR-3）
 * @param servers     已发布且未下线的 Server 生效模型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishedSnapshot(
        long revision,
        String etag,
        Instant generatedAt,
        String clusterKey,
        List<ServerSnapshot> servers) {

    public static PublishedSnapshot empty(String clusterKey) {
        return new PublishedSnapshot(0L, "\"0\"", Instant.EPOCH, clusterKey, List.of());
    }

    public List<ServerSnapshot> safeServers() {
        return servers == null ? List.of() : servers;
    }

    public Optional<ServerSnapshot> byPathSegment(String pathSegment) {
        if (pathSegment == null) {
            return Optional.empty();
        }
        return safeServers().stream()
                .filter(s -> pathSegment.equals(s.pathSegment()))
                .findFirst();
    }

    public int serverCount() {
        return safeServers().size();
    }

    public int toolCount() {
        return safeServers().stream().mapToInt(s -> s.safeTools().size()).sum();
    }
}
