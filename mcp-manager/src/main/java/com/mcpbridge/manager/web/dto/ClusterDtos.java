package com.mcpbridge.manager.web.dto;

import com.mcpbridge.manager.domain.ClusterType;
import com.mcpbridge.manager.domain.NodeStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集群与节点 DTO（PUB-01/02）。
 */
public final class ClusterDtos {

    private ClusterDtos() {
    }

    public record ClusterView(
            Long id,
            String name,
            ClusterType type,
            String entrypoint,
            String pathPrefix,
            /** 端点模板，前端提示用户「只能自定义末段」（BR-3）。 */
            String endpointTemplate,
            Long ownerDeptId,
            String ownerDeptName,
            String description,
            boolean enabled,
            Set<Long> grantedDeptIds,
            long nodeCount,
            long onlineNodeCount,
            long publishedServerCount,
            long revision,
            Instant createdAt) {
    }

    public record ClusterRequest(
            @NotBlank @Size(max = 64) String name,
            @NotNull ClusterType type,
            @NotBlank @Size(max = 255) String entrypoint,
            @Size(max = 32) String pathPrefix,
            Long ownerDeptId,
            @Size(max = 255) String description,
            Boolean enabled,
            Map<String, Object> scopes) {
    }

    /** 集群发布授权（US-05：把某集群的发布权授给部门）。 */
    public record GrantRequest(@NotEmpty Set<Long> deptIds) {
    }

    public record NodeView(
            Long id,
            Long clusterId,
            String nodeKey,
            String host,
            Integer port,
            String version,
            String protocolVersion,
            NodeStatus status,
            Instant lastHeartbeatAt,
            Map<String, Object> loadInfo) {
    }

    /** 节点注册（PUB-02）：携带集群接入令牌。 */
    public record NodeRegisterRequest(
            @NotBlank @Size(max = 128) String nodeKey,
            @NotBlank @Size(max = 64) String clusterName,
            String host,
            Integer port,
            String version,
            /** 必须为 2026-07-28，否则拒绝注册（决策 D1）。 */
            @NotBlank String protocolVersion) {
    }

    public record NodeRegisterResponse(
            Long nodeId,
            Long clusterId,
            String clusterName,
            String endpointTemplate,
            long snapshotRevision,
            long heartbeatIntervalSeconds,
            String protocolVersion) {
    }

    public record HeartbeatRequest(
            @NotBlank String nodeKey,
            /** 节点当前已加载的快照版本，用于判断是否需要提示立即拉取。 */
            long snapshotRevision,
            Map<String, Object> loadInfo) {
    }

    /**
     * @param revision 控制面当前快照版本
     * @param changed  节点快照是否落后（true 时应立即拉取）
     */
    public record HeartbeatResponse(long revision, boolean changed, List<String> publishedPathSegments) {
    }
}