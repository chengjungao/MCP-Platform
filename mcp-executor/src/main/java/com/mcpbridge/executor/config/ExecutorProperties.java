package com.mcpbridge.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 数据面配置。
 *
 * <p>设计取向：<b>能自动推导的就不要求填</b>。nodeKey 留空则用「主机名 + 端口」，
 * clusterId 留空则按 clusterName 注册时由 Manager 分配，因此最小配置只需要
 * {@code manager.base-url} 与 {@code manager.token} 两项。
 *
 * @param manager  与 Manager 内部通道的对接参数
 * @param node     本节点自述信息（注册与心跳上报）
 * @param redis    Redisson 共享状态（BR-6）
 * @param upstream 上游调用的兜底默认值（快照里有配置时以快照为准）
 * @param protocol 协议与路由参数
 */
@ConfigurationProperties(prefix = "mcp.executor")
public record ExecutorProperties(
        @DefaultValue Manager manager,
        @DefaultValue Node node,
        @DefaultValue Redis redis,
        @DefaultValue Upstream upstream,
        @DefaultValue Protocol protocol) {

    /**
     * @param baseUrl        Manager 地址
     * @param token          节点接入令牌（集群令牌或引导令牌），只走 {@code X-Executor-Token} 头
     * @param clusterId      已知的集群 id；留空则按 clusterName 注册
     * @param clusterName    引导注册时使用的集群名
     * @param pollInterval   快照轮询周期（EXE-01：≤30s 感知发布）
     * @param retryBackoff   拉取失败后的重试间隔
     */
    public record Manager(
            @DefaultValue("http://localhost:8080") String baseUrl,
            @DefaultValue("dev-executor-bootstrap-token") String token,
            Long clusterId,
            @DefaultValue("default") String clusterName,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout,
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("5s") Duration retryBackoff) {
    }

    /**
     * @param key  节点标识；留空则用「主机名:端口」，容器环境下即容器 id，天然唯一
     * @param host 上报给 Manager 的可达地址（用于运维定位，不参与流量转发）
     */
    public record Node(
            @DefaultValue("") String key,
            @DefaultValue("127.0.0.1") String host,
            @DefaultValue("9090") int port,
            @DefaultValue("0.1.0") String version) {
    }

    /**
     * Redisson 共享状态（BR-6）。
     *
     * <p>{@code enabled=false} 或 Redis 不可达时，平台退化为单节点内存模式：功能可用，
     * 但令牌缓存不再跨节点共享（每个节点各自换取一次令牌），失效广播也不会扩散。
     * 这个退化是显式记录在启动日志里的，不会静默发生。
     *
     * @param tokenTtl   上游令牌在共享缓存里的存活时间上限（实际以令牌自身的 expires_in 为准，取较小值）
     * @param lockWait   刷新锁的最长等待时间：等不到就用本地已有令牌兜底，避免把上游打挂
     * @param lockLease  刷新锁的持有上限：持锁节点崩溃后自动释放
     */
    public record Redis(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("redis://localhost:6379") String address,
            String password,
            @DefaultValue("0") int database,
            @DefaultValue("mcp") String keyPrefix,
            @DefaultValue("30m") Duration tokenTtl,
            @DefaultValue("5s") Duration lockWait,
            @DefaultValue("30s") Duration lockLease) {
    }

    public record Upstream(
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            /** 上游响应体在内存中的上限，超过即截断并标注，避免大响应打爆节点（EXE-04）。 */
            @DefaultValue("1048576") int maxResponseBytes) {
    }

    /**
     * @param pathPrefix        对外 PATH 的平台保留前缀（BR-3），必须与集群配置一致
     * @param defaultListTtlMs  Server 未配置 listTtlMs 时的兜底值（SEP-2549）
     */
    public record Protocol(
            @DefaultValue("mcp") String pathPrefix,
            @DefaultValue("30000") int defaultListTtlMs) {
    }
}