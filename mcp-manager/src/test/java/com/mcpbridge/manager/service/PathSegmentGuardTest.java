package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.repository.McpServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PATH 末段规则（BR-3 / SVR-01）：仅末段可自定义、格式受限、集群内唯一。
 *
 * <p>末段是写进 MCP Client 配置的稳定契约，这里的每一条校验都对应一个真实事故场景：
 * 大小写混用会让「同一个服务出现两个端点」，重名会让一个部门的请求打到另一个部门的上游。
 */
class PathSegmentGuardTest {

    private McpServerRepository serverRepository;
    private PathSegmentGuard guard;

    @BeforeEach
    void setUp() {
        serverRepository = mock(McpServerRepository.class);
        guard = new PathSegmentGuard(serverRepository);
    }

    @Test
    @DisplayName("合法末段规范化后原样返回")
    void acceptsValidSegment() {
        assertThat(guard.requireAvailable(" Order-Service_1 ", null)).isEqualTo("order-service_1");
    }

    @Test
    @DisplayName("大写与首尾连字符被拒绝：末段必须以小写字母或数字开头结尾")
    void rejectsIllegalSegment() {
        assertThatThrownBy(() -> guard.requireAvailable("-order", null))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> guard.requireAvailable("订单中心", null))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> guard.requireAvailable("order!", null))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> guard.requireAvailable("   ", null))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("末段被占用时返回 409 并指出占用方，便于用户改名而不是干猜")
    void rejectsOccupiedSegment() {
        when(serverRepository.findByPathSegment("order")).thenReturn(List.of(server(11L, "订单服务")));

        assertThatThrownBy(() -> guard.requireAvailable("order", null))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> {
                    PlatformException ex = (PlatformException) e;
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.httpStatus()).isEqualTo(409);
                    assertThat(ex.details())
                            .containsEntry("field", "pathSegment")
                            .containsEntry("value", "order")
                            .containsEntry("occupiedByServerId", 11L)
                            .containsEntry("occupiedByServerName", "订单服务");
                });
    }

    @Test
    @DisplayName("更新自身时排除自己，否则任何一次编辑都会撞自己的唯一性约束")
    void excludesSelfOnUpdate() {
        when(serverRepository.findByPathSegment("order")).thenReturn(List.of(server(11L, "订单服务")));

        assertThat(guard.requireAvailable("order", 11L)).isEqualTo("order");
    }

    @Test
    @DisplayName("服务名可规范化时直接用作末段")
    void derivesFromLatinServiceName() {
        assertThat(guard.derive("Order Center API", "seed")).isEqualTo("order-center-api");
    }

    @Test
    @DisplayName("中文名无法推导时回落 api-<短哈希>，保证一定有合法末段")
    void fallsBackToHashForNonLatinName() {
        String derived = guard.derive("订单中心 API", "seed-1");

        assertThat(derived).startsWith("api-");
        assertThat(com.mcpbridge.common.util.PathSegments.isValid(derived)).isTrue();
    }

    @Test
    @DisplayName("同一个 seed 反复推导结果一致（注册必须可重入）")
    void deriveIsRepeatableForSameSeed() {
        assertThat(guard.derive("订单中心", "registration-42"))
                .isEqualTo(guard.derive("订单中心", "registration-42"));
    }

    @Test
    @DisplayName("推导出的末段同样要过唯一性校验")
    void deriveRespectsUniqueness() {
        when(serverRepository.findByPathSegment(anyString())).thenReturn(List.of(server(3L, "占用者")));

        assertThatThrownBy(() -> guard.derive("taken-name", "seed"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("完整端点 = 集群入口 + 保留前缀 + 末段，多余斜杠被清掉")
    void buildsEndpoint() {
        assertThat(guard.endpoint("http://gw.local:9090/", "/mcp/", "order"))
                .isEqualTo("http://gw.local:9090/mcp/order");
        assertThat(guard.endpoint("http://gw.local:9090", null, "order"))
                .isEqualTo("http://gw.local:9090/mcp/order");
    }

    private static McpServer server(Long id, String name) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setName(name);
        return server;
    }
}