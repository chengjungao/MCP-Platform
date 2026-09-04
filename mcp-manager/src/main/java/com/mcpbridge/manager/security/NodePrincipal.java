package com.mcpbridge.manager.security;

/**
 * 内部通道的节点身份（PUB-02）。
 *
 * <p>Executor 用 {@code X-Executor-Token} 访问 {@code /internal/v1/**}，Manager 按令牌的 sha256
 * 反查所属集群，因此身份里直接带 clusterId：快照接口据此只返回该集群的已发布内容，
 * 天然满足共享集群的多租户隔离（R6）。
 *
 * @param clusterId   令牌归属集群；仅匹配全局引导令牌时为 null
 * @param clusterName 集群名
 * @param bootstrap   是否为全局引导令牌（只允许节点注册，不允许拉取快照）
 */
public record NodePrincipal(Long clusterId, String clusterName, boolean bootstrap) {

    public static NodePrincipal ofBootstrap() {
        return new NodePrincipal(null, null, true);
    }
}