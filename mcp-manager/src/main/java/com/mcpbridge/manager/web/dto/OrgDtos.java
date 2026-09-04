package com.mcpbridge.manager.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 用户 / 角色 / 部门 DTO（MGM-01/02/03）。
 */
public final class OrgDtos {

    private OrgDtos() {
    }

    /**
     * 用户视图。注意不含任何口令字段。
     */
    public record UserView(
            Long id,
            String username,
            String displayName,
            String email,
            Long deptId,
            String deptName,
            boolean enabled,
            Set<String> roles,
            Set<String> permissions,
            Instant createdAt,
            Instant lastLoginAt) {
    }

    public record UserCreateRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 64) String password,
            @Size(max = 64) String displayName,
            @Email @Size(max = 128) String email,
            /** 为空则归属创建者所在部门（MGM-04：资源归部门）。 */
            Long deptId,
            Set<String> roleCodes) {
    }

    /** 所有字段可选：只更新传入的部分。{@code password} 非空时重置口令。 */
    public record UserUpdateRequest(
            @Size(max = 64) String displayName,
            @Email @Size(max = 128) String email,
            Long deptId,
            Set<String> roleCodes,
            Boolean enabled,
            @Size(min = 8, max = 64) String password) {
    }

    /** 部门树节点。 */
    public record DepartmentView(
            Long id,
            String name,
            Long parentId,
            String description,
            boolean enabled,
            long memberCount,
            List<DepartmentView> children) {
    }

    public record DepartmentRequest(
            @NotBlank @Size(max = 64) String name,
            Long parentId,
            @Size(max = 255) String description,
            Boolean enabled) {
    }

    public record RoleView(Long id, String code, String name, String description, boolean builtin,
                           Set<String> permissions) {
    }

    public record RoleRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description,
            @NotNull Set<String> permissions) {
    }

    /** 权限点清单，前端渲染角色配置界面用。 */
    public record PermissionCatalogView(List<String> permissions) {
    }
}