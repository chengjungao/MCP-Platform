package com.mcpbridge.manager.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.mcpbridge.manager.domain.ClusterQuota;
import com.mcpbridge.manager.domain.ClusterType;
import com.mcpbridge.manager.domain.NodeStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
            Instant createdAt,
            /**
             * 发布配额；{@code null} 表示不限。
             *
             * <p>与 {@code publishedServerCount} 一起就能在列表页直接显示 "3/50"，
             * 不必让前端再算一次。
             */
            ClusterQuota quota) {
    }

    public record ClusterRequest(
            @NotBlank @Size(max = 64) String name,
            @NotNull ClusterType type,
            @NotBlank @Size(max = 255) String entrypoint,
            @Size(max = 32) String pathPrefix,
            Long ownerDeptId,
            @Size(max = 255) String description,
            Boolean enabled,
            /**
             * 发布配额，形如 {@code {"maxServers":50,"maxToolsPerServer":200}}；省略或空对象 = 不限。
             *
             * <p>这里刻意收 {@code Map} 而不是强类型 record：校验规则（非整数、负数）要能
             * 以 {@code ApiResponse} 的结构化 details 返回，交给 {@link ClusterQuota#of} 在服务层做。
             */
            @JsonAlias("scopes") Map<String, Object> quota) {
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
     * 心跳响应。刻意只回两个标量：心跳是 10s 级高频调用，任何「顺带的便利字段」都会变成
     * 每个节点每 10s 一次的固定装配成本，而 Executor 侧并不消费它们（EXE-01）。
     *
     * @param revision 控制面当前快照版本
     * @param changed  节点快照是否落后（true 时应立即拉取）
     */
    public record HeartbeatResponse(long revision, boolean changed) {
    }
}