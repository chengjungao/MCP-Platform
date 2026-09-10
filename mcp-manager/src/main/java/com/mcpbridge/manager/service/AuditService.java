package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.util.Csv;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.manager.domain.AuditLog;
import com.mcpbridge.manager.repository.AuditLogRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.security.CurrentPrincipal;
import com.mcpbridge.manager.web.dto.AuditDtos;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Set;

/**
 * 操作审计（MGM-05 / US-10）。
 *
 * <p>append-only：只插入与查询。写入用 {@code REQUIRES_NEW}，保证业务事务回滚时审计仍然留存
 * （例如发布失败也要留下「谁在什么时候尝试发布」）。
 * detail 统一过 {@link LogSanitizer}，避免密钥与 PII 落库（SEC-02 / R8）。
 *
 * <p>DB 层另有一道闸：V8 在 {@code audit_log} 上装了 BEFORE UPDATE/DELETE 触发器并 REVOKE 了
 * 相关权限。应用层的"没有删除入口"只是自觉，触发器才是兜底——详见 {@code V8__audit_append_only.sql}。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 导出分页大小：够大以摊薄往返，够小以免一次性把 jsonb 全 detoast 进内存。 */
    private static final int EXPORT_PAGE_SIZE = 500;

    /** 导出列顺序，与 {@link #csvRow} 一一对应。 */
    private static final Object[] HEADERS = {
            "id", "createdAt", "actorId", "actorName", "action",
            "targetType", "targetId", "deptId", "clientIp", "traceId", "detail"};

    private final AuditLogRepository auditLogRepository;
    private final CurrentPrincipal currentPrincipal;

    public AuditService(AuditLogRepository auditLogRepository, CurrentPrincipal currentPrincipal) {
        this.auditLogRepository = auditLogRepository;
        this.currentPrincipal = currentPrincipal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Object targetId, Map<String, Object> detail) {
        try {
            AuditLog entry = new AuditLog();
            AuthPrincipal principal = currentPrincipal.find().orElse(null);
            entry.setActorId(principal == null ? null : principal.userId());
            entry.setActorName(principal == null ? "system" : principal.username());
            entry.setDeptId(principal == null ? null : principal.deptId());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId == null ? null : String.valueOf(targetId));
            if (detail != null && !detail.isEmpty()) {
                entry.setDetail(LogSanitizer.sanitizeAndTruncate(Json.write(detail), 8000));
            }
            currentRequest().ifPresent(request -> {
                entry.setClientIp(clientIp(request));
                entry.setTraceId(request.getHeader("traceparent"));
            });
            auditLogRepository.save(entry);
        } catch (RuntimeException e) {
            // 审计失败不能阻断业务，但必须可见（运维告警项）
            log.error("审计写入失败 action={} target={}:{}", action, targetType, targetId, e);
        }
    }

    public void record(String action, String targetType, Object targetId) {
        record(action, targetType, targetId, Map.of());
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> query(Pageable pageable) {
        return auditLogRepository.findAllByOrderByIdDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> queryByTarget(String targetType, String targetId, Pageable pageable) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId, pageable);
    }

    /**
     * 带部门隔离的审计查询（MGM-04 / MGM-05）。
     *
     * <p>平台管理员看全量；其余角色（包括 AUDITOR）只看得到本部门及下级的记录，
     * 避免审计页面成为跨部门信息泄露通道。
     *
     * @param action 可选动作码过滤，为空则不限
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> query(String action, Pageable pageable, AuthPrincipal principal, DepartmentScope scope) {
        Set<Long> visible = scope.visibleDeptIds(principal);
        if (visible == null) {
            return action == null || action.isBlank()
                    ? auditLogRepository.findAllByOrderByIdDesc(pageable)
                    : auditLogRepository.findByActionOrderByIdDesc(action.trim(), pageable);
        }
        return action == null || action.isBlank()
                ? auditLogRepository.findByDeptIdInOrderByIdDesc(visible, pageable)
                : auditLogRepository.findByActionAndDeptIdInOrderByIdDesc(action.trim(), visible, pageable);
    }

    /**
     * 导出审计为 CSV（MGM-05）。
     *
     * <p>三个刻意的选择：
     * <ol>
     *   <li><b>复用 {@link #query} 逐页取，而不是另写一条流式 SQL。</b>导出与列表页共用同一段
     *       部门隔离逻辑，就不会出现"列表里看不到、导出文件里却全有"这种最典型的越权事故。</li>
     *   <li><b>超上限直接抛错，不截断。</b>被截断的审计文件看起来是完整的。</li>
     *   <li><b>时间用 ISO-8601（UTC）。</b>审计文件常被跨系统比对，本地时间字符串没法可靠解析。</li>
     * </ol>
     *
     * @param maxRows 行数上限，来自 {@code mcp.manager.audit.export-max-rows}
     */
    @Transactional(readOnly = true)
    public AuditDtos.Export exportCsv(String action, AuthPrincipal principal, DepartmentScope scope, int maxRows) {
        StringBuilder out = new StringBuilder(Csv.BOM).append(Csv.row(HEADERS));
        int rows = 0;
        for (int pageIndex = 0; ; pageIndex++) {
            Page<AuditLog> chunk = query(action, PageRequest.of(pageIndex, EXPORT_PAGE_SIZE), principal, scope);
            if (pageIndex == 0 && chunk.getTotalElements() > maxRows) {
                throw new PlatformException(ErrorCode.INVALID_STATE, "导出条数超过上限",
                        Map.of("total", chunk.getTotalElements(),
                                "max", maxRows,
                                "hint", "请缩小筛选范围（例如按动作码过滤）后再导出"));
            }
            for (AuditLog entry : chunk) {
                out.append(csvRow(entry));
                rows++;
            }
            if (!chunk.hasNext()) {
                break;
            }
        }
        return new AuditDtos.Export(out.toString(), rows);
    }

    private static String csvRow(AuditLog entry) {
        return Csv.row(
                entry.getId(),
                entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString(),
                entry.getActorId(),
                entry.getActorName(),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getDeptId(),
                entry.getClientIp(),
                entry.getTraceId(),
                // detail 已脱敏，直接以原始 JSON 字符串入列；它自身含逗号与引号，由 Csv 负责转义
                entry.getDetail());
    }

    private static java.util.Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return java.util.Optional.ofNullable(attributes.getRequest());
        }
        return java.util.Optional.empty();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}