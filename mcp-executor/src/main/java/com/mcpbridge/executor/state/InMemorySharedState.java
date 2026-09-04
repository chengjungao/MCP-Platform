package com.mcpbridge.executor.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单节点退化实现：Redis 未启用或连接失败时使用。
 *
 * <p>为什么要有它：数据面的正确性不依赖共享状态，只依赖发布快照。没有 Redis 时，
 * 平台仍然能完整跑通「注册 → 发布 → tools/list → tools/call」这条最小闭环（PRD §5.6），
 * 代价是 OAuth2 令牌每个节点各换一次。把这个退化做成显式的一等公民，
 * 比让应用启动失败要好——本地开发与单元测试也因此不需要拉起 Redis。
 */
public class InMemorySharedState implements SharedState {

    private static final Logger log = LoggerFactory.getLogger(InMemorySharedState.class);

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    public InMemorySharedState(String reason) {
        log.warn("共享状态退化为单节点内存模式（{}）：OAuth2 令牌不再跨节点共享，失效广播不会扩散到其它节点",
                reason);
    }

    @Override
    public Optional<String> getCachedToken(String key) {
        Entry entry = tokens.get(tokenKey(key));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            tokens.remove(tokenKey(key));
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void cacheToken(String key, String token, Duration ttl) {
        if (token == null || token.isBlank()) {
            return;
        }
        Duration effective = ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofMinutes(5) : ttl;
        tokens.put(tokenKey(key), new Entry(token, Instant.now().plus(effective)));
    }

    @Override
    public void evictToken(String key) {
        tokens.remove(tokenKey(key));
    }

    @Override
    public <T> T withLock(String key, Duration wait, Duration lease, Supplier<T> action, Supplier<T> fallback) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            return fallback.get();
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void publish(String channel, String message) {
        listeners.getOrDefault(channel, List.of()).forEach(listener -> {
            try {
                listener.accept(message);
            } catch (RuntimeException e) {
                log.warn("处理本地失效广播出错 channel={}", channel, e);
            }
        });
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public boolean isShared() {
        return false;
    }

    @Override
    public String mode() {
        return "in-memory";
    }

    private static String tokenKey(String key) {
        return TOKEN_KEY_PREFIX + ":" + key;
    }

    private record Entry(String value, Instant expiresAt) {
    }
}