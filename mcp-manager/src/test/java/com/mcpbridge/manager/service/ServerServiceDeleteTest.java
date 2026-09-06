package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.PublishBinding;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.domain.ServerUpstream;
import com.mcpbridge.manager.repository.ApiRegistrationRepository;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.McpToolRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.repository.ServerUpstreamRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ServerService.delete 语义（SVR-06）：已发布 409 拦截、草稿直接删、
 * 关联文档注册记录（api_registration）连坐删除。
 */
class ServerServiceDeleteTest {

    private McpServerRepository serverRepository;
    private PublishBindingRepository bindingRepository;
    private ExecutorClusterRepository clusterRepository;
    private ServerUpstreamRepository upstreamRepository;
    private ApiRegistrationRepository registrationRepository;
    private AuditService auditService;
    private ServerService serverService;

    @BeforeEach
    void setUp() {
        serverRepository = mock(McpServerRepository.class);
        bindingRepository = mock(PublishBindingRepository.class);
        clusterRepository = mock(ExecutorClusterRepository.class);
        upstreamRepository = mock(ServerUpstreamRepository.class);
        registrationRepository = mock(ApiRegistrationRepository.class);
        auditService = mock(AuditService.class);
        serverService = new ServerService(
                serverRepository,
                mock(McpToolRepository.class),
                bindingRepository,
                clusterRepository,
                upstreamRepository,
                mock(com.mcpbridge.manager.repository.ServerAccessRepository.class),
                mock(AuthConfigService.class),
                mock(OverlayService.class),
                mock(PathSegmentGuard.class),
                mock(DepartmentScope.class),
                mock(DepartmentService.class),
                auditService,
                registrationRepository);
        // requireManage：deptId=99 命中 mock 的 canAccess
        McpServer server = server(1L, 99L, null);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(serverRepository.findByRegistrationId(any())).thenReturn(List.of());
        when(upstreamRepository.findByServerIdOrderByServiceIdAsc(1L)).thenReturn(List.of());
    }

    private McpServer server(long id, long deptId, Long registrationId) {
        McpServer s = new McpServer();
        s.setId(id);
        s.setDeptId(deptId);
        s.setName("订单服务");
        s.setPathSegment("order");
        s.setStatus(ServerStatus.DRAFT);
        s.setRegistrationId(registrationId);
        return s;
    }

    private PublishBinding binding(boolean current, BindingState state) {
        PublishBinding b = new PublishBinding();
        b.setServerId(1L);
        b.setClusterId(2L);
        b.setCurrent(current);
        b.setState(state);
        return b;
    }

    private AuthPrincipal user() {
        return new AuthPrincipal(1L, "u", "U", 99L, Set.of("DEPT_DEVELOPER"), Set.of());
    }

    @Test
    void rejectsWhenPublishedToCluster() {
        when(bindingRepository.findByServerIdOrderByIdDesc(1L))
                .thenReturn(List.of(binding(true, BindingState.PUBLISHED)));

        PlatformException e = assertThrows(PlatformException.class,
                () -> serverService.delete(1L, user()));
        assertEquals("该 Server 已发布到集群，请先下线后再删除", e.getMessage());
        verify(serverRepository, never()).delete(any());
    }

    @Test
    void allowsDraftWithoutLiveBinding() {
        when(bindingRepository.findByServerIdOrderByIdDesc(1L)).thenReturn(List.of());

        serverService.delete(1L, user());

        verify(serverRepository).delete(argThat(s -> s.getId() == 1L));
        verify(auditService).record(eq(AuditAction.SERVER_DELETE), eq("server"), eq(1L), any());
    }

    @Test
    void ignoresNonCurrentPublishedHistory() {
        // 历史 binding（已下线/被新版本取代）不影响删除
        when(bindingRepository.findByServerIdOrderByIdDesc(1L)).thenReturn(List.of(
                binding(true, BindingState.OFFLINE),
                binding(false, BindingState.PUBLISHED)));

        serverService.delete(1L, user());

        verify(serverRepository).delete(any());
    }

    @Test
    void cascadesDocumentRegistrations() {
        // 主 registration=5；聚合注册 serviceId="6" 命中且存在；手工补录 serviceId="manual" 跳过
        McpServer server = server(1L, 99L, 5L);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        ServerUpstream auto = new ServerUpstream();
        auto.setServerId(1L);
        auto.setServiceId("6");
        ServerUpstream manual = new ServerUpstream();
        manual.setServerId(1L);
        manual.setServiceId("manual-svc");
        when(upstreamRepository.findByServerIdOrderByServiceIdAsc(1L))
                .thenReturn(List.of(auto, manual));
        when(registrationRepository.existsById(5L)).thenReturn(true);
        when(registrationRepository.existsById(6L)).thenReturn(true);
        when(serverRepository.findByRegistrationId(5L)).thenReturn(List.of());
        when(serverRepository.findByRegistrationId(6L)).thenReturn(List.of());

        serverService.delete(1L, user());

        // 解绑主 registration 后删除
        verify(serverRepository).save(argThat(s -> s.getRegistrationId() == null));
        verify(serverRepository).delete(server);
        verify(registrationRepository).deleteById(5L);
        verify(registrationRepository).deleteById(6L);
        verify(auditService).record(eq(AuditAction.SERVER_DELETE), eq("server"), eq(1L),
                argThat((Map<String, Object> detail) -> {
                    Object deleted = detail.get("deletedRegistrations");
                    return deleted instanceof List<?> list && list.containsAll(List.of(5L, 6L));
                }));
    }

    @Test
    void skipsRegistrationStillReferencedByAnotherServer() {
        McpServer server = server(1L, 99L, 5L);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(serverRepository.findByRegistrationId(5L)).thenReturn(List.of(server(2L, 98L, 5L)));

        serverService.delete(1L, user());

        verify(registrationRepository, never()).deleteById(5L);
    }
}
