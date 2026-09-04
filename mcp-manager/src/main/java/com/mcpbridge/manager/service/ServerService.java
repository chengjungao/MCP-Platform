package com.mcpbridge.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.OverlayStatus;
import com.mcpbridge.manager.domain.PublishBinding;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.McpToolRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.PageView;
import com.mcpbridge.manager.web.dto.PublishDtos;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP Server 与 Tool 的配置入口（SVR-01 / SVR-02 / SVR-04，BR-2 / BR-3）。
 *
 * <p>所有写操作都落在 overlay 上，基座（原始文档推导值）永远不被修改，
 * 因此「恢复默认」= 清空 overlay，「差异视图」= base 与 effective 对比。
 *
 * <p>注意：本服务只改配置，不改已发布内容。Executor 看到的始终是
 * {@code publish_binding.snapshot}，必须显式发布（或回滚）才会生效（PUB-03）。
 */
@Service
public class ServerService {

    private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https");

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final PublishBindingRepository bindingRepository;
    private final ExecutorClusterRepository clusterRepository;
    private final AuthConfigService authConfigService;
    private final OverlayService overlayService;
    private final PathSegmentGuard pathSegmentGuard;
    private final DepartmentScope departmentScope;
    private final DepartmentService departmentService;
    private final AuditService auditService;

