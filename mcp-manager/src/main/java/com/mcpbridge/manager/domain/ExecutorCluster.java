package com.mcpbridge.manager.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Executor 集群（PUB-01 / BR-8）。
 *
 * <p>{@code revision} 是集群级快照版本号：任何发布 / 下线 / 配置变更都会递增，
 * Executor 轮询时用它做增量判断（EXE-01）。
 */
@Entity
@Table(name = "executor_cluster")
public class ExecutorCluster extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClusterType type = ClusterType.SHARED;

    /** 对外入口，如 https://mcp.example.com。 */
    @Column(nullable = false, length = 255)
    private String entrypoint;

    /** 平台保留前缀，用户只能自定义末段（BR-3）。 */
    @Column(name = "path_prefix", nullable = false, length = 32)
    private String pathPrefix = "mcp";

    /** 集群归属部门（私有集群即租户）。 */
    @Column(name = "owner_dept_id")
    private Long ownerDeptId;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 可发布范围与配额（jsonb）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String scopes;

    /** 被授权可发布到本集群的部门集合（集群授权，§5.4）。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cluster_grant", joinColumns = @JoinColumn(name = "cluster_id"))
    @Column(name = "dept_id", nullable = false)
    private Set<Long> grantedDeptIds = new LinkedHashSet<>();

    /** 节点注册令牌的 sha256（PUB-02），不存明文。 */
    @Column(name = "node_token_hash", length = 64)
    private String nodeTokenHash;

    @Column(name = "revision", nullable = false)
    private long revision = 0L;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ClusterType getType() { return type; }
    public void setType(ClusterType type) { this.type = type; }
    public String getEntrypoint() { return entrypoint; }
    public void setEntrypoint(String entrypoint) { this.entrypoint = entrypoint; }
    public String getPathPrefix() { return pathPrefix; }
    public void setPathPrefix(String pathPrefix) { this.pathPrefix = pathPrefix; }
    public Long getOwnerDeptId() { return ownerDeptId; }
    public void setOwnerDeptId(Long ownerDeptId) { this.ownerDeptId = ownerDeptId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Set<Long> getGrantedDeptIds() { return grantedDeptIds; }
    public void setGrantedDeptIds(Set<Long> grantedDeptIds) { this.grantedDeptIds = grantedDeptIds; }
    public String getNodeTokenHash() { return nodeTokenHash; }
    public void setNodeTokenHash(String nodeTokenHash) { this.nodeTokenHash = nodeTokenHash; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    /** 部门是否有权发布到本集群：平台管理员之外的授权判定入口（US-05）。 */
    public boolean allowsDepartment(Long deptId) {
        return deptId != null && grantedDeptIds.contains(deptId);
    }
}