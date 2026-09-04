package com.mcpbridge.manager.web;

import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.domain.AuditLog;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.AuditService;
import com.mcpbridge.manager.service.DepartmentScope;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.AuditDtos;
import com.mcpbridge.manager.web.dto.PageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 操作审计查询（MGM-05 / US-10）。
 *
 * <p>只读：审计是 append-only 的，平台不提供任何修改或删除入口。
 * 部门隔离同样生效——部门管理员只能审计到自己部门及下级。
 */
@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {

    private final AuditService auditService;
    private final DepartmentScope departmentScope;

    public AuditController(AuditService auditService, DepartmentScope departmentScope) {
        this.auditService = auditService;
        this.departmentScope = departmentScope;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<PageView<AuditDtos.AuditView>> page(
            @RequestParam(required = false) String action,
            @PageableDefault(size = 50) Pageable pageable,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Page<AuditLog> page = auditService.query(action, pageable, principal, departmentScope);
        return ApiResponse.ok(PageView.of(page, AuditController::toView));
    }

    /** 按对象查审计：Server 详情页的「变更历史」标签用。 */
    @GetMapping("/target/{targetType}/{targetId}")
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<PageView<AuditDtos.AuditView>> byTarget(@PathVariable String targetType,
                                                               @PathVariable String targetId,
                                                               @PageableDefault(size = 50) Pageable pageable) {
        return ApiResponse.ok(PageView.of(auditService.queryByTarget(targetType, targetId, pageable),
                AuditController::toView));
    }

    private static AuditDtos.AuditView toView(AuditLog entry) {
        return new AuditDtos.AuditView(
                entry.getId(),
                entry.getActorId(),
                entry.getActorName(),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getDeptId(),
                detailOf(entry.getDetail()),
                entry.getTraceId(),
                entry.getClientIp(),
                entry.getCreatedAt());
    }

    /** detail 落库前已脱敏，这里只做 JSON → Map 还原；损坏时降级为空 map 而不是整个接口失败。 */
    private static Map<String, Object> detailOf(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = Json.toMap(Json.tree(json));
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}