    public ServerService(McpServerRepository serverRepository,
                         McpToolRepository toolRepository,
                         PublishBindingRepository bindingRepository,
                         ExecutorClusterRepository clusterRepository,
                         AuthConfigService authConfigService,
                         OverlayService overlayService,
                         PathSegmentGuard pathSegmentGuard,
                         DepartmentScope departmentScope,
                         DepartmentService departmentService,
                         AuditService auditService) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.bindingRepository = bindingRepository;
        this.clusterRepository = clusterRepository;
        this.authConfigService = authConfigService;
        this.overlayService = overlayService;
        this.pathSegmentGuard = pathSegmentGuard;
        this.departmentScope = departmentScope;
        this.departmentService = departmentService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public PageView<ServerDtos.ServerView> page(Pageable pageable, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        Page<McpServer> page = visible == null
                ? serverRepository.findAll(pageable)
                : serverRepository.findByDeptIdIn(visible, pageable);
        Map<Long, ServerDtos.ServerView> views = toViews(page.getContent());
        return new PageView<>(
                page.getContent().stream().map(s -> views.get(s.getId())).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ServerDtos.ServerView view(Long id, AuthPrincipal principal) {
        McpServer server = requireServer(id, principal);
        return toViews(List.of(server)).get(server.getId());
    }

    @Transactional(readOnly = true)
    public McpServer requireServer(Long id, AuthPrincipal principal) {
        McpServer server = serverRepository.findById(id).orElseThrow(() -> PlatformException.notFound("MCP Server", id));
        departmentScope.requireAccess(server.getDeptId(), principal);
        return server;
    }

    @Transactional(readOnly = true)
    public List<ServerDtos.ToolView> tools(Long serverId, AuthPrincipal principal) {
        requireServer(serverId, principal);
        return toolRepository.findByServerIdOrderBySortOrderAsc(serverId).stream()
                .map(overlayService::toToolView)
                .toList();
    }

    /** 「原始 vs 生效」差异视图（BR-2 要求 UI 必须提供）。 */
    @Transactional(readOnly = true)
    public ServerDtos.DiffView diff(Long serverId, AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        return overlayService.diff(server, toolRepository.findByServerIdOrderBySortOrderAsc(serverId));
    }

    /** 运行时生效模型预览：Executor 发布后将加载的内容（不含任何凭据）。 */
    @Transactional(readOnly = true)
    public ServerDtos.EffectiveModelView effectiveModel(Long serverId, AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        List<ToolSnapshot> tools = toolRepository.findByServerIdOrderBySortOrderAsc(serverId).stream()
                .filter(McpTool::isEnabled)
                .map(overlayService::toSnapshot)
                .toList();
        return new ServerDtos.EffectiveModelView(
                server.getId(), server.getName(), server.getPathSegment(), server.getTitle(),
                server.getDescription(), server.getVersion(), server.getProtocolVersion(),
                server.getListTtlMs(), tools);
    }

    // ------------------------------------------------------------------ 写入

    /** SVR-01：名称 / 展示名 / 描述 / PATH 末段 / list 缓存 TTL。 */
    @Transactional
    public ServerDtos.ServerView update(Long id, ServerDtos.ServerUpdateRequest request, AuthPrincipal principal) {
        McpServer server = requireServer(id, principal);
        ObjectNode overlay = overlayService.serverOverlay(server);
        Map<String, Object> changes = new LinkedHashMap<>();

        putOverride(overlay, OverlayService.FIELD_NAME, request.name(), changes);
        putOverride(overlay, OverlayService.FIELD_TITLE, request.title(), changes);
        putOverride(overlay, OverlayService.FIELD_DESCRIPTION, request.description(), changes);

        if (request.pathSegment() != null) {
            String previous = server.getPathSegment();
            if (request.pathSegment().isBlank()) {
                overlay.remove(OverlayService.FIELD_PATH_SEGMENT);
                changes.put(OverlayService.FIELD_PATH_SEGMENT, Map.of("from", previous, "to", "（恢复默认）"));
            } else {
                // BR-3：末段是稳定契约，改名等于让已配置的 MCP Client 断链，因此必须显式校验唯一性
                String segment = pathSegmentGuard.requireAvailable(request.pathSegment(), server.getId());
                overlay.put(OverlayService.FIELD_PATH_SEGMENT, segment);
                if (!segment.equals(previous)) {
                    changes.put(OverlayService.FIELD_PATH_SEGMENT, Map.of("from", previous, "to", segment));
                }
            }
        }
        if (request.listTtlMs() != null) {
            if (request.listTtlMs() < 0) {
                throw PlatformException.validation("listTtlMs 不能为负数",
                        Map.of("field", OverlayService.FIELD_LIST_TTL_MS));
            }
            overlay.put(OverlayService.FIELD_LIST_TTL_MS, request.listTtlMs());
            changes.put(OverlayService.FIELD_LIST_TTL_MS, request.listTtlMs());
        }

        server.setOverlay(overlay.isEmpty() ? null : Json.write(overlay));
        overlayService.applyServerOverlay(server);
        if (!changes.isEmpty()) {
            server.setOverlayVersion(server.getOverlayVersion() + 1);
        }
        McpServer saved = serverRepository.save(server);
        if (!changes.isEmpty()) {
            String action = changes.containsKey(OverlayService.FIELD_PATH_SEGMENT)
                    ? AuditAction.SERVER_PATH_CHANGE : AuditAction.SERVER_UPDATE;
            auditService.record(action, "server", saved.getId(), changes);
        }
        return toViews(List.of(saved)).get(saved.getId());
    }

    /** EXE-03 / EXE-04：上游地址、负载均衡、超时、重试与熔断。 */
    @Transactional
    public ServerDtos.ServerView updateUpstream(Long id, ServerDtos.UpstreamRequest request, AuthPrincipal principal) {
        McpServer server = requireServer(id, principal);
        List<String> baseUrls = new ArrayList<>();
        for (String raw : request.baseUrls()) {
            baseUrls.add(requireHttpUrl(raw));
        }
        if (baseUrls.isEmpty()) {
            throw PlatformException.validation("至少配置一个上游地址", Map.of("field", "baseUrls"));
        }
        UpstreamSnapshot.CircuitBreaker circuitBreaker = new UpstreamSnapshot.CircuitBreaker(
                orDefault(request.cbFailureThreshold(), 5),
                orDefault(request.cbOpenMs(), 30_000L),
                orDefault(request.cbHalfOpenProbes(), 2));
        UpstreamSnapshot upstream = new UpstreamSnapshot(
                baseUrls,
                request.lbStrategy() == null ? UpstreamSnapshot.LbStrategy.ROUND_ROBIN : request.lbStrategy(),
                List.of(),
                orDefault(request.connectTimeoutMs(), 3_000L),
                orDefault(request.readTimeoutMs(), 30_000L),
                orDefault(request.retries(), 1),
                request.retryOnStatus() == null ? List.of(502, 503, 504) : request.retryOnStatus(),
                circuitBreaker);
        server.setUpstream(Json.write(upstream));
        if (server.getStatus() == ServerStatus.DRAFT) {
            server.setStatus(ServerStatus.CONFIGURED);
        }
        McpServer saved = serverRepository.save(server);
        auditService.record(AuditAction.SERVER_UPDATE, "server", saved.getId(), Map.of(
                "upstream", Map.of(
                        "baseUrls", baseUrls,
                        "lbStrategy", upstream.lbStrategy().name(),
                        "connectTimeoutMs", upstream.connectTimeoutMs(),
                        "readTimeoutMs", upstream.readTimeoutMs(),
                        "retries", upstream.retries())));
        return toViews(List.of(saved)).get(saved.getId());
    }

    /** SVR-02：单个 Tool 的覆盖编辑。 */
    @Transactional
    public ServerDtos.ToolView updateToolOverlay(Long serverId, Long toolId,
                                                 ServerDtos.ToolOverlayRequest request, AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        McpTool tool = requireTool(serverId, toolId);
        ObjectNode overlay = overlayService.toolOverlay(tool);
        Map<String, Object> changes = new LinkedHashMap<>();

        if (request.name() != null) {
            overlayService.requireValidToolName(request.name().trim());
            if (request.name().isBlank()) {
                overlay.remove(OverlayService.FIELD_NAME);
            } else {
                String candidate = request.name().trim();
                requireUniqueEffectiveName(server, tool, candidate);
                overlay.put(OverlayService.FIELD_NAME, candidate);
            }
            changes.put(OverlayService.FIELD_NAME, request.name());
        }
        putOverride(overlay, OverlayService.FIELD_DESCRIPTION, request.description(), changes);
        if (request.inputSchema() != null) {
            if (request.inputSchema().isNull()
                    || (request.inputSchema().isObject() && request.inputSchema().isEmpty())) {
                overlay.remove(OverlayService.FIELD_INPUT_SCHEMA);
                changes.put(OverlayService.FIELD_INPUT_SCHEMA, "（恢复默认）");
            } else if (!request.inputSchema().isObject()) {
                throw PlatformException.validation("inputSchema 必须是 JSON Schema 对象",
                        Map.of("field", OverlayService.FIELD_INPUT_SCHEMA));
            } else {
                overlay.set(OverlayService.FIELD_INPUT_SCHEMA, request.inputSchema());
                changes.put(OverlayService.FIELD_INPUT_SCHEMA, "已覆盖");
            }
        }
        if (request.streaming() != null) {
            overlay.put(OverlayService.FIELD_STREAMING, request.streaming());
            changes.put(OverlayService.FIELD_STREAMING, request.streaming());
        }
        putOverride(overlay, OverlayService.FIELD_STREAM_FORMAT, request.streamFormat(), changes);
        if (request.enabled() != null && request.enabled() != tool.isEnabled()) {
            tool.setEnabled(request.enabled());
            changes.put("enabled", request.enabled());
        }

        overlayService.writeToolOverlay(tool, overlay);
        toolRepository.save(tool);
        bumpOverlayVersion(server);
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.TOOL_OVERLAY_UPDATE, "tool", tool.getId(), changes);
        }
        return overlayService.toToolView(tool);
    }

