package com.mcpbridge.executor.config;

import com.mcpbridge.executor.state.InMemorySharedState;
import com.mcpbridge.executor.state.RedissonSharedState;
import com.mcpbridge.executor.state.SharedState;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享状态装配（BR-6）。
 *
 * <p>这里做了一个刻意的取舍：<b>Redis 连不上时不让应用启动失败</b>，而是退化为单节点内存模式。
 * 理由是数据面的正确性只依赖发布快照（它来自 Manager，不来自 Redis），
 * Redis 只影响 OAuth2 令牌的跨节点复用效率。让一个「优化组件」把整个数据面拖死是不划算的。
 *
 * <p>退化必须是响亮的：{@link InMemorySharedState} 构造时打 WARN，
 * 并且 {@code /healthz} 会把 {@code sharedState.mode} 暴露出来，运维巡检一眼可见。
 */
@Configuration
public class SharedStateConfig {

    private static final Logger log = LoggerFactory.getLogger(SharedStateConfig.class);

    @Bean
    public SharedState sharedState(ExecutorProperties properties) {
        ExecutorProperties.Redis redis = properties.redis();
        if (!redis.enabled()) {
            return new InMemorySharedState("mcp.executor.redis.enabled=false");
        }
        try {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(redis.address())
                    .setDatabase(redis.database())
                    // 连接池不要太大：Executor 对 Redis 的访问模式是「低频小对象」，
                    // 大池子只会在 Redis 抖动时把节点的文件描述符耗尽
                    .setConnectionPoolSize(16)
                    .setConnectionMinimumIdleSize(2)
                    .setSubscriptionConnectionPoolSize(4);
            if (redis.password() != null && !redis.password().isBlank()) {
                config.useSingleServer().setPassword(redis.password());
            }
            RedissonClient client = Redisson.create(config);
            log.info("共享状态已启用 Redisson address={} database={} keyPrefix={}",
                    redis.address(), redis.database(), redis.keyPrefix());
            return new RedissonSharedState(client, redis.keyPrefix());
        } catch (RuntimeException | LinkageError e) {
            log.error("初始化 Redisson 失败，退化为单节点内存模式 address={}: {}", redis.address(), e.getMessage());
            return new InMemorySharedState("Redis 连接失败: " + e.getMessage());
        }
    }
}