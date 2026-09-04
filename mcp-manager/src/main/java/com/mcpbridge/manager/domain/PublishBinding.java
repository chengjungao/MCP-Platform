package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Server 在某集群上的发布记录（版本化，支持回滚，PUB-03 / PUB-04）。
 *
 * <p>{@code snapshot} 保存发布当时的生效模型（base ⊕ overlay 的固化结果），
 * 因此回滚只是把 {@code current} 指回历史行，不需要重新解析文档。
 */
@Entity
@Table(name = "publish_binding")
public class PublishBinding extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    /** 发布版本号：同一 (server, cluster) 内单调递增，从 1 开始。 */
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BindingState state = BindingState.DRAFT;

    /** 当前生效版本标记：同一 (server, cluster) 至多一行为 true。 */
    @Column(name = "is_current", nullable = false)
    private boolean current = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb")
    private String snapshot;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "offlined_at")
    private Instant offlinedAt;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public Long getClusterId() { return clusterId; }
    public void setClusterId(Long clusterId) { this.clusterId = clusterId; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public BindingState getState() { return state; }
    public void setState(BindingState state) { this.state = state; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getOfflinedAt() { return offlinedAt; }
    public void setOfflinedAt(Instant offlinedAt) { this.offlinedAt = offlinedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}