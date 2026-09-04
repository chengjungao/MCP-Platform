package com.mcpbridge.manager.domain;

/**
 * Executor 集群形态（BR-8）。
 *
 * <p>共享集群：平台统一托管的多租户入口，PATH 末段在集群内全局唯一；
 * 私有集群：某部门独立部署的节点组，路径空间独立，只有被授权的 Server 可发布。
 */
public enum ClusterType {
    SHARED, PRIVATE
}