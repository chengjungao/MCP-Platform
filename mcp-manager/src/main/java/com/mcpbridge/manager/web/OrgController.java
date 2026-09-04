package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.DepartmentService;
import com.mcpbridge.manager.service.RoleService;
import com.mcpbridge.manager.service.UserService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.OrgDtos;
import com.mcpbridge.manager.web.dto.PageView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户 / 部门 / 角色管理（MGM-01 / MGM-02 / MGM-03 / MGM-04）。
 *
 * <p>权限点是<b>粗粒度门禁</b>（能不能进这个接口），部门隔离是<b>细粒度数据范围</b>
 * （进来之后能看到哪些行）。两者都必须在服务端强制，前端隐藏按钮只是体验优化。
 */
@RestController
@RequestMapping("/api/v1")
public class OrgController {

    private final UserService userService;
    private final DepartmentService departmentService;
    private final RoleService roleService;

    public OrgController(UserService userService, DepartmentService departmentService, RoleService roleService) {
        this.userService = userService;
        this.departmentService = departmentService;
        this.roleService = roleService;
    }

    // ------------------------------------------------------------------ 用户

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<PageView<OrgDtos.UserView>> users(@PageableDefault(size = 20) Pageable pageable,
                                                         @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(userService.page(pageable, principal));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<OrgDtos.UserView> user(@PathVariable Long id,
                                              @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(userService.view(id, principal));
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<OrgDtos.UserView> createUser(@Valid @RequestBody OrgDtos.UserCreateRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(userService.create(request, principal));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<OrgDtos.UserView> updateUser(@PathVariable Long id,
                                                    @Valid @RequestBody OrgDtos.UserUpdateRequest request,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(userService.update(id, request, principal));
    }

    // ------------------------------------------------------------------ 部门

    /** 部门树。角色配置与资源归属选择器都用它，因此不分页。 */
    @GetMapping("/departments/tree")
    @PreAuthorize("hasAuthority('dept:read')")
    public ApiResponse<List<OrgDtos.DepartmentView>> departmentTree() {
        return ApiResponse.ok(departmentService.tree());
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAuthority('dept:read')")
    public ApiResponse<List<OrgDtos.DepartmentView>> departments() {
        return ApiResponse.ok(departmentService.flat());
    }

    @PostMapping("/departments")
    @PreAuthorize("hasAuthority('dept:write')")
    public ApiResponse<OrgDtos.DepartmentView> createDepartment(
            @Valid @RequestBody OrgDtos.DepartmentRequest request) {
        return ApiResponse.ok(departmentService.create(request));
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('dept:write')")
    public ApiResponse<OrgDtos.DepartmentView> updateDepartment(@PathVariable Long id,
                                                                @Valid @RequestBody OrgDtos.DepartmentRequest request) {
        return ApiResponse.ok(departmentService.update(id, request));
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('dept:write')")
    public ApiResponse<Void> deleteDepartment(@PathVariable Long id) {
        departmentService.delete(id);
        return ApiResponse.ok(null, "部门已删除");
    }

    // ------------------------------------------------------------------ 角色

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<OrgDtos.RoleView>> roles() {
        return ApiResponse.ok(roleService.list());
    }

    /** 权限点清单：前端渲染角色配置界面用，不做权限限制（已登录即可读）。 */
    @GetMapping("/permissions")
    public ApiResponse<OrgDtos.PermissionCatalogView> permissions() {
        return ApiResponse.ok(roleService.catalog());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<OrgDtos.RoleView> createRole(@Valid @RequestBody OrgDtos.RoleRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<OrgDtos.RoleView> updateRole(@PathVariable Long id,
                                                    @Valid @RequestBody OrgDtos.RoleRequest request) {
        return ApiResponse.ok(roleService.update(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok(null, "角色已删除");
    }
}