package com.mcpbridge.executor.config;

import com.mcpbridge.executor.state.InMemorySharedState;
import com.mcpbridge.executor.state.RedissonSharedState;
import com.mcpbridge.executor.state.SharedState;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;

/**
 * 共享状态装配（BR-6）。
 *
 * <p>接入形态由 {@code mcp.executor.redis.mode} 选择：{@code single}（单实例/主从/代理）
 * 走 {@link Config#useSingleServer()}，{@code cluster}（Redis Cluster）走
 * {@link Config#useClusterServers()}。两条分支的池子参数刻意保持一致，
 * 让「换形态」只是一次配置变更，不引入第二套性能特征。
 *
 * <p>这里做了一个刻意的取舍：<b>Redis 连不上时不让应用启动失败</b>，而是退化为单节点内存模式。
 * 理由是数据面的正确性只依赖发布快照（它来自 Manager，不来自 Redis），
 * Redis 只影响 OAuth2 令牌的跨节点复用效率。让一个「优化组件」把整个数据面拖死是不划算的。
 *
 * <p>但这个宽容<b>只适用于「连不上」</b>。如果配置本身就不自洽（cluster 模式没给 nodes、
 * single 模式没给 address），那是写错了而不是连不上，必须<b>启动即失败</b>——
 * 否则运维会以为自己配的是三节点集群，实际每个节点各跑各的内存态，而且只在日志里闪一行 WARN。
 *
 * <p>退化必须是响亮的：{@link InMemorySharedState} 构造时打 WARN，
 * 并且 {@code /healthz} 会把 {@code sharedState.mode} 暴露出来，运维巡检一眼可见。
 */
@Configuration
public class SharedStateConfig {

    private static final Logger log = LoggerFactory.getLogger(SharedStateConfig.class);

    /**
     * Executor 对 Redis 的访问模式是「低频小对象」，池子刻意开小：
     * 大池子只会在 Redis 抖动时把节点的文件描述符耗尽。
     */
    private static final int POOL_SIZE = 16;
    private static final int POOL_MIN_IDLE = 2;
    private static final int SUBSCRIPTION_POOL_SIZE = 4;

    /** 集群拓扑刷新周期（ms）：集群扩缩容后靠它发现新分片。与 Redisson 默认值一致，显式写出便于调优。 */
    private static final int CLUSTER_SCAN_INTERVAL_MS = 1000;

    @Bean
    public SharedState sharedState(ExecutorProperties properties) {
        ExecutorProperties.Redis redis = properties.redis();
        if (!redis.enabled()) {
            return new InMemorySharedState("mcp.executor.redis.enabled=false");
        }
        // 配置自洽性校验放在 try 之外：它抛出的 IllegalStateException 是「配置错误」，
        // 必须让启动失败，不能被下面的 catch 吞成一次静默降级。
        List<String> endpoints = endpoints(redis);
        try {
            Config config = new Config();
            apply(config, redis, endpoints);
            RedissonClient client = Redisson.create(config);
            log.info("共享状态已启用 Redisson mode={} endpoints={} keyPrefix={}{}",
                    redis.mode(), String.join(",", endpoints), redis.keyPrefix(),
                    redis.mode() == ExecutorProperties.Redis.Mode.SINGLE ? " database=" + redis.database() : "");
            return new RedissonSharedState(client, redis.keyPrefix());
        } catch (RuntimeException | LinkageError e) {
            log.error("初始化 Redisson 失败，退化为单节点内存模式 mode={} endpoints={}: {}",
                    redis.mode(), String.join(",", endpoints), e.getMessage());
            return new InMemorySharedState("Redis 连接失败: " + e.getMessage());
        }
    }

