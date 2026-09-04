package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.ClusterService;
import com.mcpbridge.manager.service.NodeService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.ClusterDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Executor 集群与节点管理（US-05 / PUB-02 / OPS-01）。
 *
 * <p>节点接入令牌只在「生成 / 轮换」的响应里出现一次明文，库里只存 sha256（SEC-01）。
 * 因此轮换是不可逆操作，UI 必须二次确认并提示「旧令牌立即失效」。
 */
@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private final ClusterService clusterService;
    private final NodeService nodeService;

    public ClusterController(ClusterService clusterService, NodeService nodeService) {
        this.clusterService = clusterService;
        this.nodeService = nodeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('cluster:read')")
    public ApiResponse<List<ClusterDtos.ClusterView>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.list(principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cluster:read')")
    public ApiResponse<ClusterDtos.ClusterView> view(@PathVariable Long id,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.view(id, principal));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cluster:write')")
    public ApiResponse<ClusterDtos.ClusterView> create(@Valid @RequestBody ClusterDtos.ClusterRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.create(request, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('cluster:write')")
    public ApiResponse<ClusterDtos.ClusterView> update(@PathVariable Long id,
                                                       @Valid @RequestBody ClusterDtos.ClusterRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.update(id, request, principal),
                "入口或前缀变更会让已发布端点失效，请重新发布受影响的 Server");
    }

    /** 集群授权（US-05）：覆盖式写入被授权部门集合。 */
    @PutMapping("/{id}/grants")
    @PreAuthorize("hasAuthority('cluster:grant')")
    public ApiResponse<ClusterDtos.ClusterView> grant(@PathVariable Long id,
                                                      @Valid @RequestBody ClusterDtos.GrantRequest request,
                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.grant(id, request, principal));
    }

    /**
     * 轮换节点接入令牌。明文只在本响应返回一次，请立即写入 Executor 的环境变量。
     */
    @PostMapping("/{id}/rotate-node-token")
    @PreAuthorize("hasAuthority('cluster:write')")
    public ApiResponse<Map<String, String>> rotateNodeToken(@PathVariable Long id,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        String token = clusterService.rotateNodeToken(id, principal);
        return ApiResponse.ok(Map.of("token", token),
                "令牌已轮换，旧令牌立即失效；明文只返回这一次，请妥善保存");
    }

    @GetMapping("/{id}/nodes")
    @PreAuthorize("hasAuthority('cluster:read')")
    public ApiResponse<List<ClusterDtos.NodeView>> nodes(@PathVariable Long id,
                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(clusterService.nodes(id, principal));
    }

    /** 手工摘除节点（OPS-01）。节点无状态，摘除只影响可观测性，不影响流量转发。 */
    @PostMapping("/nodes/{nodeId}/offline")
    @PreAuthorize("hasAuthority('cluster:write')")
    public ApiResponse<List<ClusterDtos.NodeView>> offlineNode(@PathVariable Long nodeId) {
        return ApiResponse.ok(nodeService.offlineNode(nodeId), "节点已标记为离线");
    }
}