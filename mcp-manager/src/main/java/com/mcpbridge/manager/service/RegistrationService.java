package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.AuthDSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.ToolNames;
import com.mcpbridge.manager.domain.ApiRegistration;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.DocSource;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.OverlayStatus;
import com.mcpbridge.manager.domain.RegistrationStatus;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.ApiRegistrationRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.McpToolRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.parse.DocumentFetcher;
import com.mcpbridge.manager.service.parse.ParsedApi;
import com.mcpbridge.manager.service.parse.ParsedOperation;
import com.mcpbridge.manager.service.parse.SwaggerParseService;
import com.mcpbridge.manager.web.dto.PageView;
import com.mcpbridge.manager.web.dto.RegistrationDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 接口注册与文档升级（REG-01 / REG-02 / REG-03，BR-1）。
 *
 * <p>关键不变式：
 * <ul>
 *   <li><b>1 个注册 = 1 个 MCP Server</b>，1 个 operation = 1 个 Tool（BR-1）；</li>
 *   <li>原始文档只读留存，并额外保存 sha256；读取原文时重新计算比对，
 *       一旦有人绕过应用层直接改库就报冲突（PRD「双保险」）；</li>
 *   <li>重新解析（re-import）会 <b>保留覆盖</b>：锚点仍在的覆盖继续生效，
 *       锚点消失的覆盖进挂起区并在 diff 报告里显式列出，绝不静默丢弃（REG-03）。</li>
 * </ul>
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private static final int SUMMARY_MAX = 512;
    private static final int DESCRIPTION_MAX = 4000;

    private final ApiRegistrationRepository registrationRepository;
    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final SwaggerParseService parseService;
    private final DocumentFetcher documentFetcher;
    private final OverlayService overlayService;
    private final PathSegmentGuard pathSegmentGuard;
    private final DepartmentScope departmentScope;
    private final DepartmentService departmentService;
    private final AuditService auditService;
    /**
     * 自身代理。重新解析需要「先在事务外抓取文档，再在事务内比对入库」，
     * 同类内部直调会绕过 Spring 代理导致 {@code @Transactional} 失效，故注入懒加载代理转发。
     */
    private final RegistrationService self;

    public RegistrationService(ApiRegistrationRepository registrationRepository,
                               McpServerRepository serverRepository,
                               McpToolRepository toolRepository,
                               SwaggerParseService parseService,
                               DocumentFetcher documentFetcher,
                               OverlayService overlayService,
                               PathSegmentGuard pathSegmentGuard,
                               DepartmentScope departmentScope,
                               DepartmentService departmentService,
                               AuditService auditService,
                               @Lazy RegistrationService self) {
        this.registrationRepository = registrationRepository;
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.parseService = parseService;
        this.documentFetcher = documentFetcher;
        this.overlayService = overlayService;
        this.pathSegmentGuard = pathSegmentGuard;
        this.departmentScope = departmentScope;
        this.departmentService = departmentService;
        this.auditService = auditService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageView<RegistrationDtos.View> page(Pageable pageable, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        Page<ApiRegistration> page = visible == null
                ? registrationRepository.findAll(pageable)
                : registrationRepository.findByDeptIdIn(visible, pageable);
        List<Long> ids = page.getContent().stream().map(ApiRegistration::getId).toList();
        Map<Long, Long> serverIdByRegistration = serverRepository.findByRegistrationIdIn(ids).stream()
                .collect(Collectors.toMap(McpServer::getRegistrationId, McpServer::getId, (a, b) -> a));
        Map<Long, String> deptNames = departmentService.namesOf();
        return PageView.of(page, r -> toView(r, deptNames.get(r.getDeptId()), serverIdByRegistration.get(r.getId())));
    }

    @Transactional(readOnly = true)
    public RegistrationDtos.View view(Long id, AuthPrincipal principal) {
        ApiRegistration registration = require(id, principal);
        Long serverId = serverRepository.findByRegistrationId(id).map(McpServer::getId).orElse(null);
        return toView(registration, departmentService.nameOf(registration.getDeptId()), serverId);
    }

    /**
     * 按 URL 注册：先抓取（无事务），再解析入库（有事务）。
     * 这样网络耗时不会占着数据库连接。
     */
    public RegistrationDtos.View createByUrl(RegistrationDtos.CreateByUrlRequest request, AuthPrincipal principal) {
        DocumentFetcher.FetchResult fetched = documentFetcher.fetch(request.url());
        return create(request.name(), fetched.body(), DocSource.URL, fetched.url(),
                request.deptId(), request.pathSegment(), principal);
    }

    public RegistrationDtos.View createByUpload(String name, MultipartFile file, Long deptId,
                                                String pathSegment, AuthPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw PlatformException.validation("请上传接口文档文件", Map.of("field", "file"));
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlatformException(ErrorCode.PARSE_FAILED, "读取上传文件失败", e);
        }
        return create(name, text, DocSource.FILE, file.getOriginalFilename(), deptId, pathSegment, principal);
    }

    @Transactional
    public RegistrationDtos.View create(String name, String rawDoc, DocSource source, String sourceRef,
                                        Long deptId, String pathSegment, AuthPrincipal principal) {
        String trimmedName = name == null ? null : name.trim();
        if (trimmedName == null || trimmedName.isEmpty()) {
            throw PlatformException.validation("服务名不能为空", Map.of("field", "name"));
        }
        Long ownerDeptId = deptId != null ? deptId : principal.deptId();
        if (ownerDeptId == null) {
            throw PlatformException.validation("必须指定归属部门", Map.of("field", "deptId"));
        }
        departmentScope.requireAccess(ownerDeptId, principal);
        departmentService.require(ownerDeptId);
        if (registrationRepository.existsByNameAndDeptId(trimmedName, ownerDeptId)) {
            throw PlatformException.conflict("同部门下已存在同名注册",
                    Map.of("name", trimmedName, "deptId", ownerDeptId));
        }

        ParsedApi api;
        try {
            api = parseService.parse(rawDoc);
        } catch (PlatformException e) {
            // 审计用 REQUIRES_NEW，业务事务回滚后这条「谁尝试注册了什么」的记录仍然保留（MGM-05）
            if (e.errorCode() == ErrorCode.PARSE_FAILED) {
                auditService.record(AuditAction.REGISTRATION_PARSE_FAILED, "registration", null, Map.of(
                        "name", trimmedName,
                        "deptId", ownerDeptId,
                        "docSource", source.name(),
                        "reason", String.valueOf(e.getMessage())));
            }
            throw e;
        }

        ApiRegistration registration = new ApiRegistration();
        registration.setName(trimmedName);
        registration.setDeptId(ownerDeptId);
        registration.setDocSource(source);
        registration.setSourceRef(truncate(sourceRef, 512));
        registration.setSwaggerVersion(api.specVersion());
        registration.setRawDoc(rawDoc);
        registration.setRawDocSha256(Hashing.sha256Hex(rawDoc));
        registration.setDocVersion(1);
        registration.setDiagnostics(Json.write(api.diagnostics()));
        registration.setOperationCount(api.operations().size());
        registration.setCreatedBy(principal.userId());
        registration.setStatus(api.hasErrors() ? RegistrationStatus.FAILED : RegistrationStatus.READY);
        ApiRegistration saved = registrationRepository.save(registration);

        McpServer server = null;
        if (saved.getStatus() == RegistrationStatus.READY) {
            server = createServerAndTools(saved, api, pathSegment, principal);
        } else {
            log.warn("注册解析存在 ERROR 级诊断，未生成 MCP Server：registrationId={}", saved.getId());
        }

        auditService.record(AuditAction.REGISTRATION_CREATE, "registration", saved.getId(), Map.of(
                "name", trimmedName,
                "deptId", ownerDeptId,
                "docSource", source.name(),
                "specVersion", api.specVersion(),
                "operationCount", api.operations().size(),
                "status", saved.getStatus().name(),
                "sha256", String.valueOf(saved.getRawDocSha256()),
                "serverId", server == null ? "none" : String.valueOf(server.getId())));

        return toView(saved, departmentService.nameOf(ownerDeptId), server == null ? null : server.getId());
    }

    private McpServer createServerAndTools(ApiRegistration registration, ParsedApi api,
                                           String requestedSegment, AuthPrincipal principal) {
        String seed = registration.getName() + "#" + registration.getId();
        String segment = requestedSegment != null && !requestedSegment.isBlank()
                ? pathSegmentGuard.requireAvailable(requestedSegment, null)
                : pathSegmentGuard.derive(api.title(), seed);

        McpServer server = new McpServer();
        server.setDeptId(registration.getDeptId());
        server.setRegistrationId(registration.getId());
        server.setProtocolVersion(McpProtocol.SUPPORTED_VERSION);
        server.setStatus(ServerStatus.DRAFT);
        server.setCreatedBy(principal.userId());
        server.setListTtlMs(McpProtocol.DEFAULT_LIST_TTL_MS);
        overlayService.writeBaseModel(server, new OverlayService.ServerBase(
                api.title(), api.title(), api.description(), api.version(), segment, api.baseUrls()));
        overlayService.applyServerOverlay(server);
        // 初始上游配置直接采用文档声明的地址；没有声明则为空，发布前的校验会拦住（PUB-01）
        server.setUpstream(Json.write(UpstreamSnapshot.defaults(api.baseUrls())));
        server.setAuthD(Json.write(AuthDSnapshot.none()));
        McpServer saved = serverRepository.save(server);

        int order = 0;
        List<McpTool> tools = new ArrayList<>(api.operations().size());
        for (ParsedOperation operation : api.operations()) {
            McpTool tool = new McpTool();
            tool.setServerId(saved.getId());
            tool.setAnchor(truncate(operation.anchor(), 512));
            tool.setMethod(operation.method());
            tool.setPath(truncate(operation.path(), 512));
            tool.setBaseName(operation.name());
            tool.setBaseSummary(truncate(operation.summary(), SUMMARY_MAX));
            tool.setBaseDescription(truncate(operation.description(), DESCRIPTION_MAX));
            tool.setBaseInputSchema(Json.write(operation.inputSchema()));
            tool.setParameterIn(Json.write(operation.parameterIn()));
            tool.setRequestBodyRequired(operation.requestBodyRequired());
            tool.setIdempotent(operation.idempotent());
            tool.setStreaming(operation.streaming());
            tool.setStreamFormat(operation.streamFormat());
            tool.setEnabled(true);
            tool.setOverlayStatus(OverlayStatus.NONE);
            tool.setSortOrder(order++);
            tools.add(tool);
        }
        toolRepository.saveAll(tools);
        return saved;
    }

    /**
     * 读取原始文档（只读）。返回前重算 sha256 与库中值比对，作为「原始文档不可篡改」的第二道保险。
     */
    @Transactional(readOnly = true)
    public String rawDoc(Long id, AuthPrincipal principal) {
        ApiRegistration registration = require(id, principal);
        String raw = registration.getRawDoc();
        String actual = Hashing.sha256Hex(raw == null ? "" : raw);
        if (!Objects.equals(actual, registration.getRawDocSha256())) {
            throw PlatformException.conflict("原始文档完整性校验失败：sha256 与登记值不一致，文档可能在库内被直接修改",
                    Map.of("registrationId", id,
                            "expected", String.valueOf(registration.getRawDocSha256()),
                            "actual", actual));
        }
        return raw;
    }

    /**
     * 重新解析（REG-02 / REG-03 / US-12）。
     *
     * @param rawDoc 新文档；为 null 时按登记的 sourceRef 重新抓取（仅 URL 来源支持）
     */
    public RegistrationDtos.DiffReport reimport(Long id, String rawDoc, AuthPrincipal principal) {
        ApiRegistration registration = require(id, principal);
        String text = rawDoc != null && !rawDoc.isBlank() ? rawDoc : refetch(registration);
        return self.doReimport(registration.getId(), text, principal);
    }

    @Transactional
    public RegistrationDtos.DiffReport doReimport(Long registrationId, String rawDoc, AuthPrincipal principal) {
        ApiRegistration registration = require(registrationId, principal);
        ParsedApi api = parseService.parse(rawDoc);
        McpServer server = serverRepository.findByRegistrationId(registration.getId())
                .orElseThrow(() -> PlatformException.notFound("MCP Server", registration.getId()));
        List<McpTool> existing = toolRepository.findByServerIdOrderBySortOrderAsc(server.getId());
        Map<String, McpTool> byAnchor = new LinkedHashMap<>();
        existing.forEach(t -> byAnchor.put(t.getAnchor(), t));

        Set<String> usedNames = new LinkedHashSet<>();
        existing.forEach(t -> usedNames.add(t.getBaseName()));
        Set<String> newAnchors = new LinkedHashSet<>(api.anchors());

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<McpTool> toSave = new ArrayList<>();
        int nextOrder = existing.stream().mapToInt(McpTool::getSortOrder).max().orElse(-1) + 1;

        for (ParsedOperation operation : api.operations()) {
            McpTool tool = byAnchor.get(operation.anchor());
            if (tool == null) {
                McpTool created = new McpTool();
                created.setServerId(server.getId());
                created.setAnchor(truncate(operation.anchor(), 512));
                created.setMethod(operation.method());
                created.setPath(truncate(operation.path(), 512));
                created.setBaseName(ToolNames.unique(operation.name(), usedNames));
                usedNames.add(created.getBaseName());
                created.setBaseSummary(truncate(operation.summary(), SUMMARY_MAX));
                created.setBaseDescription(truncate(operation.description(), DESCRIPTION_MAX));
                created.setBaseInputSchema(Json.write(operation.inputSchema()));
                created.setParameterIn(Json.write(operation.parameterIn()));
                created.setRequestBodyRequired(operation.requestBodyRequired());
                created.setIdempotent(operation.idempotent());
                created.setStreaming(operation.streaming());
                created.setStreamFormat(operation.streamFormat());
                // 新增接口默认不启用：由开发者确认后再开，避免上游变更直接把新接口暴露出去
                created.setEnabled(false);
                created.setOverlayStatus(OverlayStatus.NONE);
                created.setSortOrder(nextOrder++);
                toSave.add(created);
                added.add(operation.anchor());
                continue;
            }
            if (baseChanged(tool, operation)) {
                changed.add(tool.getAnchor());
            }
            tool.setBaseSummary(truncate(operation.summary(), SUMMARY_MAX));
            tool.setBaseDescription(truncate(operation.description(), DESCRIPTION_MAX));
            tool.setBaseInputSchema(Json.write(operation.inputSchema()));
            tool.setParameterIn(Json.write(operation.parameterIn()));
            tool.setRequestBodyRequired(operation.requestBodyRequired());
            tool.setIdempotent(operation.idempotent());
            tool.setStreaming(operation.streaming());
            tool.setStreamFormat(operation.streamFormat());
            toSave.add(tool);
        }

        for (McpTool tool : existing) {
            if (!newAnchors.contains(tool.getAnchor())) {
                // 接口被删：自动停用，但保留行与覆盖，便于上游回滚时恢复
                tool.setEnabled(false);
                removed.add(tool.getAnchor());
                toSave.add(tool);
            }
        }
        toolRepository.saveAll(toSave);

        OverlayService.ReconcileResult reconcile = overlayService.reconcileAnchors(existing, newAnchors);
        toolRepository.saveAll(existing);

        List<String> preserved = existing.stream()
                .filter(t -> t.getOverlayStatus() == OverlayStatus.ACTIVE)
                .map(McpTool::getAnchor)
                .toList();

        // 刷新 Server 基座：标题/描述/版本跟随新文档，用户覆盖仍然优先；PATH 末段是稳定契约，不随文档变
        String currentSegment = overlayService.baseOf(server).pathSegment();
        overlayService.writeBaseModel(server, new OverlayService.ServerBase(
                api.title(), api.title(), api.description(), api.version(),
                currentSegment, api.baseUrls()));
        overlayService.applyServerOverlay(server);
        if (server.getStatus() == ServerStatus.DRAFT) {
            // 仅在用户尚未配置上游时同步文档声明的地址，避免覆盖人工配置
            server.setUpstream(Json.write(UpstreamSnapshot.defaults(api.baseUrls())));
        }
        server.setOverlayVersion(server.getOverlayVersion() + 1);
        serverRepository.save(server);

        registration.setRawDoc(rawDoc);
        registration.setRawDocSha256(Hashing.sha256Hex(rawDoc));
        registration.setSwaggerVersion(api.specVersion());
        registration.setDocVersion(registration.getDocVersion() + 1);
        registration.setDiagnostics(Json.write(api.diagnostics()));
        registration.setOperationCount(api.operations().size());
        registration.setStatus(api.hasErrors() ? RegistrationStatus.FAILED : RegistrationStatus.READY);
        registrationRepository.save(registration);

        auditService.record(AuditAction.REGISTRATION_REIMPORT, "registration", registration.getId(), Map.of(
                "docVersion", registration.getDocVersion(),
                "added", added,
                "removed", removed,
                "changed", changed,
                "suspendedOverlays", reconcile.suspended(),
                "restoredOverlays", reconcile.restored()));

        return new RegistrationDtos.DiffReport(registration.getDocVersion(), added, removed, changed,
                reconcile.suspended(), preserved);
    }

    private String refetch(ApiRegistration registration) {
        if (registration.getDocSource() != DocSource.URL || registration.getSourceRef() == null) {
            throw PlatformException.validation("该注册不是 URL 来源，请在请求体中直接提交新文档内容",
                    Map.of("registrationId", registration.getId(), "docSource", registration.getDocSource().name()));
        }
        return documentFetcher.fetch(registration.getSourceRef()).body();
    }

    @Transactional(readOnly = true)
    public ApiRegistration require(Long id, AuthPrincipal principal) {
        ApiRegistration registration = registrationRepository.findById(id)
                .orElseThrow(() -> PlatformException.notFound("接口注册", id));
        departmentScope.requireAccess(registration.getDeptId(), principal);
        return registration;
    }

    /** 基座签名是否发生变化：决定该接口是否进入 diff 报告的 changed 列表。 */
    private boolean baseChanged(McpTool tool, ParsedOperation operation) {
        return !Objects.equals(tool.getBaseSummary(), operation.summary())
                || !Objects.equals(tool.getBaseDescription(), operation.description())
                || !jsonEquals(tool.getBaseInputSchema(), operation.inputSchema())
                || !Objects.equals(overlayService.parameterInOf(tool), operation.parameterIn())
                || tool.isRequestBodyRequired() != operation.requestBodyRequired()
                || tool.isIdempotent() != operation.idempotent()
                || tool.isStreaming() != operation.streaming()
                || !Objects.equals(tool.getStreamFormat(), operation.streamFormat());
    }

    private static boolean jsonEquals(String stored, com.fasterxml.jackson.databind.JsonNode candidate) {
        if (stored == null || stored.isBlank()) {
            return candidate == null || candidate.isNull();
        }
        try {
            return Objects.equals(Json.tree(stored), candidate);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RegistrationDtos.View toView(ApiRegistration r, String deptName, Long serverId) {
        return new RegistrationDtos.View(
                r.getId(),
                r.getName(),
                r.getDeptId(),
                deptName,
                r.getDocSource(),
                r.getSourceRef(),
                r.getSwaggerVersion(),
                r.getDocVersion(),
                r.getStatus(),
                r.getOperationCount(),
                diagnosticsOf(r),
                serverId,
                r.getRawDocSha256(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private List<RegistrationDtos.Diagnostic> diagnosticsOf(ApiRegistration r) {
        String json = r.getDiagnostics();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<RegistrationDtos.Diagnostic>>() {
            });
        } catch (RuntimeException e) {
            log.warn("诊断信息反序列化失败 registrationId={}", r.getId(), e);
            return List.of();
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}