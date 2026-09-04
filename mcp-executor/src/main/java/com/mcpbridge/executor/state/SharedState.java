package com.mcpbridge.executor.state;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 跨节点共享状态（BR-6）。
 *
 * <p>Executor 是无状态的（R5），但有三类状态<b>必须</b>跨节点一致，否则会出现
 * 「同一个客户端在不同节点上表现不同」这种最难排查的问题：
 * <ol>
 *   <li><b>上游令牌缓存</b>：OAuth2 client_credentials 换来的 access_token。
 *       每个节点各换一次会把上游的令牌端点打爆，也会让令牌提前失效。</li>
 *   <li><b>刷新锁</b>：令牌过期瞬间，N 个节点同时收到请求，只应有 1 个去刷新，
 *       其余等结果——这就是经典的缓存击穿，必须用分布式锁而不是本地锁。</li>
 *   <li><b>失效广播</b>：上游返回 401 时，所有节点都要立即丢弃缓存的令牌，
 *       否则某些节点会继续用废令牌重试，把失败放大。</li>
 * </ol>
 *
 * <p>生产实现是 {@link RedissonSharedState}；Redis 不可用时退化为 {@link InMemorySharedState}，
 * 功能可用但不再跨节点共享——退化是显式的（{@link #isShared()} 返回 false 并打日志）。
 */
public interface SharedState {

    /** 缓存键的命名空间，便于多平台共用一个 Redis。 */
    String TOKEN_KEY_PREFIX = "token";

    /** 令牌失效广播的频道名。 */
    String TOKEN_INVALIDATION_TOPIC = "token-invalidation";

    Optional<String> getCachedToken(String key);

    void cacheToken(String key, String token, Duration ttl);

    void evictToken(String key);

    /**
     * 在分布式锁保护下执行动作。
     *
     * @param key      锁键（通常与缓存键同源）
     * @param action   拿到锁后执行的动作，例如「向上游换取新令牌并写缓存」
     * @param fallback 等不到锁时的兜底动作，例如「用本地已有令牌再试一次」；
     *                 不能抛异常，否则一次上游抖动会让所有等待者一起失败
     */
    <T> T withLock(String key, Duration wait, Duration lease, Supplier<T> action, Supplier<T> fallback);

    void publish(String channel, String message);

    void subscribe(String channel, Consumer<String> listener);

    /** true 表示状态真的跨节点共享；false 表示已退化为单节点内存模式。 */
    boolean isShared();

    /** 实现名，写进 /healthz 与启动日志，运维一眼能看出当前跑在哪种模式下。 */
    String mode();
}