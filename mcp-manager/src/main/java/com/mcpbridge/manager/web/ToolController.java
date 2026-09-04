package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.ServerService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.ServerDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tool 覆盖精修（SVR-02 / SVR-04，BR-2）。
 *
 * <p>独立成控制器是为了让「tool 级权限点」与「server 级权限点」分开：
 * 只读用户能看 Server 列表，但改 tool 覆盖需要 {@code tool:write}。
 */
@RestController
@RequestMapping("/api/v1/servers/{serverId}/tools")
public class ToolController {

    private final ServerService serverService;

    public ToolController(ServerService serverService) {
        this.serverService = serverService;
    }

    @PutMapping("/{toolId}/overlay")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.ToolView> updateOverlay(@PathVariable Long serverId,
                                                          @PathVariable Long toolId,
                                                          @Valid @RequestBody ServerDtos.ToolOverlayRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.updateToolOverlay(serverId, toolId, request, principal),
                "覆盖已保存，需重新发布后生效");
    }

    /** 恢复默认 = 清空该 tool 的全部覆盖，回落到原始解析值。 */
    @DeleteMapping("/{toolId}/overlay")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.ToolView> resetOverlay(@PathVariable Long serverId,
                                                         @PathVariable Long toolId,
                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.resetToolOverlay(serverId, toolId, principal),
                "已恢复为原始解析值，需重新发布后生效");
    }

    /** 批量启用/停用（SVR-04）。停用的 tool 不会出现在 tools/list 里。 */
    @PostMapping("/batch-toggle")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<List<ServerDtos.ToolView>> batchToggle(
            @PathVariable Long serverId,
            @Valid @RequestBody ServerDtos.ToolBatchToggleRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.batchToggle(serverId, request, principal),
                "已更新，需重新发布后生效");
    }
}