    /**
     * 按模式装配 Redisson 配置。
     *
     * @param endpoints 已归一化的节点地址（cluster 模式为全部节点，single 模式只有一个）
     */
    private static void apply(Config config, ExecutorProperties.Redis redis, List<String> endpoints) {
        boolean hasPassword = redis.password() != null && !redis.password().isBlank();
        if (redis.mode() == ExecutorProperties.Redis.Mode.CLUSTER) {
            if (redis.database() != 0) {
                // 不静默吞掉：运维显式配了 database 说明他对部署形态有假设，值得提醒一句
                log.warn("Redis Cluster 只有 db0，已忽略 mcp.executor.redis.database={}", redis.database());
            }
            ClusterServersConfig cluster = config.useClusterServers()
                    .addNodeAddress(endpoints.toArray(String[]::new))
                    .setScanInterval(CLUSTER_SCAN_INTERVAL_MS)
                    // 读写与订阅分别建池：订阅连接是长连接，混在主池里会把可用连接数吃掉
                    .setMasterConnectionPoolSize(POOL_SIZE)
                    .setMasterConnectionMinimumIdleSize(POOL_MIN_IDLE)
                    .setSlaveConnectionPoolSize(POOL_SIZE)
                    .setSlaveConnectionMinimumIdleSize(POOL_MIN_IDLE)
                    .setSubscriptionConnectionPoolSize(SUBSCRIPTION_POOL_SIZE);
            if (hasPassword) {
                cluster.setPassword(redis.password());
            }
            return;
        }
        SingleServerConfig single = config.useSingleServer()
                .setAddress(endpoints.get(0))
                .setDatabase(redis.database())
                .setConnectionPoolSize(POOL_SIZE)
                .setConnectionMinimumIdleSize(POOL_MIN_IDLE)
                .setSubscriptionConnectionPoolSize(SUBSCRIPTION_POOL_SIZE);
        if (hasPassword) {
            single.setPassword(redis.password());
        }
    }

    /**
     * 解析并校验接入点，返回归一化后的节点地址。
     *
     * <p>不在这里碰 Redisson：地址文本是否可用是纯粹的配置问题，解析出来才能给出
     * 「到底缺了哪个 key」的错误信息，而不是让 Redisson 在建连阶段抛一个难定位的解析异常。
     *
     * @throws IllegalStateException 配置不自洽（cluster 无 nodes / single 无 address）
     */
    static List<String> endpoints(ExecutorProperties.Redis redis) {
        if (redis.mode() == ExecutorProperties.Redis.Mode.CLUSTER) {
            List<String> nodes = normalizeAll(redis.nodes());
            if (nodes.isEmpty()) {
                // 环境变量注入时最容易踩这个坑：EXECUTOR_REDIS_MODE 设了 cluster，却忘了 EXECUTOR_REDIS_NODES。
                // 常见于「照着集群版的文档改了一半」——所以要给出 hint，而不是只说「不能为空」
                throw new IllegalStateException(
                        "mcp.executor.redis.mode=cluster，但 mcp.executor.redis.nodes 为空。"
                                + "请配置形如 redis://h1:6379,redis://h2:6379,redis://h3:6379 的节点地址；"
                                + "若确实只想连一个单节点，请显式设置 mcp.executor.redis.mode=single");
            }
            return nodes;
        }
        String address = normalizeAddress(redis.address());
        if (address == null) {
            throw new IllegalStateException(
                    "mcp.executor.redis.mode=single，但 mcp.executor.redis.address 为空。"
                            + "请配置形如 redis://localhost:6379 的地址");
        }
        return List.of(address);
    }

    private static List<String> normalizeAll(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        // 过滤空白项：`EXECUTOR_REDIS_NODES=` 这类空值注入会绑出一个只含空串的列表，
        // 那种情况下「nodes 看起来配了」比「完全没配」更危险，必须按没配处理
        return raw.stream().map(SharedStateConfig::normalizeAddress).filter(Objects::nonNull).toList();
    }

    /**
     * 补全协议前缀。Redisson 只认 {@code redis://} 与 {@code rediss://}，
     * 但运维在 K8s Service 名或 ConfigMap 里很容易只写 {@code host:6379}，
     * 那时 Redisson 给出的错误很难定位，不如在这里直接补齐（{@code rediss://} 保持原样不覆盖）。
     */
    static String normalizeAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.contains("://") ? trimmed : "redis://" + trimmed;
    }
}
