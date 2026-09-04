package com.mcpbridge.manager.service;

import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.manager.domain.AuditLog;
import com.mcpbridge.manager.repository.AuditLogRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.security.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

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