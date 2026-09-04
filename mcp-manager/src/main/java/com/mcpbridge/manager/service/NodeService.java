package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.ExecutorNode;
import com.mcpbridge.manager.domain.NodeStatus;
import com.mcpbridge.manager.repository.ExecutorNodeRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.security.NodePrincipal;
import com.mcpbridge.manager.web.dto.ClusterDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executor 节点注册与心跳（PUB-02 / OPS-01）。
 *
 * <p>节点是<b>无状态</b>的（R5）：注册只是为了让控制面知道「有几个实例、跑的是哪个协议版本、
 * 快照是否落后」，不承载会话。因此心跳只做三件事：更新在线时间、上报负载、
 * 告知节点「你的快照版本落后了，立即来拉」。
 *
 * <p>协议版本守卫（决策 D1）：节点上报的 protocolVersion 不是 {@value McpProtocol#SUPPORTED_VERSION}
 * 时直接拒绝注册，避免旧版 Executor 混进集群后对客户端表现出不一致行为。
 */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private final ExecutorNodeRepository nodeRepository;
    private final PublishBindingRepository bindingRepository;
    private final ClusterService clusterService;
    private final AuditService auditService;
    private final ManagerProperties properties;

    public NodeService(ExecutorNodeRepository nodeRepository,
                       PublishBindingRepository bindingRepository,
                       ClusterService clusterService,
                       AuditService auditService,
                       ManagerProperties properties) {
        this.nodeRepository = nodeRepository;
        this.bindingRepository = bindingRepository;
        this.clusterService = clusterService;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional
    public ClusterDtos.NodeRegisterResponse register(ClusterDtos.NodeRegisterRequest request, NodePrincipal principal) {
        requireSupportedProtocol(request.protocolVersion());
        ExecutorCluster cluster = principal.clusterId() != null
                ? clusterService.require(principal.clusterId())
                : clusterService.requireByName(request.clusterName());
        if (!cluster.isEnabled()) {
            throw PlatformException.conflict("集群已停用，无法注册节点", Map.of("cluster", cluster.getName()));
        }

        ExecutorNode node = nodeRepository
                .findByClusterIdAndNodeKey(cluster.getId(), request.nodeKey())
                .orElseGet(() -> {
                    ExecutorNode created = new ExecutorNode();
                    created.setClusterId(cluster.getId());
                    created.setNodeKey(request.nodeKey());
                    return created;
                });
        node.setHost(request.host());
        node.setPort(request.port());
        node.setVersion(request.version());
        node.setProtocolVersion(McpProtocol.SUPPORTED_VERSION);
        node.setStatus(NodeStatus.ONLINE);
        node.setLastHeartbeatAt(Instant.now());
        ExecutorNode saved = nodeRepository.save(node);

        auditService.record(AuditAction.NODE_REGISTER, "node", saved.getId(), Map.of(
                "cluster", cluster.getName(),
                "nodeKey", saved.getNodeKey(),
                "version", String.valueOf(saved.getVersion()),
                "protocolVersion", saved.getProtocolVersion()));

        return new ClusterDtos.NodeRegisterResponse(
                saved.getId(),
                cluster.getId(),
                cluster.getName(),
                clusterService.endpointTemplate(cluster),
                cluster.getRevision(),
                properties.executor().heartbeatInterval().toSeconds(),
                McpProtocol.SUPPORTED_VERSION);
    }

    /**
     * 心跳：刷新在线时间并告知节点快照是否落后。
     *
     * @param clusterId 由内部通道鉴权（令牌 → 集群）解析得到，不由节点自述，防止串集群
     */
    @Transactional
    public ClusterDtos.HeartbeatResponse heartbeat(Long clusterId, ClusterDtos.HeartbeatRequest request) {
        ExecutorCluster cluster = clusterService.require(clusterId);
        ExecutorNode node = nodeRepository.findByClusterIdAndNodeKey(clusterId, request.nodeKey())
                .orElseThrow(() -> new PlatformException(ErrorCode.INVALID_STATE,
                        "节点未注册，请先调用 POST /internal/v1/nodes/register",
                        Map.of("clusterId", clusterId, "nodeKey", request.nodeKey())));
        node.setStatus(NodeStatus.ONLINE);
        node.setLastHeartbeatAt(Instant.now());
        if (request.loadInfo() != null && !request.loadInfo().isEmpty()) {
            node.setLoadInfo(Json.write(request.loadInfo()));
        }
        nodeRepository.save(node);

        boolean changed = request.snapshotRevision() != cluster.getRevision();
        return new ClusterDtos.HeartbeatResponse(cluster.getRevision(), changed, publishedPathSegments(clusterId));
    }

    @Transactional(readOnly = true)
    public List<String> publishedPathSegments(Long clusterId) {
        return bindingRepository.findByClusterIdAndCurrentTrue(clusterId).stream()
                .filter(b -> b.getState() == BindingState.PUBLISHED && b.getSnapshot() != null)
                .map(b -> Json.tree(b.getSnapshot()).path("pathSegment").asText(null))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    @Transactional
    public List<ClusterDtos.NodeView> offlineNode(Long nodeId) {
        ExecutorNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> PlatformException.notFound("Executor 节点", nodeId));
        node.setStatus(NodeStatus.OFFLINE);
        nodeRepository.save(node);
        auditService.record(AuditAction.NODE_OFFLINE, "node", nodeId,
                Map.of("clusterId", node.getClusterId(), "nodeKey", node.getNodeKey(), "reason", "manual"));
        return nodeRepository.findByClusterIdOrderByIdAsc(node.getClusterId()).stream()
                .map(ClusterService::toNodeView)
                .toList();
    }

    /**
     * 失联巡检：超过 {@code heartbeatInterval × heartbeatTimeoutMultiplier} 未上报的节点标记 OFFLINE。
     * 用固定毫秒配置而不是 Duration 字符串，是因为 {@code @Scheduled} 只认 ISO-8601 或纯数字。
     */
    @Scheduled(fixedDelayString = "${mcp.manager.node-sweep-interval-ms:15000}")
    @Transactional
    public void sweepStaleNodes() {
        Instant threshold = Instant.now().minus(properties.executor().offlineThreshold());
        List<ExecutorNode> stale = nodeRepository.findByStatusAndLastHeartbeatAtBefore(NodeStatus.ONLINE, threshold);
        if (stale.isEmpty()) {
            return;
        }
        for (ExecutorNode node : stale) {
            node.setStatus(NodeStatus.OFFLINE);
        }
        nodeRepository.saveAll(stale);
        stale.forEach(node -> auditService.record(AuditAction.NODE_OFFLINE, "node", node.getId(), Map.of(
                "clusterId", node.getClusterId(),
                "nodeKey", node.getNodeKey(),
                "lastHeartbeatAt", String.valueOf(node.getLastHeartbeatAt()),
                "reason", "heartbeat-timeout")));
        log.info("节点失联巡检：{} 个节点超过 {} 未上报心跳，已标记 OFFLINE", stale.size(), threshold);
    }

    /** 决策 D1：只接受 2026-07-28，legacy 版本显式拒绝并给出升级指引。 */
    private void requireSupportedProtocol(String protocolVersion) {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new PlatformException(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "缺少协议版本声明，本平台仅支持 MCP " + McpProtocol.SUPPORTED_VERSION,
                    Map.of("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION,
                            "upgradeUrl", McpProtocol.UPGRADE_GUIDE_URL));
        }
        if (!McpProtocol.isSupported(protocolVersion)) {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("detectedVersion", protocolVersion);
            details.put("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION);
            details.put("legacySupported", false);
            details.put("upgradeUrl", McpProtocol.UPGRADE_GUIDE_URL);
            if (McpProtocol.isKnownLegacy(protocolVersion)) {
                details.put("hint", "该版本属于 legacy 协议，本平台已停止支持，请升级 Executor 镜像");
            }
            throw new PlatformException(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "不支持的 MCP 协议版本：" + protocolVersion, details);
        }
    }
}