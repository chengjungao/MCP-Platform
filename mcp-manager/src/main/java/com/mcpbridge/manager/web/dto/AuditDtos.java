package com.mcpbridge.manager.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 审计日志查询 DTO（MGM-05 / US-10）。
 *
 * <p>detail 在落库前已经过 {@code LogSanitizer} 脱敏，因此可以直接回传给前端展示。
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditView(
            Long id,
            Long actorId,
            String actorName,
            String action,
            String targetType,
            String targetId,
            Long deptId,
            Map<String, Object> detail,
            String traceId,
            String clientIp,
            Instant createdAt) {
    }
}