    /** SVR-02：清除某个 Tool 的全部覆盖，回落到基座值。 */
    @Transactional
    public ServerDtos.ToolView resetToolOverlay(Long serverId, Long toolId, AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        McpTool tool = requireTool(serverId, toolId);
        tool.setOverlay(null);
        tool.setOverlayStatus(OverlayStatus.NONE);
        toolRepository.save(tool);
        bumpOverlayVersion(server);
        auditService.record(AuditAction.TOOL_OVERLAY_RESET, "tool", tool.getId(),
                Map.of("anchor", tool.getAnchor(), "baseName", tool.getBaseName()));
        return overlayService.toToolView(tool);
    }

    /** SVR-04：批量启用/停用 Tool。 */
    @Transactional
    public List<ServerDtos.ToolView> batchToggle(Long serverId, ServerDtos.ToolBatchToggleRequest request,
                                                 AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        List<McpTool> tools = toolRepository.findAllById(request.toolIds());
        List<McpTool> mismatched = tools.stream().filter(t -> !serverId.equals(t.getServerId())).toList();
        if (!mismatched.isEmpty()) {
            throw PlatformException.validation("存在不属于该 Server 的 tool",
                    Map.of("serverId", serverId,
                            "invalidToolIds", mismatched.stream().map(McpTool::getId).toList()));
        }
        List<String> changed = new ArrayList<>();
        for (McpTool tool : tools) {
            if (tool.isEnabled() != request.enabled()) {
                tool.setEnabled(request.enabled());
                changed.add(tool.getAnchor());
            }
        }
        toolRepository.saveAll(tools);
        if (!changed.isEmpty()) {
            bumpOverlayVersion(server);
            auditService.record(AuditAction.TOOL_TOGGLE, "server", serverId,
                    Map.of("enabled", request.enabled(), "anchors", changed));
        }
        return toolRepository.findByServerIdOrderBySortOrderAsc(serverId).stream()
                .map(overlayService::toToolView)
                .toList();
    }

