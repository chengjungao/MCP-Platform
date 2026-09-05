package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 跨部门访问申请/授权（按 (server, dept) 唯一，整部门授权）。
 *
 * <p>授予的是<b>只读访问</b>：通过后申请部门成员可查看 Server 详情与 Tool 列表；
 * 写操作与 Auth-B/Auth-D 凭据仍只限所属部门（服务层 requireManage / requireRead 拆分）。
 */
@Entity
@Table(name = "server_access")
public class ServerAccess extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** 申请部门（授权覆盖该部门及其子树，与部门树可见性语义一致）。 */
    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessStatus status = AccessStatus.PENDING;

    @Column(length = 500)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public AccessStatus getStatus() { return status; }
    public void setStatus(AccessStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
