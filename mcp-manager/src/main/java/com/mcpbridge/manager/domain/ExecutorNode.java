package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Executor 节点（PUB-02）。节点必须无状态可丢：任意节点可服务任意请求（BR-6 附加推论）。
 */
@Entity
@Table(name = "executor_node",
        uniqueConstraints = @UniqueConstraint(name = "uk_node_cluster_key", columnNames = {"cluster_id", "node_key"}))
public class ExecutorNode extends BaseEntity {

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    /** 节点自报的稳定标识（如 hostname 或配置项 mcp.executor.node-id）。 */
    @Column(name = "node_key", nullable = false, length = 128)
    private String nodeKey;

    @Column(length = 128)
    private String host;

    private Integer port;

    /** 节点版本号（发布可观测，PUB-05）。 */
    @Column(length = 32)
    private String version;

    /** 节点声明实现的协议版本，必须为 2026-07-28（决策 D1）。 */
    @Column(name = "protocol_version", length = 16)
    private String protocolVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeStatus status = NodeStatus.OFFLINE;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** 心跳上报的负载信息：并发数、已加载 server/tool 数、快照 revision 等。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "load_info", columnDefinition = "jsonb")
    private String loadInfo;

    public Long getClusterId() { return clusterId; }
    public void setClusterId(Long clusterId) { this.clusterId = clusterId; }
    public String getNodeKey() { return nodeKey; }
    public void setNodeKey(String nodeKey) { this.nodeKey = nodeKey; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public String getLoadInfo() { return loadInfo; }
    public void setLoadInfo(String loadInfo) { this.loadInfo = loadInfo; }
}