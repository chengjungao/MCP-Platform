package com.mcpbridge.executor.upstream;

import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断状态机（EXE-03）。
 *
 * <p>熔断是「用局部失败换整体可用」的交易，交易做错的代价是双向的：
 * 阈值不生效等于没有保护，半开探测不限量等于熔断形同虚设，
 * 而失败计数不随成功清零则会让偶发抖动积累成永久熔断。三条都钉死。
 */
class CircuitBreakerRegistryTest {

    private static final long SERVER_ID = 1L;

    private final CircuitBreakerRegistry registry = new CircuitBreakerRegistry();

    @Test
    @DisplayName("初始状态为 CLOSED，请求一律放行")
    void allowsWhenClosed() {
        assertThat(registry.allow(SERVER_ID, cfg(5, 30_000L, 2))).isTrue();
        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.CLOSED);
    }

    @Test
    @DisplayName("连续失败达到阈值后转 OPEN 并拒绝调用")
    void tripsAfterConsecutiveFailures() {
        UpstreamSnapshot.CircuitBreaker config = cfg(3, 30_000L, 2);

        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isTrue();

        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();
        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.OPEN);
    }

    @Test
    @DisplayName("一次成功清零失败计数：偶发抖动不该积累成永久熔断")
    void successResetsFailureCounter() {
        UpstreamSnapshot.CircuitBreaker config = cfg(3, 30_000L, 2);

        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        registry.onSuccess(SERVER_ID);
        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);

        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.CLOSED);

        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();
    }

    @Test
    @DisplayName("OPEN 超过保持时间后转 HALF_OPEN 并放行探测")
    void transitionsToHalfOpenAfterOpenWindow() throws InterruptedException {
        UpstreamSnapshot.CircuitBreaker config = cfg(1, 40L, 1);

        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();

        Thread.sleep(150L);

        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.HALF_OPEN);
    }

    @Test
    @DisplayName("HALF_OPEN 只放行 halfOpenProbes 个探测请求，超出的继续被拒")
    void limitsHalfOpenProbes() throws InterruptedException {
        UpstreamSnapshot.CircuitBreaker config = cfg(1, 40L, 2);
        tripAndWait(config);

        // 转半开的那次 allow 本身不消耗探测配额，因此配额 2 会得到 3 个 true
        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        assertThat(registry.allow(SERVER_ID, config)).isFalse();
    }

    @Test
    @DisplayName("HALF_OPEN 下探测成功即关闭熔断")
    void closesOnHalfOpenSuccess() throws InterruptedException {
        UpstreamSnapshot.CircuitBreaker config = cfg(1, 40L, 1);
        tripAndWait(config);

        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        registry.onSuccess(SERVER_ID);

        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.CLOSED);
        assertThat(registry.allow(SERVER_ID, config)).isTrue();
    }

    @Test
    @DisplayName("HALF_OPEN 下探测失败立即重新熔断，不再等阈值")
    void reopensOnHalfOpenFailure() throws InterruptedException {
        UpstreamSnapshot.CircuitBreaker config = cfg(5, 40L, 1);
        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();

        Thread.sleep(150L);
        assertThat(registry.allow(SERVER_ID, config)).isTrue();

        // 阈值是 5，但半开状态下一次失败就必须立刻断开
        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();
        assertThat(registry.states()).containsEntry(SERVER_ID, CircuitBreakerRegistry.State.OPEN);
    }

    @Test
    @DisplayName("各 Server 的熔断彼此独立：一个上游挂了不牵连其它端点")
    void tracksServersIndependently() {
        UpstreamSnapshot.CircuitBreaker config = cfg(1, 30_000L, 1);

        registry.onFailure(SERVER_ID, config);

        assertThat(registry.allow(SERVER_ID, config)).isFalse();
        assertThat(registry.allow(2L, config)).isTrue();
        assertThat(registry.states())
                .containsEntry(SERVER_ID, CircuitBreakerRegistry.State.OPEN)
                .containsEntry(2L, CircuitBreakerRegistry.State.CLOSED);
    }

    @Test
    @DisplayName("未发生任何调用时状态视图为空，不污染 /executor/status")
    void statesIsEmptyBeforeAnyInteraction() {
        assertThat(registry.states()).isEmpty();
    }

    // ------------------------------------------------------------------ 夹具

    /** 把熔断打到 OPEN 并等过保持时间，使下一次 allow 必然进入 HALF_OPEN。 */
    private void tripAndWait(UpstreamSnapshot.CircuitBreaker config) throws InterruptedException {
        assertThat(registry.allow(SERVER_ID, config)).isTrue();
        registry.onFailure(SERVER_ID, config);
        assertThat(registry.allow(SERVER_ID, config)).isFalse();
        Thread.sleep(150L);
    }

    private static UpstreamSnapshot.CircuitBreaker cfg(int failureThreshold, long openMs, int halfOpenProbes) {
        return new UpstreamSnapshot.CircuitBreaker(failureThreshold, openMs, halfOpenProbes);
    }
}