package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.ServerAccessRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Server 级访问守门（MGM-04）：requireManage / requireRead 的两级语义。
 *
 * <p>这套逻辑原先在 {@code ServerService} 上，后来下沉到 {@link ServerAccessGuard} 以解开
 * 与 {@link ResourcePromptService} 的循环依赖（前者需要后者的目录装配，后者需要前者的权限校验）。
 * 语义一字未改，本测试跟着逻辑搬到了新的宿主。
 */
class ServerAccessGuardTest {

    private McpServerRepository serverRepository;
    private ServerAccessRepository accessRepository;
    private DepartmentScope departmentScope;
    private ServerAccessGuard guard;

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
        guard = new ServerAccessGuard(serverRepository, accessRepository, departmentScope);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server(1L, 99L)));
    }

    @Test
    @DisplayName("写权：本部门树内放行")
    void requireManageAllowsDeptTreeMember() {
        when(departmentScope.canAccess(99L, user(9L, Set.of("DEPT_DEVELOPER")))).thenReturn(true);
        assertNotNull(guard.requireManage(1L, user(9L, Set.of("DEPT_DEVELOPER"))));
    }

    @Test
    @DisplayName("写权：外部门拒绝")
    void requireManageDeniesForeignDept() {
        when(departmentScope.canAccess(99L, user(9L, Set.of("DEPT_DEVELOPER")))).thenReturn(false);
        org.mockito.Mockito.doThrow(PlatformException.forbidden("无权访问该部门的资源"))
                .when(departmentScope).requireAccess(eq(99L), any());
        assertThrows(PlatformException.class,
                () -> guard.requireManage(1L, user(9L, Set.of("DEPT_DEVELOPER"))));
    }

    @Test
    @DisplayName("读权：授权给了祖先部门时放行——跨部门授权覆盖部门子树")
    void requireReadAllowsApprovedAncestorGrant() {
        AuthPrincipal foreign = user(9L, Set.of("READONLY"));
        when(departmentScope.canAccess(99L, foreign)).thenReturn(false);
        when(departmentScope.deptChainToRoot(9L)).thenReturn(List.of(9L, 5L));
        // 授权给了 dept 5（foreign 的祖先），foreign 在子树内 → 放行
        when(accessRepository.existsByServerIdAndDeptIdInAndStatus(
                eq(1L), eq(List.of(9L, 5L)), eq(AccessStatus.APPROVED))).thenReturn(true);
        assertNotNull(guard.requireRead(1L, foreign));
    }

    @Test
    @DisplayName("读权：无授权则拒绝")
    void requireReadDeniesWithoutGrant() {
        AuthPrincipal foreign = user(9L, Set.of("READONLY"));
        when(departmentScope.canAccess(99L, foreign)).thenReturn(false);
        when(departmentScope.deptChainToRoot(9L)).thenReturn(List.of(9L));
        when(accessRepository.existsByServerIdAndDeptIdInAndStatus(
                eq(1L), eq(List.of(9L)), eq(AccessStatus.APPROVED))).thenReturn(false);
        assertThrows(PlatformException.class, () -> guard.requireRead(1L, foreign));
    }

    @Test
    @DisplayName("读权：同部门成员直接放行，不再查跨部门授权表")
    void requireReadAllowsSameDeptMember() {
        AuthPrincipal owner = user(99L, Set.of("DEPT_ADMIN"));
        when(departmentScope.canAccess(99L, owner)).thenReturn(true);
        assertEquals(1L, guard.requireRead(1L, owner).getId());
    }

    @Test
    @DisplayName("Server 不存在：404，且不泄露「它属于哪个部门」")
    void missingServerIsNotFound() {
        when(serverRepository.findById(404L)).thenReturn(Optional.empty());
        assertThrows(PlatformException.class, () -> guard.requireRead(404L, user(99L, Set.of("DEPT_ADMIN"))));
        assertThrows(PlatformException.class, () -> guard.requireManage(404L, user(99L, Set.of("DEPT_ADMIN"))));
    }
}
