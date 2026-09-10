package com.mcpbridge.executor.testkit;

import com.mcpbridge.executor.metrics.ExecutorMetrics;
import com.mcpbridge.executor.snapshot.SnapshotStore;
import com.mcpbridge.executor.upstream.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 构造 {@link ExecutorMetrics} 的测试工厂。
 *
 * <p>存在的理由很实际：几乎所有 executor 组件都带一个 {@code ExecutorMetrics} 参数，
 * 如果每个测试都自己写 {@code new ExecutorMetrics(new SimpleMeterRegistry(), new SnapshotStore(), ...)}，
 * 以后指标类多接一个依赖就要改十几个测试文件。收在这里，只改一处。
 *
 * <p>用 {@link SimpleMeterRegistry} 而不是 mock：它是 Micrometer 官方的内存实现，
 * 断言真实计数比验证「某个 mock 被调用过」更接近生产行为——尤其能顺便发现标签拼错这类问题。
 */
public final class TestMetrics {

    private TestMetrics() {
    }

    /** 只关心「有个可用的指标对象」时用它；拿不到 registry 引用，不能做断言。 */
    public static ExecutorMetrics create(CircuitBreakerRegistry breakers) {
        return new ExecutorMetrics(new SimpleMeterRegistry(), new SnapshotStore(), breakers);
    }

    /** 需要读取计数时用它，自己留着 registry 做断言。 */
    public static ExecutorMetrics create(MeterRegistry registry, SnapshotStore snapshotStore,
                                        CircuitBreakerRegistry breakers) {
        return new ExecutorMetrics(registry, snapshotStore, breakers);
    }
}
