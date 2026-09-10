package com.mcpbridge.manager.domain;

import com.mcpbridge.common.error.PlatformException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集群发布配额的解析与判定（PUB-01）。
 *
 * <p>重点是两组容易出错的地方：<b>边界差一</b>（"等于上限"到底是允许还是拒绝）与
 * <b>脏输入</b>（负数、小数、字符串）。判定做成纯函数就是为了能在这里穷举掉，
 * 而不是留到发布失败时才发现。
 */
class ClusterQuotaTest {

    // ------------------------------------------------------------ 解析

    @Test
    @DisplayName("null 与空对象都表示不限")
    void treatsAbsentAsUnlimited() {
        assertThat(ClusterQuota.of(null).isUnlimited()).isTrue();
        assertThat(ClusterQuota.of(Map.of()).isUnlimited()).isTrue();
        assertThat(ClusterQuota.UNLIMITED.isUnlimited()).isTrue();
    }

    @Test
    @DisplayName("只设置一个维度时其余维度保持不限")
    void parsesPartialDocument() {
        ClusterQuota quota = ClusterQuota.of(Map.of("maxServers", 50));

        assertThat(quota.maxServers()).isEqualTo(50);
        assertThat(quota.maxToolsPerServer()).isNull();
        assertThat(quota.maxCatalogItemsPerServer()).isNull();
        assertThat(quota.isUnlimited()).isFalse();
    }

    @Test
    @DisplayName("接受 JSON 数字与前端数字框回传的字符串、空串")
    void coercesNumbersAndStrings() {
        assertThat(ClusterQuota.of(Map.of("maxServers", 50)).maxServers()).isEqualTo(50);
        assertThat(ClusterQuota.of(Map.of("maxServers", "50")).maxServers()).isEqualTo(50);
        assertThat(ClusterQuota.of(Map.of("maxServers", " 50 ")).maxServers()).isEqualTo(50);
        // el-input-number 清空后提交的是空串，按"未设置"处理
        assertThat(ClusterQuota.of(Map.of("maxServers", "")).isUnlimited()).isTrue();
    }

    @Test
    @DisplayName("负数、小数、非数字字符串一律拒绝并指出字段——静默当成不限是最坏的结果")
    void rejectsNonSensicalValues() {
        PlatformException negative = rejection(Map.of("maxServers", -1));
        // 前端靠 details.field 定位到具体输入框，靠 message 展示原因，两者都要成立
        assertThat(negative.details()).containsEntry("field", "quota.maxServers");
        assertThat(negative.getMessage()).contains("本集群已发布 Server 数").contains("不能为负数");

        assertThat(rejection(Map.of("maxServers", 1.5)).details())
                .containsEntry("field", "quota.maxServers");
        assertThat(rejection(Map.of("maxToolsPerServer", "abc")).details())
                .containsEntry("field", "quota.maxToolsPerServer");
        assertThat(rejection(Map.of("maxServers", true)).details())
                .containsEntry("field", "quota.maxServers");
    }

    private static PlatformException rejection(Map<String, Object> raw) {
        try {
            ClusterQuota.of(raw);
            throw new AssertionError("期望校验不通过，实际接受了: " + raw);
        } catch (PlatformException expected) {
            return expected;
        }
    }

    @Test
    @DisplayName("0 是合法值：表示该维度一个都不允许")
    void allowsZero() {
        ClusterQuota quota = ClusterQuota.of(Map.of("maxServers", 0));

        assertThat(quota.maxServers()).isZero();
        assertThat(quota.violations(0, false, 0, 0)).containsKey("quota.maxServers");
    }

    @Test
    @DisplayName("回写时只输出已设置的维度，不多写 null 键")
    void writesOnlyPresentDimensions() {
        Map<String, Object> onlyServers = ClusterQuota.of(Map.of("maxServers", 50)).asMap();
        assertThat(onlyServers).containsExactlyEntriesOf(Map.of("maxServers", 50));

        Map<String, Object> all = ClusterQuota.of(all(3, 200, 100)).asMap();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("maxServers", 3);
        expected.put("maxToolsPerServer", 200);
        expected.put("maxCatalogItemsPerServer", 100);
        assertThat(all).containsExactlyEntriesOf(expected);
        assertThat(ClusterQuota.UNLIMITED.asMap()).isEmpty();
    }

    // ------------------------------------------------------------ 判定

    @Test
    @DisplayName("不限时不产生任何问题")
    void unlimitedNeverViolates() {
        assertThat(ClusterQuota.UNLIMITED.violations(9999, false, 9999, 9999)).isEmpty();
    }

    @Test
    @DisplayName("Server 数：小于上限通过，等于上限拒绝（上限的含义是「最多允许几个」）")
    void checksServerCountBoundary() {
        ClusterQuota quota = ClusterQuota.of(Map.of("maxServers", 3));

        assertThat(quota.violations(2, false, 1, 0)).isEmpty();
        assertThat(quota.violations(3, false, 1, 0)).containsKey("quota.maxServers");
        assertThat(quota.violations(9, false, 1, 0)).containsKey("quota.maxServers");
    }

    @Test
    @DisplayName("已发布在本集群的 Server 重新发布时不再占用新名额，否则会被自己挡住")
    void allowsRepublishOfSameServer() {
        ClusterQuota quota = ClusterQuota.of(Map.of("maxServers", 3));

        assertThat(quota.violations(3, true, 1, 0)).isEmpty();
    }

    @Test
    @DisplayName("tool 与目录数量：等于上限通过，超过才拒绝")
    void checksPerServerLimits() {
        ClusterQuota quota = ClusterQuota.of(all(100, 200, 100));

        assertThat(quota.violations(0, false, 200, 100)).isEmpty();
        assertThat(quota.violations(0, false, 201, 100)).containsKey("quota.maxToolsPerServer");
        assertThat(quota.violations(0, false, 200, 101)).containsKey("quota.maxCatalogItemsPerServer");
    }

    @Test
    @DisplayName("多个维度同时超限时逐项返回，前端能一次看到全部原因")
    void reportsEveryBreach() {
        Map<String, Object> problems = ClusterQuota.of(all(1, 2, 3)).violations(1, false, 9, 9);

        assertThat(problems).containsOnlyKeys(
                "quota.maxServers", "quota.maxToolsPerServer", "quota.maxCatalogItemsPerServer");
        assertThat(problems.get("quota.maxToolsPerServer").toString())
                .contains("上限 2").contains("当前 9");
    }

    private static Map<String, Object> all(int servers, int tools, int catalog) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("maxServers", servers);
        raw.put("maxToolsPerServer", tools);
        raw.put("maxCatalogItemsPerServer", catalog);
        return raw;
    }
}
