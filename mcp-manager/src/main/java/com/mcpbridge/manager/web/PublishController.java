package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.PublishService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.PublishDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布 / 下线 / 回滚（PUB-01 / PUB-03 / PUB-04）。
 *
 * <p>三个接口都会推进集群 {@code revision}，Executor 靠比对 revision 感知变更，
 * 因此「发布成功」到「线上生效」的延迟上限就是一个轮询周期（≤30s）。
 */
@RestController
@RequestMapping("/api/v1/servers/{serverId}")
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping("/publish")
    @PreAuthorize("hasAuthority('publish:execute')")
    public ApiResponse<PublishDtos.PublishResult> publish(@PathVariable Long serverId,
                                                          @Valid @RequestBody PublishDtos.PublishRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(publishService.publish(serverId, request, principal));
    }

    @PostMapping("/offline")
    @PreAuthorize("hasAuthority('publish:execute')")
    public ApiResponse<PublishDtos.PublishResult> offline(@PathVariable Long serverId,
                                                          @RequestParam Long clusterId,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(publishService.offline(serverId, clusterId, principal));
    }

    /** 回滚：以历史版本的快照内容创建一个新版本，历史链条保持完整可审计。 */
    @PostMapping("/rollback")
    @PreAuthorize("hasAuthority('publish:rollback')")
    public ApiResponse<PublishDtos.PublishResult> rollback(@PathVariable Long serverId,
                                                           @RequestParam Long clusterId,
                                                           @Valid @RequestBody PublishDtos.RollbackRequest request,
                                                           @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(publishService.rollback(serverId, clusterId, request, principal));
    }

    @GetMapping("/publish-history")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<PublishDtos.BindingView>> history(@PathVariable Long serverId,
                                                              @RequestParam Long clusterId,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(publishService.history(serverId, clusterId, principal));
    }
}