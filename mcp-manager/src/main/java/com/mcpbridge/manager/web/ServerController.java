package com.mcpbridge.manager.web;

import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.AuthConfigService;
import com.mcpbridge.manager.service.ResourcePromptService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ResourcePromptService resourcePromptService;

    public ServerController(ServerService serverService,
                            AuthConfigService authConfigService,
                            ResourcePromptService resourcePromptService) {
        this.serverService = serverService;
        this.authConfigService = authConfigService;
        this.resourcePromptService = resourcePromptService;
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

    /** 新建 MCP Server（先建基础信息，再在该 Server 下注册多份 Swagger 文档）。 */
    @PostMapping
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> create(@Valid @RequestBody ServerDtos.ServerCreateRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.create(request, principal),
                "已创建空 Server，接下来在「REST 服务」里注册 Swagger 文档");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthPrincipal principal) {
        serverService.delete(id, principal);
        return ApiResponse.ok(null, "Server 已删除");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> update(@PathVariable Long id,
                                                     @Valid @RequestBody ServerDtos.ServerUpdateRequest request,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.update(id, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    /** 按 serviceId upsert 单个 REST 服务配置（EXE-03 / EXE-04，多服务支持）。 */
    @PutMapping("/{id}/upstreams/{serviceId}")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> upsertUpstream(@PathVariable Long id,
                                                              @PathVariable String serviceId,
                                                              @Valid @RequestBody ServerDtos.UpstreamEntryRequest request,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        // 路径里的 serviceId 优先；请求体里的 serviceId 若存在须一致，否则以路径为准
        ServerDtos.UpstreamEntryRequest merged = new ServerDtos.UpstreamEntryRequest(
                serviceId,
                request.name(),
                request.baseUrls(),
                request.lbStrategy(),
                request.weights(),
                request.connectTimeoutMs(),
                request.readTimeoutMs(),
                request.retries(),
                request.retryOnStatus(),
                request.cbFailureThreshold(),
                request.cbOpenMs(),
                request.cbHalfOpenProbes(),
                request.authB());
        return ApiResponse.ok(serverService.upsertUpstream(id, merged, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    /** 删除某个 REST 服务（多服务场景下移除一份 Swagger 对应的服务配置）。 */
    @DeleteMapping("/{id}/upstreams/{serviceId}")
    @PreAuthorize("hasAuthority('server:write')")
    public ApiResponse<ServerDtos.ServerView> deleteUpstream(@PathVariable Long id,
                                                              @PathVariable String serviceId,
                                                              @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(serverService.deleteUpstream(id, serviceId, principal),
                "已删除，需重新发布后对 MCP Client 生效");
    }

    /** 上行鉴权（Auth-B）视图：只回掩码，绝不回明文。 */
    @GetMapping("/{id}/auth-b")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.AuthBView> authB(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireManage(id, principal);
        return ApiResponse.ok(authConfigService.authBView(server));
    }

    /** 写入上行鉴权。密钥字段留空表示「保持原值不变」，这是掩码语义下的唯一安全做法。 */
    @PutMapping("/{id}/auth-b")
    @PreAuthorize("hasAuthority('auth:write')")
    public ApiResponse<ServerDtos.AuthBView> saveAuthB(@PathVariable Long id,
                                                       @Valid @RequestBody ServerDtos.AuthBRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireManage(id, principal);
        return ApiResponse.ok(authConfigService.saveAuthB(server, request),
                "凭据已加密保存，需重新发布后生效");
    }

    /** 下行鉴权（Auth-D）视图。 */
    @GetMapping("/{id}/auth-d")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<ServerDtos.AuthDView> authD(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireManage(id, principal);
        return ApiResponse.ok(authConfigService.authDView(server));
    }

    @PutMapping("/{id}/auth-d")
    @PreAuthorize("hasAuthority('auth:write')")
    public ApiResponse<ServerDtos.AuthDView> saveAuthD(@PathVariable Long id,
                                                       @Valid @RequestBody ServerDtos.AuthDRequest request,
                                                       @AuthenticationPrincipal AuthPrincipal principal) {
        McpServer server = serverService.requireManage(id, principal);
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

    // ---------------------------------------------------------------- Resource（SVR-05）

    @GetMapping("/{id}/resources")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<ServerDtos.ResourceView>> resources(@PathVariable Long id,
                                                                @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.resources(id, principal));
    }

    /** 新增 Resource。URI 在 Server 内唯一。 */
    @PostMapping("/{id}/resources")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.ResourceView> createResource(
            @PathVariable Long id,
            @Valid @RequestBody ServerDtos.ResourceRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.saveResource(id, null, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    @PutMapping("/{id}/resources/{resourceId}")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.ResourceView> updateResource(
            @PathVariable Long id,
            @PathVariable Long resourceId,
            @Valid @RequestBody ServerDtos.ResourceRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.saveResource(id, resourceId, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    @DeleteMapping("/{id}/resources/{resourceId}")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<Void> deleteResource(@PathVariable Long id,
                                            @PathVariable Long resourceId,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        resourcePromptService.deleteResource(id, resourceId, principal);
        return ApiResponse.ok(null, "已删除，需重新发布后对 MCP Client 生效");
    }

    // ---------------------------------------------------------------- Prompt（SVR-06）

    @GetMapping("/{id}/prompts")
    @PreAuthorize("hasAuthority('server:read')")
    public ApiResponse<List<ServerDtos.PromptView>> prompts(@PathVariable Long id,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.prompts(id, principal));
    }

    /** 新增 Prompt。模板占位符必须与参数声明一致，否则直接拒绝。 */
    @PostMapping("/{id}/prompts")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.PromptView> createPrompt(
            @PathVariable Long id,
            @Valid @RequestBody ServerDtos.PromptRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.savePrompt(id, null, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    @PutMapping("/{id}/prompts/{promptId}")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<ServerDtos.PromptView> updatePrompt(
            @PathVariable Long id,
            @PathVariable Long promptId,
            @Valid @RequestBody ServerDtos.PromptRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(resourcePromptService.savePrompt(id, promptId, request, principal),
                "已保存，需重新发布后对 MCP Client 生效");
    }

    @DeleteMapping("/{id}/prompts/{promptId}")
    @PreAuthorize("hasAuthority('tool:write')")
    public ApiResponse<Void> deletePrompt(@PathVariable Long id,
                                          @PathVariable Long promptId,
                                          @AuthenticationPrincipal AuthPrincipal principal) {
        resourcePromptService.deletePrompt(id, promptId, principal);
        return ApiResponse.ok(null, "已删除，需重新发布后对 MCP Client 生效");
    }
}