package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.Department;
import com.mcpbridge.manager.repository.DepartmentRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 部门树可见性与祖先链（跨部门授权覆盖判定的基础）。
 *
 * <p>树形：1(根) → 2 → 3；1 → 4。dept 2 的成员可见 {2,3}，看不到 1、4。
 */
class DepartmentScopeTest {

    private DepartmentRepository repository;
    private DepartmentScope scope;

    private Department dept(long id, Long parentId) {
        Department d = new Department();
        d.setId(id);
        d.setParentId(parentId);
        return d;
    }

    @BeforeEach
    void setUp() {
        repository = mock(DepartmentRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                dept(1, null), dept(2, 1L), dept(3, 2L), dept(4, 1L)));
        scope = new DepartmentScope(repository);
    }

    private AuthPrincipal user(Long deptId, Set<String> roles) {
        return new AuthPrincipal(1L, "u", "U", deptId, roles, Set.of());
    }

    @Test
    void visibleDeptIdsCoversSelfAndDescendantsOnly() {
        assertEquals(Set.of(2L, 3L), scope.visibleDeptIds(user(2L, Set.of("DEPT_ADMIN"))));
    }

    @Test
    void platformAdminIsUnrestricted() {
        assertEquals(null, scope.visibleDeptIds(user(2L, Set.of("PLATFORM_ADMIN"))));
    }

    @Test
    void canAccessOwnDescendantButNotSiblingOrAncestor() {
        assertTrue(scope.canAccess(3L, user(2L, Set.of("DEPT_DEVELOPER"))));
        assertFalse(scope.canAccess(4L, user(2L, Set.of("DEPT_DEVELOPER"))));
        assertFalse(scope.canAccess(1L, user(2L, Set.of("DEPT_DEVELOPER"))));
    }

    @Test
    void chainToRootIsBottomUpIncludingSelf() {
        assertEquals(List.of(3L, 2L, 1L), scope.deptChainToRoot(3L));
        assertEquals(List.of(1L), scope.deptChainToRoot(1L));
        assertEquals(List.of(), scope.deptChainToRoot(null));
    }

    @Test
    void requireAccessThrowsForbiddenOutsideTree() {
        PlatformException ex = org.junit.jupiter.api.Assertions.assertThrows(
                PlatformException.class, () -> scope.requireAccess(4L, user(2L, Set.of("DEPT_DEVELOPER"))));
        assertTrue(ex.getMessage().contains("跨部门"));
    }
}
