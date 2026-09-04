package com.mcpbridge.executor.state;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的共享状态实现（BR-6）。
 *
 * <p>三件事各用一个 Redisson 原语：{@code RBucket} 存令牌（带 TTL）、{@code RLock} 做刷新互斥、
 * {@code RTopic} 做失效广播。全部用 {@link StringCodec}，这样运维可以直接用 {@code redis-cli}
 * 看到明文键值排查问题——令牌本身是短期凭据，且 Redis 应当部署在内网。
 *
 * <p>任何 Redis 异常都不向上冒泡成 500：读缓存失败当作未命中，写缓存失败只记日志，
 * 锁获取失败走 fallback。<b>共享状态是优化，不是正确性前提</b>。
 */
public class RedissonSharedState implements SharedState, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedissonSharedState.class);

    private final RedissonClient redisson;
    private final String keyPrefix;

    public RedissonSharedState(RedissonClient redisson, String keyPrefix) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "mcp" : keyPrefix.trim();
    }

    /** 供健康检查与测试取用底层客户端。 */
    public RedissonClient redissonClient() {
        return redisson;
    }

    /**
     * 容器关闭时释放 Redisson 的连接池与定时任务。
     * 必须显式做：本类是以 {@link SharedState} 接口类型注册的 Bean，
     * 不实现 {@code DisposableBean} 的话 Spring 不会知道要关这个客户端。
     */
    @Override
    public void destroy() {
        try {
            if (!redisson.isShutdown()) {
                redisson.shutdown();
            }
        } catch (RuntimeException e) {
            log.warn("关闭 Redisson 客户端异常：{}", e.getMessage());
        }
    }

    @Override
    public Optional<String> getCachedToken(String key) {
        try {
            return Optional.ofNullable(bucket(tokenKey(key)).get());
        } catch (RuntimeException e) {
            log.warn("读取共享令牌缓存失败，按未命中处理 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void cacheToken(String key, String token, Duration ttl) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Duration effective = ttl == null || ttl.isNegative() || ttl.isZero()
                    ? Duration.ofMinutes(5) : ttl;
            bucket(tokenKey(key)).set(token, effective);
        } catch (RuntimeException e) {
            log.warn("写入共享令牌缓存失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictToken(String key) {
        try {
            bucket(tokenKey(key)).delete();
        } catch (RuntimeException e) {
            log.warn("清除共享令牌缓存失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public <T> T withLock(String key, Duration wait, Duration lease, Supplier<T> action, Supplier<T> fallback) {
        RLock lock = redisson.getLock(keyPrefix + ":lock:" + key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待刷新锁被中断 key={}", key);
        } catch (RuntimeException e) {
            log.warn("获取刷新锁失败，走本地兜底 key={}: {}", key, e.getMessage());
        }
        if (!acquired) {
            return fallback.get();
        }
        try {
            return action.get();
        } finally {
            // 只解自己持有的锁：lease 到期后锁可能已被别的节点拿走，无脑 unlock 会抛 IllegalMonitorState
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (RuntimeException e) {
                    log.warn("释放刷新锁失败（将由 lease 到期自动释放）key={}: {}", key, e.getMessage());
                }
            }
        }
    }

    @Override
    public void publish(String channel, String message) {
        try {
            redisson.getTopic(keyPrefix + ":" + channel, StringCodec.INSTANCE).publish(message);
        } catch (RuntimeException e) {
            log.warn("发布失效广播失败 channel={}: {}", channel, e.getMessage());
        }
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        try {
            RTopic topic = redisson.getTopic(keyPrefix + ":" + channel, StringCodec.INSTANCE);
            topic.addListener(String.class, (name, message) -> {
                try {
                    listener.accept(message);
                } catch (RuntimeException e) {
                    log.warn("处理失效广播出错 channel={} message={}", channel, message, e);
                }
            });
            log.info("已订阅共享失效广播 channel={}", keyPrefix + ":" + channel);
        } catch (RuntimeException e) {
            log.warn("订阅失效广播失败，本节点将依赖 TTL 过期而非主动失效 channel={}: {}", channel, e.getMessage());
        }
    }

    @Override
    public boolean isShared() {
        return true;
    }

    @Override
    public String mode() {
        return "redisson";
    }

    private RBucket<String> bucket(String key) {
        return redisson.getBucket(key, StringCodec.INSTANCE);
    }

    private String tokenKey(String key) {
        return keyPrefix + ":" + TOKEN_KEY_PREFIX + ":" + key;
    }
}