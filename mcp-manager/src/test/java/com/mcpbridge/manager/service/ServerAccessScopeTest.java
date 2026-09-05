package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.McpToolRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.repository.ServerAccessRepository;
import com.mcpbridge.manager.repository.ServerUpstreamRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * requireRead / requireManage 拆分（跨部门只读授权的服务层守门）。
 */
class ServerAccessScopeTest {

    private McpServerRepository serverRepository;
    private ServerAccessRepository accessRepository;
    private DepartmentScope departmentScope;
    private ServerService serverService;

    private McpServer server(long id, long deptId) {
        McpServer s = new McpServer();
        s.setId(id);
        s.setDeptId(deptId);
        s.setStatus(ServerStatus.DRAFT);
        return s;
    }

    private AuthPrincipal user(Long deptId, Set<String> roles) {
        return new AuthPrincipal(1L, "u", "U", deptId, roles, Set.of());
    }

    @BeforeEach
    void setUp() {
        serverRepository = mock(McpServerRepository.class);
        accessRepository = mock(ServerAccessRepository.class);
        departmentScope = mock(DepartmentScope.class);
        serverService = new ServerService(
                serverRepository,
                mock(McpToolRepository.class),
                mock(PublishBindingRepository.class),
                mock(ExecutorClusterRepository.class),
                mock(ServerUpstreamRepository.class),
                accessRepository,
                mock(AuthConfigService.class),
                mock(OverlayService.class),
                mock(PathSegmentGuard.class),
                departmentScope,
                mock(DepartmentService.class),
                mock(AuditService.class));
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server(1L, 99L)));
    }

    @Test
    void requireManageAllowsDeptTreeMember() {
        when(departmentScope.canAccess(99L, user(9L, Set.of("DEPT_DEVELOPER")))).thenReturn(true);
        assertNotNull(serverService.requireManage(1L, user(9L, Set.of("DEPT_DEVELOPER"))));
    }

    @Test
    void requireManageDeniesForeignDept() {
        when(departmentScope.canAccess(99L, user(9L, Set.of("DEPT_DEVELOPER")))).thenReturn(false);
        org.mockito.Mockito.doThrow(PlatformException.forbidden("无权访问该部门的资源"))
                .when(departmentScope).requireAccess(eq(99L), any());
        assertThrows(PlatformException.class,
                () -> serverService.requireManage(1L, user(9L, Set.of("DEPT_DEVELOPER"))));
    }

    @Test
    void requireReadAllowsApprovedAncestorGrant() {
        AuthPrincipal foreign = user(9L, Set.of("READONLY"));
        when(departmentScope.canAccess(99L, foreign)).thenReturn(false);
        when(departmentScope.deptChainToRoot(9L)).thenReturn(List.of(9L, 5L));
        // 授权给了 dept 5（foreign 的祖先），foreign 在子树内 → 放行
        when(accessRepository.existsByServerIdAndDeptIdInAndStatus(
                eq(1L), eq(List.of(9L, 5L)), eq(AccessStatus.APPROVED))).thenReturn(true);
        assertNotNull(serverService.requireRead(1L, foreign));
    }

    @Test
    void requireReadDeniesWithoutGrant() {
        AuthPrincipal foreign = user(9L, Set.of("READONLY"));
        when(departmentScope.canAccess(99L, foreign)).thenReturn(false);
        when(departmentScope.deptChainToRoot(9L)).thenReturn(List.of(9L));
        when(accessRepository.existsByServerIdAndDeptIdInAndStatus(
                eq(1L), eq(List.of(9L)), eq(AccessStatus.APPROVED))).thenReturn(false);
        assertThrows(PlatformException.class, () -> serverService.requireRead(1L, foreign));
    }

    @Test
    void requireReadAllowsSameDeptMember() {
        AuthPrincipal owner = user(99L, Set.of("DEPT_ADMIN"));
        when(departmentScope.canAccess(99L, owner)).thenReturn(true);
        assertEquals(1L, serverService.requireRead(1L, owner).getId());
    }
}
