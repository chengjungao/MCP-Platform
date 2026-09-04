package com.mcpbridge.manager.web;

import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.AuthConfigService;
import com.mcpbridge.manager.service.ServerService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.PageView;
import com.mcpbridge.manager.web.dto.PublishDtos;
import com.mcpbridge.manager.web.dto.ServerDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP Server 配置（SVR-01 / SVR-03 / EXE-03 / EXE-04，BR-2 / BR-3）。
 *
 * <p>三条硬约束在这里落到接口形状上：
 * <ul>
 *   <li>所有写操作都只改 overlay 或独立配置列，<b>不存在「修改原始解析结果」的接口</b>（BR-2）；</li>
 *   <li>Auth-B 只有写入与「掩码视图」两个接口，<b>没有任何接口能读回明文凭据</b>（SEC-01）；</li>
 *   <li>这里改的东西不会影响线上，必须显式发布（PUB-03）——因此每个写接口都在提示里点明这一点。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/servers")
public class ServerController {

    private final ServerService serverService;
    private final AuthConfigService authConfigService;

    public ServerController(ServerService serverService, AuthConfigService authConfigService) {
        this.serverService = serverService;
        this.authConfigService = authConfigService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<PageView<ServerDtos.ServerView>> page(@PageableDefault(size = 20) Pageable pageable,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.page(pageable, principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.ServerView> view(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.view(id, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> update(@PathVariable Long id,
                                                     @Valid @RequestBody ServerDtos.ServerUpdateRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.update(id, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    /** 上游地址、负载均衡、超时、重试与熔断（EXE-03 / EXE-04）。 */
    @PutMapping("/{id}/upstream")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> updateUpstream(@PathVariable Long id,
                                                             @Valid @RequestBody ServerDtos.UpstreamRequest request,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.updateUpstream(id, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    /** 上行鉴权（Auth-B）视图：只回掩码，绝不回明文。 */
    @GetMapping("/{id}/auth-b")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.AuthBView> authB(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireServer(id, principal);
        return ApiResponse.ok(authConfigService.authBView(server));
    }

    /** 写入上行鉴权。密钥字段留空表示「保持原值不变」，这是掩码语义下的唯一安全做法。 */
    @PutMapping("/{id}/auth-b")
    @PreAuthorize("hasAuthority('auth:write')")
    public ApiResponse<ServerDtos.AuthBView> saveAuthB(@PathVariable Long id,
                                                       @Valid @RequestBody ServerDtos.AuthBRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireServer(id, principal);
        return ApiResponse.ok(authConfigService.saveAuthB(server, request),
                "凭据已加密保存，需重新发布后生效");
    }

    /** 下行鉴权（Auth-D）视图。 */
    @GetMapping("/{id}/auth-d")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.AuthDView> authD(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireServer(id, principal);
        return ApiResponse.ok(authConfigService.authDView(server));
    }

    @PutMapping("/{id}/auth-d")
    @PreAuthorize("hasAuthority('auth:write')")
    public ApiResponse<ServerDtos.AuthDView> saveAuthD(@PathVariable Long id,
                                                       @Valid @RequestBody ServerDtos.AuthDRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireServer(id, principal);
        return ApiResponse.ok(authConfigService.saveAuthD(server, request),
                "已保存，需重新发布后生效");
    }

    @GetMapping("/{id}/tools")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<ServerDtos.ToolView>> tools(@PathVariable Long id,
                                                        @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.tools(id, principal));
    }

    /** 原始 vs 生效差异视图（BR-2 明确要求 UI 必须提供）。 */
    @GetMapping("/{id}/diff")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.DiffView> diff(@PathVariable Long id,
                                                 @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.diff(id, principal));
    }

    /** 运行时生效模型预览：发布后 Executor 将加载的内容（不含任何凭据）。 */
    @GetMapping("/{id}/effective")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.EffectiveModelView> effective(@PathVariable Long id,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.effectiveModel(id, principal));
    }

    /** 该 Server 的全部发布绑定（含历史版本），发布页与回滚选择器共用。 */
    @GetMapping("/{id}/bindings")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<PublishDtos.BindingView>> bindings(@PathVariable Long id,
                                                               @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.bindings(id, principal));
    }
}