    // ------------------------------------------------------------------ 发布态视图

    @Transactional(readOnly = true)
    public List<PublishDtos.BindingView> bindings(Long serverId, AuthPrincipal principal) {
        McpServer server = requireServer(serverId, principal);
        Map<Long, ExecutorCluster> clusters = clusterIndex();
        return bindingRepository.findByServerIdOrderByIdDesc(serverId).stream()
                .map(b -> toBindingView(b, server, clusters.get(b.getClusterId())))
                .toList();
    }

    /** 供 PublishService 复用：把绑定映射成视图。 */
    public PublishDtos.BindingView toBindingView(PublishBinding binding, McpServer server, ExecutorCluster cluster) {
        String endpoint = cluster == null ? null
                : PathSegments.endpoint(cluster.getEntrypoint(), cluster.getPathPrefix(), server.getPathSegment());
        return new PublishDtos.BindingView(
                binding.getId(),
                binding.getServerId(),
                server.getName(),
                server.getPathSegment(),
                binding.getClusterId(),
                cluster == null ? null : cluster.getName(),
                cluster == null ? null : cluster.getType().name(),
                binding.getVersion(),
                binding.getState(),
                binding.isCurrent(),
                endpoint,
                binding.getPublishedBy(),
                binding.getPublishedAt(),
                binding.getOfflinedAt(),
                binding.getFailureReason(),
                toolCountOf(binding));
    }

    /** 读取 Server 的上游策略；未配置时给出默认值。 */
    public UpstreamSnapshot upstreamOf(McpServer server) {
        String json = server.getUpstream();
        if (json == null || json.isBlank()) {
            return UpstreamSnapshot.defaults(List.of());
        }
        try {
            UpstreamSnapshot upstream = Json.read(json, UpstreamSnapshot.class);
            return upstream == null ? UpstreamSnapshot.defaults(List.of()) : upstream;
        } catch (RuntimeException e) {
            return UpstreamSnapshot.defaults(List.of());
        }
    }

    public String endpointTemplate(ExecutorCluster cluster) {
        String prefix = cluster == null || cluster.getPathPrefix() == null
                ? PathSegments.DEFAULT_RESERVED_PREFIX : cluster.getPathPrefix();
        String entrypoint = cluster == null ? "" : cluster.getEntrypoint().replaceAll("/+$", "");
        return entrypoint + "/" + prefix + "/{末段}";
    }

    // ------------------------------------------------------------------ 内部工具

    private Map<Long, ServerDtos.ServerView> toViews(List<McpServer> servers) {
        if (servers.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = servers.stream().map(McpServer::getId).toList();
        Map<Long, String> deptNames = departmentService.namesOf();
        Map<Long, ExecutorCluster> clusters = clusterIndex();
        Map<Long, List<McpTool>> toolsByServer = toolRepository.findByServerIdInOrderBySortOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(McpTool::getServerId));
        Map<Long, List<PublishBinding>> bindingsByServer = bindingRepository.findByServerIdIn(ids).stream()
                .collect(Collectors.groupingBy(PublishBinding::getServerId));
        Map<Long, ServerDtos.AuthBView> authBViews = authConfigService.authBViews(ids);

        Map<Long, ServerDtos.ServerView> result = new LinkedHashMap<>();
        for (McpServer server : servers) {
            List<McpTool> tools = toolsByServer.getOrDefault(server.getId(), List.of());
            List<PublishBinding> bindings = bindingsByServer.getOrDefault(server.getId(), List.of());
            bindings.sort(Comparator.comparing(PublishBinding::getId).reversed());
            List<PublishDtos.BindingView> bindingViews = bindings.stream()
                    .map(b -> toBindingView(b, server, clusters.get(b.getClusterId())))
                    .toList();
            String endpointPreview = bindings.stream()
                    .filter(b -> b.isCurrent() && b.getState() == BindingState.PUBLISHED)
                    .findFirst()
                    .map(b -> clusters.get(b.getClusterId()))
                    .map(c -> PathSegments.endpoint(c.getEntrypoint(), c.getPathPrefix(), server.getPathSegment()))
                    .orElse(null);
            result.put(server.getId(), new ServerDtos.ServerView(
                    server.getId(),
                    server.getName(),
                    server.getTitle(),
                    server.getDescription(),
                    server.getPathSegment(),
                    endpointPreview,
                    server.getVersion(),
                    server.getProtocolVersion(),
                    server.getStatus(),
                    server.getOverlayVersion(),
                    server.getDeptId(),
                    deptNames.get(server.getDeptId()),
                    server.getRegistrationId(),
                    tools.size(),
                    tools.stream().filter(McpTool::isEnabled).count(),
                    server.getListTtlMs(),
                    upstreamOf(server),
                    authBViews.getOrDefault(server.getId(), AuthConfigService.emptyAuthBView()),
                    authConfigService.authDView(server),
                    bindingViews,
                    server.getCreatedAt(),
                    server.getUpdatedAt()));
        }
        return result;
    }

