package com.mcpbridge.manager.web;

import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.security.NodePrincipal;
import com.mcpbridge.manager.service.ClusterService;
import com.mcpbridge.manager.service.NodeService;
import com.mcpbridge.manager.web.dto.ClusterDtos;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executor 节点接入内部通道（PUB-02 / OPS-01）。
 *
 * <p>鉴权由 {@code InternalTokenFilter} 完成（{@code X-Executor-Token}），与用户 JWT 完全隔离。
 * 节点自述的 clusterName 只在「引导令牌」场景下被采信；一旦令牌能反查到集群，
 * 就以令牌归属的集群为准，防止节点串集群拉取别人的发布内容（R6）。
 */
@RestController
@RequestMapping("/internal/v1/nodes")
public class InternalNodeController {

    private final NodeService nodeService;
    private final ClusterService clusterService;
    private final ManagerProperties properties;

    public InternalNodeController(NodeService nodeService, ClusterService clusterService,
                                  ManagerProperties properties) {
        this.nodeService = nodeService;
        this.clusterService = clusterService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ClusterDtos.NodeRegisterResponse register(@Valid @RequestBody ClusterDtos.NodeRegisterRequest request,
                                                     @AuthenticationPrincipal NodePrincipal principal) {
        return nodeService.register(request, principal);
    }

    @PostMapping("/heartbeat")
    public ClusterDtos.HeartbeatResponse heartbeat(@Valid @RequestBody ClusterDtos.HeartbeatRequest request,
                                                   @AuthenticationPrincipal NodePrincipal principal) {
        return nodeService.heartbeat(resolveClusterId(principal), request);
    }

    /** 引导令牌没有集群归属，此时回落到配置的默认集群（开箱即用部署）。 */
    private Long resolveClusterId(NodePrincipal principal) {
        if (principal.clusterId() != null) {
            return principal.clusterId();
        }
        ExecutorCluster cluster = clusterService.requireByName(properties.bootstrap().clusterName());
        return cluster.getId();
    }
}