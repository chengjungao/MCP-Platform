package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.AccessService;
import com.mcpbridge.manager.web.dto.AccessDtos;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.PageView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨部门访问申请与授权。
 *
 * <p>所有端点要求 {@code server:read}（只有读权限者才能申请/审批）；
 * 审批资格（资源方部门管理员 / 平台管理员）在 {@link AccessService} 内按角色与服务数据双重强制，
 * 不依赖前端隐藏。授权只读，写与凭据仍由 {@code ServerService.requireManage} 保护。
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

    private final AccessService accessService;

    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    /** 可申请目录（我不可直接访问的 Server 最小信息 + 申请状态）。 */
    @GetMapping("/catalog")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<PageView<AccessDtos.CatalogRow>> catalog(@PageableDefault(size = 20) Pageable pageable,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.catalog(pageable, principal));
    }

    /** 发起申请（按我的归属部门）。 */
    @PostMapping
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<AccessDtos.AccessView> apply(@Valid @RequestBody AccessDtos.ApplyRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.apply(request, principal));
    }

    /** 我发起的申请（本部门及子树）。 */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<AccessDtos.AccessView>> mine(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.mine(principal));
    }

    /** 待我审批（资源方部门管理员 / 平台管理员）。 */
    @GetMapping("/todo")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<AccessDtos.AccessView>> todo(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.todo(principal));
    }

    /** 已授权（可回收，资源方部门管理员 / 平台管理员）。 */
    @GetMapping("/grants")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<AccessDtos.AccessView>> grants(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.grants(principal));
    }

    /** 通过。 */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<AccessDtos.AccessView> approve(@PathVariable Long id,
                                                      @RequestBody(required = false) AccessDtos.ReviewRequest request,
                                                      @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.approve(id, request, principal));
    }

    /** 驳回。 */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<AccessDtos.AccessView> reject(@PathVariable Long id,
                                                     @RequestBody(required = false) AccessDtos.ReviewRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.reject(id, request, principal));
    }

    /** 回收（APPROVED → REVOKED）。 */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<AccessDtos.AccessView> revoke(@PathVariable Long id,
                                                     @RequestBody(required = false) AccessDtos.ReviewRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accessService.revoke(id, request, principal));
    }
}
