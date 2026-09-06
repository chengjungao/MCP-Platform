package com.mcpbridge.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.domain.AccessStatus;
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
import com.mcpbridge.manager.repository.ApiRegistrationRepository;
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
    private final com.mcpbridge.manager.repository.ServerUpstreamRepository upstreamRepository;
    private final com.mcpbridge.manager.repository.ServerAccessRepository accessRepository;
    private final AuthConfigService authConfigService;
    private final OverlayService overlayService;
    private final PathSegmentGuard pathSegmentGuard;
    private final DepartmentScope departmentScope;
    private final DepartmentService departmentService;
    private final AuditService auditService;
    private final ApiRegistrationRepository registrationRepository;

    public ServerService(McpServerRepository serverRepository,
                         McpToolRepository toolRepository,
                         PublishBindingRepository bindingRepository,
                         ExecutorClusterRepository clusterRepository,
                         com.mcpbridge.manager.repository.ServerUpstreamRepository upstreamRepository,
                         com.mcpbridge.manager.repository.ServerAccessRepository accessRepository,
                         AuthConfigService authConfigService,
                         OverlayService overlayService,
                         PathSegmentGuard pathSegmentGuard,
                         DepartmentScope departmentScope,
                         DepartmentService departmentService,
                         AuditService auditService,
                         ApiRegistrationRepository registrationRepository) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.bindingRepository = bindingRepository;
        this.clusterRepository = clusterRepository;
        this.upstreamRepository = upstreamRepository;
        this.accessRepository = accessRepository;
        this.authConfigService = authConfigService;
        this.overlayService = overlayService;
        this.pathSegmentGuard = pathSegmentGuard;
        this.departmentScope = departmentScope;
        this.departmentService = departmentService;
        this.auditService = auditService;
        this.registrationRepository = registrationRepository;
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public PageView<ServerDtos.ServerView> page(Pageable pageable, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        Page<McpServer> page = visible == null
                ? serverRepository.findAll(pageable)
                : serverRepository.findByDeptIdIn(visible, pageable);
        Map<Long, ServerDtos.ServerView> views = toViews(page.getContent(), principal);
        return new PageView<>(
                page.getContent().stream().map(s -> views.get(s.getId())).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ServerDtos.ServerView view(Long id, AuthPrincipal principal) {
        McpServer server = requireRead(id, principal);
        ServerDtos.ServerView view = toViews(List.of(server), principal).get(server.getId());
        // 跨部门只读授权：敏感内容（端点/绑定/上行下行凭据/上游地址）在组视图后脱敏
        return view.manageable() ? view : sanitizeForGrantedRead(view);
    }

    /**
     * 管理权校验（跨部门只读拆分后，写操作与凭据读取走这里）：仅本部门树内或平台管理员。
     * 与旧 requireServer 语义一致；403 而非 404，避免掩盖存在性。
     */
    @Transactional(readOnly = true)
    public McpServer requireManage(Long id, AuthPrincipal principal) {
        McpServer server = serverRepository.findById(id).orElseThrow(() -> PlatformException.notFound("MCP Server", id));
        departmentScope.requireAccess(server.getDeptId(), principal);
        return server;
    }

    /**
     * 读权校验：管理权（本部门树/管理员）之外，放行持有 APPROVED 跨部门授权的部门成员。
     * 授权覆盖部门子树：grant.dept ∈ 祖先链(principal.deptId) 即命中。
     */
    @Transactional(readOnly = true)
    public McpServer requireRead(Long id, AuthPrincipal principal) {
        McpServer server = serverRepository.findById(id).orElseThrow(() -> PlatformException.notFound("MCP Server", id));
        if (departmentScope.canAccess(server.getDeptId(), principal)) {
            return server;
        }
        boolean granted = accessRepository.existsByServerIdAndDeptIdInAndStatus(
                id, departmentScope.deptChainToRoot(principal.deptId()), AccessStatus.APPROVED);
        if (!granted) {
            throw PlatformException.forbidden("无权访问该 Server（可发起跨部门访问申请，需资源方授权）");
        }
        return server;
    }

    @Transactional(readOnly = true)
    public List<ServerDtos.ToolView> tools(Long serverId, AuthPrincipal principal) {
        requireRead(serverId, principal);
        return toolRepository.findByServerIdOrderBySortOrderAsc(serverId).stream()
                .map(overlayService::toToolView)
                .toList();
    }

    /** 「原始 vs 生效」差异视图（BR-2 要求 UI 必须提供）。 */
    @Transactional(readOnly = true)
    public ServerDtos.DiffView diff(Long serverId, AuthPrincipal principal) {
        McpServer server = requireManage(serverId, principal);
        return overlayService.diff(server, toolRepository.findByServerIdOrderBySortOrderAsc(serverId));
    }

    /** 运行时生效模型预览：Executor 发布后将加载的内容（不含任何凭据）。 */
    @Transactional(readOnly = true)
    public ServerDtos.EffectiveModelView effectiveModel(Long serverId, AuthPrincipal principal) {
        McpServer server = requireManage(serverId, principal);
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

    /** 新建空 MCP Server（先建基础信息，再在该 Server 下注册多份 Swagger 文档）。 */
    @Transactional
    public ServerDtos.ServerView create(ServerDtos.ServerCreateRequest request, AuthPrincipal principal) {
        String name = request.name() == null ? null : request.name().trim();
        if (name == null || name.isEmpty()) {
            throw PlatformException.validation("Server 名称不能为空", Map.of("field", "name"));
        }
        Long ownerDeptId = request.deptId() != null ? request.deptId() : principal.deptId();
        if (ownerDeptId == null) {
            throw PlatformException.validation("必须指定归属部门", Map.of("field", "deptId"));
        }
        departmentScope.requireAccess(ownerDeptId, principal);
        departmentService.require(ownerDeptId);

        String seed = name + "#" + System.currentTimeMillis();
        String segment = request.pathSegment() != null && !request.pathSegment().isBlank()
                ? pathSegmentGuard.requireAvailable(request.pathSegment(), null)
                : pathSegmentGuard.derive(name, seed);

        McpServer server = new McpServer();
        server.setDeptId(ownerDeptId);
        // 空 Server 还没有挂任何 registration，置 NULL（占位 0 会违反 FK→api_registration）
        server.setProtocolVersion(com.mcpbridge.common.protocol.McpProtocol.SUPPORTED_VERSION);
        server.setStatus(ServerStatus.DRAFT);
        server.setCreatedBy(principal.userId());
        server.setListTtlMs(com.mcpbridge.common.protocol.McpProtocol.DEFAULT_LIST_TTL_MS);
        overlayService.writeBaseModel(server, new OverlayService.ServerBase(
                name, request.title(), request.description(), null, segment, java.util.List.of()));
        overlayService.applyServerOverlay(server);
        server.setAuthD(com.mcpbridge.common.util.Json.write(com.mcpbridge.common.snapshot.AuthDSnapshot.none()));
        McpServer saved = serverRepository.save(server);
        auditService.record(AuditAction.SERVER_UPDATE, "server", saved.getId(), Map.of(
                "action", "create", "name", name, "pathSegment", segment, "deptId", ownerDeptId));
        return toViews(List.of(saved), principal).get(saved.getId());
    }

    /** SVR-01：名称 / 展示名 / 描述 / PATH 末段 / list 缓存 TTL。 */
    @Transactional
    public ServerDtos.ServerView update(Long id, ServerDtos.ServerUpdateRequest request, AuthPrincipal principal) {
        McpServer server = requireManage(id, principal);
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
        return toViews(List.of(saved), principal).get(saved.getId());
    }

    /**
     * 删除 MCP Server（SVR-06）。
     *
     * <p>语义（与用户确认）：
     * <ul>
     *   <li>已发布（存在 current + PUBLISHED 的 binding）返回 409，必须先下线再删；
     *       草稿等未上线状态可直接删；</li>
     *   <li>关联数据由 DB 级联清理：mcp_tool、auth_config（Auth-B）、server_upstream、
     *       publish_binding（发布历史）、server_access（跨部门授权）；</li>
     *   <li>「与该 Server 相关」的接口文档记录（api_registration）连坐删除：
     *       主 registration + 聚合注册进本 Server 的 registration（server_upstream.service_id
     *       命中数字 registrationId 的记录）。若某条记录仍被其他 Server 引用则跳过（防御，正常不可达）。</li>
     * </ul>
     */
    @Transactional
    public void delete(Long id, AuthPrincipal principal) {
        McpServer server = requireManage(id, principal);

        List<PublishBinding> live = bindingRepository.findByServerIdOrderByIdDesc(id).stream()
                .filter(b -> b.isCurrent() && b.getState() == BindingState.PUBLISHED)
                .toList();
        if (!live.isEmpty()) {
            List<String> clusters = live.stream()
                    .map(b -> clusterRepository.findById(b.getClusterId())
                            .map(ExecutorCluster::getName)
                            .orElse("cluster#" + b.getClusterId()))
                    .toList();
            throw PlatformException.conflict("该 Server 已发布到集群，请先下线后再删除",
                    Map.of("serverId", id, "pathSegment", nullSafe(server.getPathSegment()), "clusters", clusters));
        }

        // 收集连坐删除的文档注册记录
        List<Long> regIds = linkedRegistrationIds(server);

        // 解除 server → registration 的外键（registration_id 允许 NULL），
        // 避免同一事务里先删 server 再删 registration 时受 flush 顺序影响
        if (server.getRegistrationId() != null) {
            server.setRegistrationId(null);
            serverRepository.save(server);
        }
        // 删除 Server：mcp_tool / auth_config / server_upstream / publish_binding / server_access 由 DB 级联清理
        serverRepository.delete(server);

        List<Long> deletedRegs = new ArrayList<>();
        for (Long regId : regIds) {
            if (serverRepository.findByRegistrationId(regId).isEmpty()) {
                registrationRepository.deleteById(regId);
                deletedRegs.add(regId);
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "delete");
        detail.put("name", nullSafe(server.getName()));
        detail.put("pathSegment", nullSafe(server.getPathSegment()));
        detail.put("deptId", server.getDeptId());
        if (!deletedRegs.isEmpty()) {
            detail.put("deletedRegistrations", deletedRegs);
        }
        auditService.record(AuditAction.SERVER_DELETE, "server", id, detail);
    }

    /** 本 Server 直接相关的文档记录：主 registration + 聚合注册（serviceId 为数字且存在对应记录）的 registration。 */
    private List<Long> linkedRegistrationIds(McpServer server) {
        Set<Long> ids = new LinkedHashSet<>();
        if (server.getRegistrationId() != null) {
            ids.add(server.getRegistrationId());
        }
        for (com.mcpbridge.manager.domain.ServerUpstream upstream
                : upstreamRepository.findByServerIdOrderByServiceIdAsc(server.getId())) {
            String serviceId = upstream.getServiceId();
            if (serviceId != null && serviceId.matches("\\d+")) {
                long candidate = Long.parseLong(serviceId);
                if (registrationRepository.existsById(candidate)) {
                    ids.add(candidate);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** EXE-03 / EXE-04：按 serviceId upsert 单个上游服务的配置。 */
    @Transactional
    public ServerDtos.ServerView upsertUpstream(Long id, ServerDtos.UpstreamEntryRequest request, AuthPrincipal principal) {
        McpServer server = requireManage(id, principal);
        String serviceId = request.serviceId() == null || request.serviceId().isBlank()
                ? "default" : request.serviceId();
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
        UpstreamSnapshot config = new UpstreamSnapshot(
                baseUrls,
                request.lbStrategy() == null ? UpstreamSnapshot.LbStrategy.ROUND_ROBIN : request.lbStrategy(),
                List.of(),
                orDefault(request.connectTimeoutMs(), 3_000L),
                orDefault(request.readTimeoutMs(), 30_000L),
                orDefault(request.retries(), 1),
                request.retryOnStatus() == null ? List.of(502, 503, 504) : request.retryOnStatus(),
                circuitBreaker);

        // upsert：serviceId 存在则更新，否则新增
        com.mcpbridge.manager.domain.ServerUpstream upstream = upstreamRepository
                .findByServerIdAndServiceId(server.getId(), serviceId)
                .orElseGet(() -> {
                    com.mcpbridge.manager.domain.ServerUpstream u = new com.mcpbridge.manager.domain.ServerUpstream();
                    u.setServerId(server.getId());
                    u.setServiceId(serviceId);
                    return u;
                });
        upstream.setName(truncate(request.name(), 128) == null ? serviceId : truncate(request.name(), 128));
        upstream.setBaseUrls(Json.write(config.baseUrls()));
        upstream.setLbStrategy(config.lbStrategy().name());
        upstream.setConnectTimeout(config.connectTimeoutMs());
        upstream.setReadTimeout(config.readTimeoutMs());
        upstream.setRetries(config.retries());
        upstream.setRetryOnStatus(Json.MAPPER.valueToTree(config.retryOnStatus()).toString());
        upstream.setCircuitBreaker(Json.MAPPER.valueToTree(config.circuitBreaker()).toString());
        upstreamRepository.save(upstream);

        if (server.getStatus() == ServerStatus.DRAFT) {
            server.setStatus(ServerStatus.CONFIGURED);
        }
        McpServer saved = serverRepository.save(server);
        auditService.record(AuditAction.SERVER_UPDATE, "server", saved.getId(), Map.of(
                "upstream", Map.of(
                        "serviceId", serviceId,
                        "baseUrls", baseUrls,
                        "lbStrategy", config.lbStrategy().name(),
                        "connectTimeoutMs", config.connectTimeoutMs(),
                        "readTimeoutMs", config.readTimeoutMs(),
                        "retries", config.retries())));
        return toViews(List.of(saved), principal).get(saved.getId());
    }

    /** SVR-02：单个 Tool 的覆盖编辑。 */
    @Transactional
    public ServerDtos.ToolView updateToolOverlay(Long serverId, Long toolId,
                                                 ServerDtos.ToolOverlayRequest request, AuthPrincipal principal) {
        McpServer server = requireManage(serverId, principal);
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
        McpServer server = requireManage(serverId, principal);
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
        McpServer server = requireManage(serverId, principal);
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
        McpServer server = requireManage(serverId, principal);
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

    /** 读取 Server 的所有上游服务视图（多服务支持）；无配置时返回空列表。 */
    public List<ServerDtos.UpstreamView> upstreamViewsOf(McpServer server) {
        return upstreamRepository.findByServerIdOrderByServiceIdAsc(server.getId()).stream()
                .map(u -> new ServerDtos.UpstreamView(
                        u.getServiceId(),
                        u.getName(),
                        toUpstreamSnapshot(u),
                        null,  // authB 视图后续按需填充
                        u.getUpdatedAt()))
                .toList();
    }

    /** 删除某个上游服务配置（多服务场景下移除一份 Swagger 的上游）。 */
    @Transactional
    public ServerDtos.ServerView deleteUpstream(Long id, String serviceId, AuthPrincipal principal) {
        McpServer server = requireManage(id, principal);
        com.mcpbridge.manager.domain.ServerUpstream upstream = upstreamRepository
                .findByServerIdAndServiceId(server.getId(), serviceId)
                .orElseThrow(() -> PlatformException.notFound("上游服务 " + serviceId, id));
        upstreamRepository.delete(upstream);
        auditService.record(AuditAction.SERVER_UPDATE, "server", server.getId(), Map.of(
                "deletedUpstream", serviceId));
        return toViews(List.of(serverRepository.save(server)), principal).get(id);
    }

    /** 读取 Server 的所有上游快照（Executor 装配用）。 */
    public List<UpstreamSnapshot> upstreamConfigsOf(McpServer server) {
        return upstreamRepository.findByServerIdOrderByServiceIdAsc(server.getId()).stream()
                .map(this::toUpstreamSnapshot)
                .toList();
    }

    /** 读取 Server 的所有上游 Entry（含 serviceId / name / config / authB），快照装配用。 */
    public List<com.mcpbridge.common.snapshot.UpstreamEntry> upstreamEntriesOf(McpServer server) {
        return upstreamRepository.findByServerIdOrderByServiceIdAsc(server.getId()).stream()
                .map(u -> com.mcpbridge.common.snapshot.UpstreamEntry.of(
                        u.getServiceId(),
                        u.getName(),
                        toUpstreamSnapshot(u),
                        readAuthB(u.getAuthB())))
                .toList();
    }

    private com.mcpbridge.common.snapshot.AuthBSnapshot readAuthB(String json) {
        if (json == null || json.isBlank()) return null;
        try { return Json.read(json, com.mcpbridge.common.snapshot.AuthBSnapshot.class); }
        catch (RuntimeException e) { return null; }
    }

    private UpstreamSnapshot toUpstreamSnapshot(com.mcpbridge.manager.domain.ServerUpstream u) {
        List<String> baseUrls = readStringList(u.getBaseUrls());
        UpstreamSnapshot.LbStrategy lb = UpstreamSnapshot.LbStrategy.ROUND_ROBIN;
        if (u.getLbStrategy() != null) {
            try { lb = UpstreamSnapshot.LbStrategy.valueOf(u.getLbStrategy()); } catch (IllegalArgumentException ignore) {}
        }
        UpstreamSnapshot.CircuitBreaker cb = UpstreamSnapshot.CircuitBreaker.defaults();
        if (u.getCircuitBreaker() != null && !u.getCircuitBreaker().isBlank()) {
            try { cb = Json.read(u.getCircuitBreaker(), UpstreamSnapshot.CircuitBreaker.class); } catch (RuntimeException ignore) {}
        }
        return new UpstreamSnapshot(
                baseUrls,
                lb,
                readIntegerList(u.getWeights()),
                u.getConnectTimeout(),
                u.getReadTimeout(),
                u.getRetries(),
                readIntegerList(u.getRetryOnStatus()).isEmpty() ? List.of(502, 503, 504) : readIntegerList(u.getRetryOnStatus()),
                cb);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}); }
        catch (RuntimeException e) { return List.of(); }
    }

    private List<Integer> readIntegerList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {}); }
        catch (RuntimeException e) { return List.of(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public String endpointTemplate(ExecutorCluster cluster) {
        String prefix = cluster == null || cluster.getPathPrefix() == null
                ? PathSegments.DEFAULT_RESERVED_PREFIX : cluster.getPathPrefix();
        String entrypoint = cluster == null ? "" : cluster.getEntrypoint().replaceAll("/+$", "");
        return entrypoint + "/" + prefix + "/{末段}";
    }

    // ------------------------------------------------------------------ 内部工具

    private Map<Long, ServerDtos.ServerView> toViews(List<McpServer> servers, AuthPrincipal principal) {
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
            // getOrDefault 的兜底是共享不可变 List.of()，不能原地 sort；
            // 拷贝成可变列表（无绑定的新 Server 走这里，曾在 create 后抛 UnsupportedOperationException）
            List<PublishBinding> bindings = new ArrayList<>(
                    bindingsByServer.getOrDefault(server.getId(), List.of()));
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
            boolean manageable = departmentScope.canAccess(server.getDeptId(), principal);
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
                    upstreamViewsOf(server),
                    authBViews.getOrDefault(server.getId(), AuthConfigService.emptyAuthBView()),
                    authConfigService.authDView(server),
                    bindingViews,
                    server.getCreatedAt(),
                    server.getUpdatedAt(),
                    manageable));
        }
        return result;
    }

    /**
     * 跨部门只读脱敏：丢弃端点、绑定、Auth-B/Auth-D、上游地址与上游鉴权，
     * 只保留基本信息与统计。manageable=false 的 ServerView 必须过这道门才能出网。
     */
    private ServerDtos.ServerView sanitizeForGrantedRead(ServerDtos.ServerView v) {
        List<ServerDtos.UpstreamView> upstreams = v.upstreams().stream()
                .map(u -> new ServerDtos.UpstreamView(u.serviceId(), u.name(), null, null, null))
                .toList();
        return new ServerDtos.ServerView(
                v.id(), v.name(), v.title(), v.description(), v.pathSegment(),
                null,
                v.version(), v.protocolVersion(), v.status(), v.overlayVersion(),
                v.deptId(), v.deptName(), v.registrationId(),
                v.toolCount(), v.enabledToolCount(), v.listTtlMs(),
                upstreams, null, null, List.of(),
                v.createdAt(), v.updatedAt(),
                false);
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