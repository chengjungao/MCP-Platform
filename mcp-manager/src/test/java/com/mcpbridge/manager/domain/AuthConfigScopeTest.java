package com.mcpbridge.manager.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth-B 三个配置维度的判定（V5 之后）。
 *
 * <p>这个测试存在的理由是它曾出过事故：V5 把 Auth-B 下沉到 REST 服务级时，REST 服务级行同样用
 * {@code tool_id = 0}，与 Server 级只差 {@code upstream_service_id} 是否为空。当时
 * {@code isServerLevel()} 判的是 {@code toolId == 0}，而 {@code serverLevelConfig()} 查的是
 * {@code (serverId, 0)}——于是「同一 Server 下两个 REST 服务各配了 Auth-B」会让这条查询命中两行，
 * 返回 {@code Optional} 的调用方直接抛 {@code IncorrectResultSizeDataAccessException}，
 * 而它正好在发布链路上。
 *
 * <p>所以这里不只测「各维度能认出来」，更要测「<b>三者互斥且穷尽</b>」——
 * 任何一行配置有且只有一个维度为真。这条性质一旦破坏，查询就会串维度。
 */
class AuthConfigScopeTest {

    private static AuthConfig config(long toolId, String upstreamServiceId) {
        AuthConfig config = new AuthConfig();
        config.setServerId(1L);
        config.setToolId(toolId);
        config.setUpstreamServiceId(upstreamServiceId);
        return config;
    }

    @Test
    @DisplayName("REST 服务级：toolId=0 且 serviceId 非空——这是主用形态，不能被误判成 Server 级")
    void upstreamLevelIsNotServerLevel() {
        AuthConfig config = config(AuthConfig.SERVER_LEVEL, "order-service");

        assertThat(config.isUpstreamLevel()).isTrue();
        assertThat(config.isServerLevel()).isFalse();
    }

    @Test
    @DisplayName("Server 级：toolId=0 且 serviceId 为空（历史形态，保留兼容）")
    void serverLevelHasNoServiceId() {
        AuthConfig config = config(AuthConfig.SERVER_LEVEL, null);

        assertThat(config.isServerLevel()).isTrue();
        assertThat(config.isUpstreamLevel()).isFalse();
        assertThat(config.isToolLevel()).isFalse();
    }

    @Test
    @DisplayName("Tool 级：toolId 非 0，且与 serviceId 维度无关")
    void toolLevelIsDrivenByToolId() {
        AuthConfig config = config(42L, null);

        assertThat(config.isToolLevel()).isTrue();
        assertThat(config.isServerLevel()).isFalse();
        assertThat(config.isUpstreamLevel()).isFalse();
    }

    @Test
    @DisplayName("互斥且穷尽：任意一行配置有且仅有一个维度为真")
    void dimensionsAreDisjointAndExhaustive() {
        AuthConfig[] rows = {
                config(AuthConfig.SERVER_LEVEL, null),      // Server 级
                config(AuthConfig.SERVER_LEVEL, "svc-a"),   // REST 服务级
                config(AuthConfig.SERVER_LEVEL, "svc-b"),   // 第二个 REST 服务
                config(7L, null),                           // Tool 级
        };

        for (AuthConfig row : rows) {
            long hits = 0;
            if (row.isServerLevel()) hits++;
            if (row.isUpstreamLevel()) hits++;
            if (row.isToolLevel()) hits++;
            assertThat(hits)
                    .as("toolId=%s serviceId=%s 命中了 %s 个维度", row.getToolId(), row.getUpstreamServiceId(), hits)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("回归：两个 REST 服务级行都不算 Server 级——否则按 (serverId,0) 查 Optional 会命中多行")
    void twoUpstreamLevelRowsAreNotServerLevel() {
        AuthConfig first = config(AuthConfig.SERVER_LEVEL, "svc-a");
        AuthConfig second = config(AuthConfig.SERVER_LEVEL, "svc-b");

        assertThat(first.isServerLevel()).isFalse();
        assertThat(second.isServerLevel()).isFalse();
        // Server 级查询靠 AndUpstreamServiceIdIsNull 把这两行排除在外，见 AuthConfigRepository
    }
}
