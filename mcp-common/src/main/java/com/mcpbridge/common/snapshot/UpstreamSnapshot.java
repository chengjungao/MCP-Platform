package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 上游 REST 调用策略（EXE-03 / EXE-04）。
 *
 * @param baseUrls           多实例地址，负载均衡在此列表上进行（EXE-04）
 * @param lbStrategy         负载均衡策略：ROUND_ROBIN / WEIGHTED
 * @param weights            与 baseUrls 等长的权重（WEIGHTED 时使用）
 * @param connectTimeoutMs   连接超时
 * @param readTimeoutMs      读超时（默认 30s，可配）
 * @param retries            重试次数（默认 1，仅幂等方法生效）
 * @param retryOnStatus      触发重试的上游状态码，默认 502/503/504
 * @param circuitBreaker     熔断配置（连续失败阈值可配）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpstreamSnapshot(
        List<String> baseUrls,
        LbStrategy lbStrategy,
        List<Integer> weights,
        long connectTimeoutMs,
        long readTimeoutMs,
        int retries,
        List<Integer> retryOnStatus,
        CircuitBreaker circuitBreaker) {

    public enum LbStrategy {
        ROUND_ROBIN, WEIGHTED
    }

    /**
     * @param failureThreshold 连续失败多少次后打开熔断
     * @param openMs           熔断保持时长
     * @param halfOpenProbes   半开状态放行的探测请求数
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CircuitBreaker(int failureThreshold, long openMs, int halfOpenProbes) {

        public static CircuitBreaker defaults() {
            return new CircuitBreaker(5, 30_000L, 2);
        }
    }

    /** 产品默认值：超时 30s、幂等方法重试 1 次、连续 5 次失败熔断 30s。 */
    public static UpstreamSnapshot defaults(List<String> baseUrls) {
        return new UpstreamSnapshot(
                baseUrls == null ? List.of() : baseUrls,
                LbStrategy.ROUND_ROBIN,
                List.of(),
                3_000L,
                30_000L,
                1,
                List.of(502, 503, 504),
                CircuitBreaker.defaults());
    }
}
