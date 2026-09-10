package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WEIGHTED 权重校验（EXE-04）。
 *
 * <p>Executor 发现「权重数量 ≠ 地址数量」时会退回轮询并打 WARN——那是数据面对脏配置的兜底，
 * 不是常态。如果控制面能写进非法权重，这个兜底就变成常态，而运维看到的只是「保存成功」，
 * 于是以为自己配的是 7:3 分流，实际是 5:5。所以校验必须在配置期拦住。
 *
 * <p>本节锁住四类边界：WEIGHTED 缺权重、长度不匹配、负数、总和为 0；
 * 以及一个容易被顺手改坏的行为——<b>非 WEIGHTED 时允许预置合法权重</b>（为将来切策略留路）。
 */
class UpstreamWeightsValidationTest {

    private static final UpstreamSnapshot.LbStrategy WEIGHTED = UpstreamSnapshot.LbStrategy.WEIGHTED;
    private static final UpstreamSnapshot.LbStrategy ROUND_ROBIN = UpstreamSnapshot.LbStrategy.ROUND_ROBIN;

    @Test
    @DisplayName("ROUND_ROBIN + 不给权重：正常，返回空列表（不是 null，避免下游 NPE）")
    void roundRobinWithoutWeightsIsFine() {
        assertThat(ServerService.requireWeights(ROUND_ROBIN, null, 3)).isEmpty();
    }

    @Test
    @DisplayName("WEIGHTED + 不给权重：拒绝，并指出该改哪个字段")
    void weightedWithoutWeightsIsRejected() {
        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, null, 2))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("WEIGHTED")
                .hasMessageContaining("权重");
    }

    @Test
    @DisplayName("WEIGHTED + 空列表：与不给等价，同样拒绝（前端可能提交空数组）")
    void weightedWithEmptyListIsRejected() {
        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, List.of(), 2))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("长度与地址数量不一致：拒绝，错误里带上两个数量便于定位")
    void sizeMismatchIsRejected() {
        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, List.of(1, 2, 3), 2))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("一致");
    }

    @Test
    @DisplayName("含负数或 null：拒绝（null 会被归一成 -1 后命中同一条规则）")
    void negativeOrNullWeightIsRejected() {
        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, Arrays.asList(7, -1), 2))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("负数");

        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, Arrays.asList(7, null), 2))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("负数");
    }

    @Test
    @DisplayName("WEIGHTED + 全 0：拒绝。全 0 不是「不分发」，是「无法分发」，会退化成轮询")
    void allZeroWeightsIsRejected() {
        assertThatThrownBy(() -> ServerService.requireWeights(WEIGHTED, List.of(0, 0), 2))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("大于 0");
    }

    @Test
    @DisplayName("WEIGHTED + 单个 0 其余非 0：合法。0 表示「不要把流量打到这个实例」，是有效语义")
    void singleZeroIsLegal() {
        assertThat(ServerService.requireWeights(WEIGHTED, List.of(10, 0), 2))
                .containsExactly(10, 0);
    }

    @Test
    @DisplayName("非 WEIGHTED 但预置了权重：保留并校验长度，允许直接切策略时不丢配置")
    void weightsSurviveStrategySwitch() {
        assertThat(ServerService.requireWeights(ROUND_ROBIN, List.of(7, 3), 2))
                .containsExactly(7, 3);
    }

    @Test
    @DisplayName("返回的列表不可变：防止调用方在落库后又把它改脏")
    void returnedListIsImmutable() {
        List<Integer> weights = ServerService.requireWeights(WEIGHTED, List.of(7, 3), 2);
        assertThatThrownBy(() -> weights.add(1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
