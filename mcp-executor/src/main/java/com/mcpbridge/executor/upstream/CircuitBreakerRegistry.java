package com.mcpbridge.executor.upstream;

import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Server 维度的熔断器（EXE-03）。
 *
 * <p><b>刻意不做跨节点共享</b>，尽管我们已经有了 Redis。理由是它度量的不是「上游挂了」，
 * 而是「<i>本节点到上游</i>的链路挂了」：Executor-2 到上游专线抖动时，
 * 让 Executor-1 也停止服务只会把局部故障放大成全集群故障。
 * 每个节点独立熔断，负载自然会被上游网关导到健康节点上。
 *
 * <p>用 {@code synchronized} 而不是 CAS 循环：单个 Server 的熔断判定串在一把锁上，
 * 临界区只有几条赋值语句，而它的调用频率上限就是该 Server 的 QPS——
 * 远达不到需要无锁的程度，而 CAS 版本的状态机正确性要难验证得多。
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final ConcurrentHashMap<Long, Breaker> breakers = new ConcurrentHashMap<>();

    /**
     * 是否放行本次调用。
     *
     * <p>OPEN 到期后转 HALF_OPEN 并放行探测；HALF_OPEN 下只放行 {@code halfOpenProbes} 个，
     * 超出的请求继续被拒——不限制探测数量的话，半开等于没熔断。
     */
    public boolean allow(long serverId, UpstreamSnapshot.CircuitBreaker config) {
        return breaker(serverId).allow(config);
    }

    public void onSuccess(long serverId) {
        Breaker breaker = breakers.get(serverId);
        if (breaker != null) {
            breaker.success(serverId);
        }
    }

    public void onFailure(long serverId, UpstreamSnapshot.CircuitBreaker config) {
        breaker(serverId).failure(serverId, config);
    }

    /** 运维视角的状态快照（{@code /executor/status} 用）。 */
    public Map<Long, State> states() {
        Map<Long, State> states = new LinkedHashMap<>();
        breakers.forEach((id, breaker) -> states.put(id, breaker.state()));
        return states;
    }

    private Breaker breaker(long serverId) {
        return breakers.computeIfAbsent(serverId, id -> new Breaker());
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

        synchronized void success(long serverId) {
            consecutiveFailures = 0;
            if (state != State.CLOSED) {
                log.info("上游恢复正常，熔断已关闭 serverId={}", serverId);
                state = State.CLOSED;
                probes = 0;
            }
        }

        synchronized void failure(long serverId, UpstreamSnapshot.CircuitBreaker config) {
            if (state == State.HALF_OPEN) {
                trip(serverId, config);
                return;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= Math.max(1, config.failureThreshold())) {
                trip(serverId, config);
            }
        }

        synchronized State state() {
            return state;
        }

        private void trip(long serverId, UpstreamSnapshot.CircuitBreaker config) {
            state = State.OPEN;
            openedAtMillis = System.currentTimeMillis();
            consecutiveFailures = 0;
            probes = 0;
            log.warn("上游熔断打开 serverId={} 保持 {}ms（半开探测 {} 个）",
                    serverId, config.openMs(), config.halfOpenProbes());
        }
    }
}