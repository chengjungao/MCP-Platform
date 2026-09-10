package com.mcpbridge.manager.web;

import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.AuditAction;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    /**
     * 显式指定 charset 而不是用 {@code StandardCharsets} 默认值：没有 {@code charset=UTF-8} 时
     * 部分浏览器会按 latin-1 解析下载内容，中文直接变乱码。
     */
    private static final MediaType CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final AuditService auditService;
    private final DepartmentScope departmentScope;
    private final ManagerProperties properties;

    public AuditController(AuditService auditService,
                           DepartmentScope departmentScope,
                           ManagerProperties properties) {
        this.auditService = auditService;
        this.departmentScope = departmentScope;
        this.properties = properties;
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

    /**
     * 导出当前筛选条件下的审计为 CSV（MGM-05）。
     *
     * <p>几个刻意的选择：
     * <ul>
     *   <li><b>不写 {@code produces}</b>，而是显式设 {@code Content-Type}：一旦在映射上声明
     *       {@code text/csv}，后续抛出的校验异常在内容协商阶段就可能被拒绝渲染成 JSON，
     *       前端拿到的会是一个没有 message 的 406，排障体验极差。</li>
     *   <li><b>先取内容、再记审计</b>：反过来的话这条"我导出了"的记录会出现在自己的导出文件里，
     *       看起来像是重复导出。代价是本次导出不含自己这一条，接受。</li>
     *   <li><b>超上限抛 409</b>，由全局异常处理器转成正常信封，前端能拿到可读原因。</li>
     *   <li><b>no-store</b>：审计文件是敏感数据，不让浏览器/代理缓存。</li>
     * </ul>
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('audit:read')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String action,
                                         @AuthenticationPrincipal AuthPrincipal principal) {
        AuditDtos.Export exported = auditService.exportCsv(action, principal, departmentScope,
                properties.audit().exportMaxRows());
        auditService.record(AuditAction.AUDIT_EXPORT, "audit", null, Map.of(
                "action", action == null || action.isBlank() ? "全部" : action.trim(),
                "rows", exported.rows(),
                "filename", filename()));
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(exported.csv().getBytes(StandardCharsets.UTF_8));
    }

    /** 文件名带到分钟的时间戳：同一天里多次导出不会互相覆盖，也便于对上桶里的 OSS 对象。 */
    private static String filename() {
        return "audit-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + "Z.csv";
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