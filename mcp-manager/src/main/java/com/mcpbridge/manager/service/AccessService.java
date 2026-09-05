package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.ServerAccess;
import com.mcpbridge.manager.domain.User;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.ServerAccessRepository;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.AccessDtos;
import com.mcpbridge.manager.web.dto.PageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨部门访问申请与授权（MGM-04 显式授权闭环）。
 *
 * <p>规则：
 * <ul>
 *   <li>按部门申请、按部门授权：申请人以自己的归属部门发起，授权覆盖该部门及子树（与部门树可见性语义一致）；</li>
 *   <li>审批人 = Server 所属部门的部门管理员（DEPT_ADMIN）或平台管理员（资源方授权）；</li>
 *   <li>授权只读：读路径放行在 {@link ServerService#requireRead}，写与凭据仍走 {@link ServerService#requireManage}。</li>
 * </ul>
 */
@Service
public class AccessService {

    private final ServerAccessRepository accessRepository;
    private final McpServerRepository serverRepository;
    private final UserRepository userRepository;
    private final DepartmentScope departmentScope;
    private final DepartmentService departmentService;
    private final ServerService serverService;
    private final AuditService auditService;

    public AccessService(ServerAccessRepository accessRepository,
                         McpServerRepository serverRepository,
                         UserRepository userRepository,
                         DepartmentScope departmentScope,
                         DepartmentService departmentService,
                         ServerService serverService,
                         AuditService auditService) {
        this.accessRepository = accessRepository;
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.departmentScope = departmentScope;
        this.departmentService = departmentService;
        this.serverService = serverService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 查询

    /** 平台管理员是否可做资源方审批（todo / approve / reject / revoke 的兜底资格）。 */
    public boolean isApprover(AuthPrincipal principal) {
        return principal != null
                && (principal.isPlatformAdmin()
                || (principal.roles() != null && principal.roles().contains(PermissionCatalog.ROLE_DEPT_ADMIN)));
    }

    /**
     * 可申请目录：principal 不可直接访问的 Server 最小信息 + 本部门申请状态。
     * 平台管理员可见全部，无需申请 → 空目录。
     */
    @Transactional(readOnly = true)
    public PageView<AccessDtos.CatalogRow> catalog(Pageable pageable, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        if (visible == null) {
            // 平台管理员可见全部，无需申请
            return new PageView<>(List.of(), 0, pageable.getPageNumber(), pageable.getPageSize(), 0);
        }
        Page<McpServer> page = serverRepository.findByDeptIdNotIn(visible, pageable);

        // 本部门自己的申请状态（目录上每行一个状态：NONE / PENDING / REJECTED / APPROVED）
        List<Long> ids = page.getContent().stream().map(McpServer::getId).toList();
        Map<Long, ServerAccess> mineByServer = ids.isEmpty() || principal.deptId() == null
                ? Map.of()
                : accessRepository.findByServerIdInAndDeptId(ids, principal.deptId()).stream()
                        .collect(Collectors.toMap(ServerAccess::getServerId, Function.identity()));

        // 祖先部门已获授权 → 授权覆盖本部门子树，本部门其实已可读：目录同样标 APPROVED（无本部门记录行）
        List<Long> chain = departmentScope.deptChainToRoot(principal.deptId());
        Set<Long> ancestorGrantedServerIds = chain.isEmpty() ? Set.of()
                : accessRepository.findByDeptIdInAndStatus(chain, AccessStatus.APPROVED).stream()
                        .map(ServerAccess::getServerId)
                        .collect(Collectors.toSet());

        Map<Long, String> deptNames = departmentService.namesOf();
        List<AccessDtos.CatalogRow> rows = new ArrayList<>(page.getContent().size());
        for (McpServer server : page.getContent()) {
            ServerAccess mine = mineByServer.get(server.getId());
            AccessStatus myStatus = mine != null
                    ? mine.getStatus()
                    : (ancestorGrantedServerIds.contains(server.getId()) ? AccessStatus.APPROVED : null);
            rows.add(new AccessDtos.CatalogRow(
                    server.getId(),
                    server.getName(),
                    server.getTitle(),
                    server.getPathSegment(),
                    server.getStatus(),
                    server.getDeptId(),
                    deptNames.get(server.getDeptId()),
                    myStatus,
                    mine == null ? null : mine.getId(),
                    server.getCreatedAt()));
        }
        return new PageView<>(rows, page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
    }

    /** 我（本部门及子树）发起的申请记录。平台管理员无「本部门」概念 → 空。 */
    @Transactional(readOnly = true)
    public List<AccessDtos.AccessView> mine(AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        if (visible == null) {
            return List.of();
        }
        return toViews(accessRepository.findByDeptIdInOrderByIdDesc(visible), principal);
    }

    /** 待我审批：PENDING 且 Server 属于我的管理树（审批资格在服务层强制）。 */
    @Transactional(readOnly = true)
    public List<AccessDtos.AccessView> todo(AuthPrincipal principal) {
        if (!isApprover(principal)) {
            throw PlatformException.forbidden("仅资源方部门管理员或平台管理员可审批访问申请");
        }
        return byStatusInManageTree(AccessStatus.PENDING, principal);
    }

    /** 已授权（可回收）：APPROVED 且 Server 属于我的管理树。 */
    @Transactional(readOnly = true)
    public List<AccessDtos.AccessView> grants(AuthPrincipal principal) {
        if (!isApprover(principal)) {
            throw PlatformException.forbidden("仅资源方部门管理员或平台管理员可管理已授权访问");
        }
        return byStatusInManageTree(AccessStatus.APPROVED, principal);
    }

    private List<AccessDtos.AccessView> byStatusInManageTree(AccessStatus status, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        List<ServerAccess> rows = accessRepository.findByStatusOrderByIdAsc(status);
        Map<Long, McpServer> servers = serverIndex(rows.stream().map(ServerAccess::getServerId).toList());
        List<ServerAccess> scoped = rows.stream()
                .filter(row -> {
                    McpServer server = servers.get(row.getServerId());
                    if (server == null) {
                        return false; // Server 已删除（级联删申请）
                    }
                    return visible == null || visible.contains(server.getDeptId());
                })
                .toList();
        return toViews(scoped, principal);
    }

    // ------------------------------------------------------------------ 写入

    /** 发起申请：以我的归属部门申请访问某 Server（资源方部门管理员审批）。 */
    @Transactional
    public AccessDtos.AccessView apply(AccessDtos.ApplyRequest request, AuthPrincipal principal) {
        Long myDeptId = principal.deptId();
        if (myDeptId == null) {
            throw PlatformException.validation("当前账号未归属部门，无法发起访问申请", Map.of());
        }
        McpServer server = serverRepository.findById(request.serverId())
                .orElseThrow(() -> PlatformException.notFound("MCP Server", request.serverId()));
        if (departmentScope.canAccess(server.getDeptId(), principal)) {
            throw PlatformException.validation("该 Server 在本部门可访问范围内，无需申请", Map.of());
        }
        String reason = request.reason() == null ? null : request.reason().trim();

        ServerAccess row = accessRepository.findByServerIdAndDeptId(server.getId(), myDeptId).orElse(null);
        if (row != null) {
            if (row.getStatus() == AccessStatus.PENDING) {
                throw PlatformException.validation("该部门已有待审批的访问申请", Map.of("accessId", row.getId()));
            }
            if (row.getStatus() == AccessStatus.APPROVED) {
                throw PlatformException.validation("该部门已获得本 Server 的访问授权", Map.of("accessId", row.getId()));
            }
            // REJECTED / REVOKED：带新理由重新申请，覆盖旧记录
            row.setStatus(AccessStatus.PENDING);
            row.setReason(reason);
            row.setRequestedBy(principal.userId());
            row.setRequestedAt(Instant.now());
            row.setReviewedBy(null);
            row.setReviewedAt(null);
            row.setReviewNote(null);
        } else {
            row = new ServerAccess();
            row.setServerId(server.getId());
            row.setDeptId(myDeptId);
            row.setStatus(AccessStatus.PENDING);
            row.setReason(reason);
            row.setRequestedBy(principal.userId());
            row.setRequestedAt(Instant.now());
        }
        accessRepository.save(row);
        auditService.record(AuditAction.ACCESS_APPLY, "access", row.getId(), Map.of(
                "serverId", server.getId(),
                "serverName", server.getName(),
                "deptId", myDeptId,
                "reason", String.valueOf(reason)));
        return toViews(List.of(row), principal).get(0);
    }

    /** 通过（PENDING → APPROVED）。审批人 = 资源方部门管理员 / 平台管理员。 */
    @Transactional
    public AccessDtos.AccessView approve(Long id, AccessDtos.ReviewRequest request, AuthPrincipal principal) {
        return review(id, AccessStatus.APPROVED, request, principal);
    }

    /** 驳回（PENDING → REJECTED，可带意见；申请人可带新理由重提）。 */
    @Transactional
    public AccessDtos.AccessView reject(Long id, AccessDtos.ReviewRequest request, AuthPrincipal principal) {
        return review(id, AccessStatus.REJECTED, request, principal);
    }

    /** 回收（APPROVED → REVOKED）：资源方随时可撤销已授权。 */
    @Transactional
    public AccessDtos.AccessView revoke(Long id, AccessDtos.ReviewRequest request, AuthPrincipal principal) {
        return review(id, AccessStatus.REVOKED, request, principal);
    }

    private AccessDtos.AccessView review(Long id, AccessStatus target, AccessDtos.ReviewRequest request,
                                         AuthPrincipal principal) {
        ServerAccess row = accessRepository.findById(id)
                .orElseThrow(() -> PlatformException.notFound("访问申请", id));
        AccessStatus from = switch (target) {
            case APPROVED, REJECTED -> AccessStatus.PENDING;
            case REVOKED -> AccessStatus.APPROVED;
            default -> throw new IllegalArgumentException("不支持的目标状态: " + target);
        };
        if (row.getStatus() != from) {
            throw PlatformException.validation("当前状态不允许该操作（期望 " + from + "，实际 " + row.getStatus() + "）", Map.of());
        }
        // 资源方：Server 必须在我的管理树内，且我具备审批资格（部门管理员 / 平台管理员）
        McpServer server = serverService.requireManage(row.getServerId(), principal);
        if (!isApprover(principal)) {
            throw PlatformException.forbidden("仅资源方部门管理员或平台管理员可审批访问申请");
        }
        row.setStatus(target);
        row.setReviewedBy(principal.userId());
        row.setReviewedAt(Instant.now());
        row.setReviewNote(request == null || request.note() == null ? null : request.note().trim());
        accessRepository.save(row);
        String action = switch (target) {
            case APPROVED -> AuditAction.ACCESS_APPROVE;
            case REJECTED -> AuditAction.ACCESS_REJECT;
            default -> AuditAction.ACCESS_REVOKE;
        };
        auditService.record(action, "access", row.getId(), Map.of(
                "serverId", server.getId(),
                "serverName", server.getName(),
                "deptId", row.getDeptId(),
                "note", String.valueOf(row.getReviewNote())));
        return toViews(List.of(row), principal).get(0);
    }

    // ------------------------------------------------------------------ 内部

    private Map<Long, McpServer> serverIndex(Collection<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return Map.of();
        }
        return serverRepository.findAllById(serverIds).stream()
                .collect(Collectors.toMap(McpServer::getId, Function.identity()));
    }

    private List<AccessDtos.AccessView> toViews(List<ServerAccess> rows, AuthPrincipal principal) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, McpServer> servers = serverIndex(rows.stream().map(ServerAccess::getServerId).toList());
        Map<Long, String> deptNames = departmentService.namesOf();
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(r -> {
            userIds.add(r.getRequestedBy());
            if (r.getReviewedBy() != null) {
                userIds.add(r.getReviewedBy());
            }
        });
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getDisplayName));
        Map<Long, Boolean> manageableCache = new HashMap<>();
        List<AccessDtos.AccessView> result = new ArrayList<>(rows.size());
        for (ServerAccess row : rows) {
            McpServer server = servers.get(row.getServerId());
            if (server == null) {
                continue; // Server 已删（级联），跳过
            }
            result.add(new AccessDtos.AccessView(
                    row.getId(),
                    server.getId(),
                    server.getName(),
                    server.getPathSegment(),
                    row.getDeptId(),
                    deptNames.get(row.getDeptId()),
                    row.getReason(),
                    row.getStatus(),
                    row.getRequestedBy(),
                    userNames.get(row.getRequestedBy()),
                    row.getRequestedAt(),
                    row.getReviewedBy(),
                    row.getReviewedBy() == null ? null : userNames.get(row.getReviewedBy()),
                    row.getReviewedAt(),
                    row.getReviewNote(),
                    manageableCache.computeIfAbsent(server.getDeptId(),
                            deptId -> departmentScope.canAccess(deptId, principal))));
        }
        return result;
    }
}
