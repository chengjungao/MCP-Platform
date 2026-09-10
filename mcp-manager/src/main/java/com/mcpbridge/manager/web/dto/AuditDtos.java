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

    /**
     * 审计导出结果（MGM-05）。
     *
     * <p>{@code rows} 单独回传而不是让调用方去数换行：导出动作本身要写审计，
     * "导出了多少行"是这个审计条目里最有价值的字段，数错比不记更糟。
     */
    public record Export(String csv, int rows) {
    }
}