package com.mcpbridge.manager.security;

import java.util.Set;

/**
 * 登录主体。放在 {@code Authentication#getPrincipal()} 中，控制器用
 * {@code @AuthenticationPrincipal AuthPrincipal principal} 直接取用。
 *
 * @param userId      用户 id
 * @param username    登录名
 * @param displayName 展示名
 * @param deptId      归属部门（所有资源的默认归属，MGM-04）
 * @param roles       角色码集合
 * @param permissions 权限点全集（角色权限并集）
 */
public record AuthPrincipal(
        Long userId,
        String username,
        String displayName,
        Long deptId,
        Set<String> roles,
        Set<String> permissions) {

    public static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    public boolean isPlatformAdmin() {
        return roles != null && roles.contains(PLATFORM_ADMIN_ROLE);
    }

    /** 平台管理员隐含全部权限点。 */
    public boolean has(String permission) {
        return isPlatformAdmin() || (permissions != null && permissions.contains(permission));
    }
}