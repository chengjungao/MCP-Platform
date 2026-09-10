package com.mcpbridge.executor.upstream;

import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 REST 服务（serverId:serviceId）维度的熔断器（EXE-03）。
 *
 * <p><b>刻意不做跨节点共享</b>，尽管我们已经有了 Redis。理由是它度量的不是「上游挂了」，
 * 而是「<i>本节点到某上游</i>的链路挂了」：Executor-2 到上游专线抖动时，
 * 让 Executor-1 也停止服务只会把局部故障放大成全集群故障。
 * 每个节点独立熔断，负载自然会被上游网关导到健康节点上。
 *
 * <p><b>多上游隔离</b>：一个 Server 挂多个 REST 服务后，key 改为 {@code serverId:serviceId}
 * 复合字符串，避免服务 A 连续失败误熔断服务 B（单上游时隐蔽、多上游暴露的缺陷修复）。
 *
 * <p>用 {@code synchronized} 而不是 CAS 循环：单个上游的熔断判定串在一把锁上，
 * 临界区只有几条赋值语句，而它的调用频率上限就是该上游的 QPS——
 * 远达不到需要无锁的程度，而 CAS 版本的状态机正确性要难验证得多。
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    /**
     * 是否放行本次调用。
     *
     * <p>OPEN 到期后转 HALF_OPEN 并放行探测；HALF_OPEN 下只放行 {@code halfOpenProbes} 个，
     * 超出的请求继续被拒——不限制探测数量的话，半开等于没熔断。
     */
    public boolean allow(String breakerKey, UpstreamSnapshot.CircuitBreaker config) {
        return breaker(breakerKey).allow(config);
    }

    public void onSuccess(String breakerKey) {
        Breaker breaker = breakers.get(breakerKey);
        if (breaker != null) {
            breaker.success(breakerKey);
        }
    }

    /**
     * 上报一次失败。
     *
     * @return true 表示本次失败<b>把熔断从非 OPEN 推到了 OPEN</b>。
     *         调用方据此记一次 trip 指标——把「刚打开」和「一直是打开的」分开，
     *         否则一个持续挂着的上游会每分钟刷出成百上千次「熔断打开」，告警会被淹没。
     */
    public boolean onFailure(String breakerKey, UpstreamSnapshot.CircuitBreaker config) {
        return breaker(breakerKey).failure(breakerKey, config);
    }

    /** 运维视角的状态快照（{@code /executor/status} 用）。 */
    public Map<String, State> states() {
        Map<String, State> states = new LinkedHashMap<>();
        breakers.forEach((key, breaker) -> states.put(key, breaker.state()));
        return states;
    }

    private Breaker breaker(String breakerKey) {
        return breakers.computeIfAbsent(breakerKey, id -> new Breaker());
    }

    /** 复合 key 生成：{@code serverId:serviceId}。 */
    public static String key(long serverId, String serviceId) {
        return serverId + ":" + (serviceId == null ? "default" : serviceId);
    }

    private static final class Breaker {

        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openedAtMillis;
        private int probes;

        synchronized boolean allow(UpstreamSnapshot.CircuitBreaker config) {
            switch (state) {
                case CLOSED -> {
                    return true;
                }
                case OPEN -> {
                    if (System.currentTimeMillis() - openedAtMillis >= config.openMs()) {
                        state = State.HALF_OPEN;
                        probes = 0;
                        log.info("熔断转入 HALF_OPEN，开始放行探测请求");
                        return true;
                    }
                    return false;
                }
                case HALF_OPEN -> {
                    return probes++ < Math.max(1, config.halfOpenProbes());
                }
            }
            return true;
        }

        synchronized void success(String breakerKey) {
            consecutiveFailures = 0;
            if (state != State.CLOSED) {
                log.info("上游恢复正常，熔断已关闭 key={}", breakerKey);
                state = State.CLOSED;
                probes = 0;
            }
        }

        synchronized boolean failure(String breakerKey, UpstreamSnapshot.CircuitBreaker config) {
            State before = state;
            if (state == State.HALF_OPEN) {
                trip(breakerKey, config);
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= Math.max(1, config.failureThreshold())) {
                    trip(breakerKey, config);
                }
            }
            // 只在「非 OPEN → OPEN」这一刻算 trip；状态没变（含本来就是 OPEN）都不算
            return before != State.OPEN && state == State.OPEN;
        }

        synchronized State state() {
            return state;
        }

        private void trip(String breakerKey, UpstreamSnapshot.CircuitBreaker config) {
            state = State.OPEN;
            openedAtMillis = System.currentTimeMillis();
            consecutiveFailures = 0;
            probes = 0;
            log.warn("上游熔断打开 key={} 保持 {}ms（半开探测 {} 个）",
                    breakerKey, config.openMs(), config.halfOpenProbes());
        }
    }
}
