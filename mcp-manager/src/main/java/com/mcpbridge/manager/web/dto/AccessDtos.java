package com.mcpbridge.manager.web.dto;

import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.ServerStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 跨部门访问申请 DTO。
 *
 * <p>设计约束：目录行只暴露最小信息（名称/PATH/归属/状态），不携带端点、Tool 列表或任何配置，
 * 避免申请目录变成跨部门信息泄露面；授权后经 {@code GET /servers/{id}}（脱敏视图）+ tools 读取。
 */
public final class AccessDtos {

    private AccessDtos() {
    }

    /** 我不可访问的 Server 申请目录行。 */
    public record CatalogRow(
            Long id,
            String name,
            String title,
            String pathSegment,
            ServerStatus status,
            Long deptId,
            String deptName,
            /** 我的部门对该 Server 的申请状态：NONE 表示从未申请。 */
            AccessStatus myStatus,
            /** 该申请记录的 id（撤销/查看详情用）；myStatus=NONE 时为 null。 */
            Long accessId,
            Instant createdAt) {
    }

    /** 申请/授权记录视图（「我发起的」与「待我审批」共用）。 */
    public record AccessView(
            Long id,
            Long serverId,
            String serverName,
            String serverPathSegment,
            Long deptId,
            String deptName,
            String reason,
            AccessStatus status,
            Long requestedBy,
            String requesterName,
            Instant requestedAt,
            Long reviewedBy,
            String reviewerName,
            Instant reviewedAt,
            String reviewNote,
            /** 当前账号是否可管理该 Server（资源方视角，用于待审批页展示资源归属）。 */
            boolean manageable) {
    }

    /** 发起申请。 */
    public record ApplyRequest(
            @NotNull Long serverId,
            @Size(max = 500) String reason) {
    }

    /** 审批（通过/驳回/回收共用请求体）。 */
    public record ReviewRequest(
            @Size(max = 500) String note) {
    }
}