    private Map<Long, ExecutorCluster> clusterIndex() {
        return clusterRepository.findAll().stream()
                .collect(Collectors.toMap(ExecutorCluster::getId, Function.identity(), (a, b) -> a));
    }

    private static int toolCountOf(PublishBinding binding) {
        String snapshot = binding.getSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return 0;
        }
        try {
            JsonNode tools = Json.tree(snapshot).get("tools");
            return tools == null || !tools.isArray() ? 0 : tools.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private McpTool requireTool(Long serverId, Long toolId) {
        McpTool tool = toolRepository.findById(toolId).orElseThrow(() -> PlatformException.notFound("Tool", toolId));
        if (!serverId.equals(tool.getServerId())) {
            throw PlatformException.notFound("Tool", toolId);
        }
        return tool;
    }

    private void requireUniqueEffectiveName(McpServer server, McpTool target, String candidate) {
        for (McpTool sibling : toolRepository.findByServerIdOrderBySortOrderAsc(server.getId())) {
            if (sibling.getId().equals(target.getId())) {
                continue;
            }
            if (candidate.equals(overlayService.effectiveName(sibling))) {
                throw PlatformException.conflict("tool 名在同一 Server 内必须唯一（MCP tools/list 不允许重名）",
                        Map.of("name", candidate, "conflictAnchor", sibling.getAnchor()));
            }
        }
    }

    private void bumpOverlayVersion(McpServer server) {
        server.setOverlayVersion(server.getOverlayVersion() + 1);
        serverRepository.save(server);
    }

    /**
     * 覆盖字段写入约定：null 表示「不修改」，空白串表示「清除覆盖、回落基座」，其余为覆盖值。
     */
    private static void putOverride(ObjectNode overlay, String key, String value, Map<String, Object> changes) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            overlay.remove(key);
            changes.put(key, "（恢复默认）");
            return;
        }
        overlay.put(key, value.trim());
        changes.put(key, value.trim());
    }

    private static String requireHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PlatformException.validation("上游地址不能为空", Map.of("field", "baseUrls"));
        }
        String url = raw.trim().replaceAll("/+$", "");
        String scheme = url.contains("://") ? url.substring(0, url.indexOf("://")).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
            throw PlatformException.validation("上游地址只支持 http/https",
                    Map.of("field", "baseUrls", "value", url));
        }
        return url;
    }

    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /** 供 PublishService 校验用：当前启用 tool 数。 */
    @Transactional(readOnly = true)
    public long enabledToolCount(Long serverId) {
        return toolRepository.countByServerIdAndEnabledTrue(serverId);
    }

    /** 供 PublishService 用：Server 的启用 tool 列表（已按 sortOrder 排序）。 */
    @Transactional(readOnly = true)
    public List<McpTool> enabledTools(Long serverId) {
        return toolRepository.findByServerIdOrderBySortOrderAsc(serverId).stream()
                .filter(McpTool::isEnabled)
                .toList();
    }

    /** 去重后的启用 tool 生效名，发布前校验 tools/list 不会出现重名。 */
    @Transactional(readOnly = true)
    public Set<String> duplicateEffectiveNames(Long serverId) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (McpTool tool : enabledTools(serverId)) {
            if (!seen.add(overlayService.effectiveName(tool))) {
                duplicates.add(overlayService.effectiveName(tool));
            }
        }
        return duplicates;
    }
}