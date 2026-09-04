package com.mcpbridge.manager.web.dto;

import com.mcpbridge.manager.domain.DocSource;
import com.mcpbridge.manager.domain.RegistrationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 注册与解析 DTO（REG-01/02/03）。
 */
public final class RegistrationDtos {

    private RegistrationDtos() {
    }

    /** URL 拉取注册。文件上传走 multipart，参数见控制器。 */
    public record CreateByUrlRequest(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 512) String url,
            /** 为空则归属创建者所在部门。 */
            Long deptId,
            /** 可选：自定义对外 PATH 末段；为空则由服务名推导。 */
            @Size(max = 64) String pathSegment) {
    }

    /**
     * 结构化诊断（REG-01：非法文档给出错误位置与缺失字段，不白屏）。
     *
     * @param level   ERROR / WARN
     * @param pointer JSON Pointer 形式的错误位置，如 {@code /paths/~1users/get}
     * @param field   涉及的字段名
     * @param message 说明
     */
    public record Diagnostic(String level, String pointer, String field, String message) {
    }

    public record View(
            Long id,
            String name,
            Long deptId,
            String deptName,
            DocSource docSource,
            String sourceRef,
            String swaggerVersion,
            int docVersion,
            RegistrationStatus status,
            int operationCount,
            List<Diagnostic> diagnostics,
            /** 解析成功后 1:1 生成的 MCP Server id（BR-1）。 */
            Long serverId,
            String rawDocSha256,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 重新解析（re-import）后的 diff 报告（REG-03 / US-12）。
     *
     * @param newDocVersion      升级后的文档版本
     * @param added              新增接口（进入「待确认」列表）
     * @param removed            被删接口（自动标记停用）
     * @param changed            签名发生变化的接口
     * @param suspendedOverlays  锚点失效、进入挂起区的覆盖项（不静默丢弃）
     * @param preservedOverlays  仍然有效的既有覆盖
     */
    public record DiffReport(
            int newDocVersion,
            List<String> added,
            List<String> removed,
            List<String> changed,
            List<String> suspendedOverlays,
            List<String> preservedOverlays) {
    }
}