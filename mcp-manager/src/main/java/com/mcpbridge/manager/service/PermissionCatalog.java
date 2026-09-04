package com.mcpbridge.manager.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点清单（MGM-02 / MGM-04）。
 *
 * <p>命名约定「资源:动作」。控制器用 {@code @PreAuthorize} 搭配 {@code hasAuthority} 声明，
 * 部门级数据隔离不靠权限点，而靠服务层的 {@link DepartmentScope} 过滤（越权返回 403）。
 */
public final class PermissionCatalog {

    /** 内置角色码。 */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String ROLE_OPS = "OPS";
    public static final String ROLE_AUDITOR = "AUDITOR";
    public static final String ROLE_DEPT_ADMIN = "DEPT_ADMIN";
    public static final String ROLE_DEPT_DEVELOPER = "DEPT_DEVELOPER";
    public static final String ROLE_READONLY = "READONLY";

    // ---- 组织与账号 ----
    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_WRITE = "role:write";
    public static final String DEPT_READ = "dept:read";
    public static final String DEPT_WRITE = "dept:write";

    // ---- 注册与解析 ----
    public static final String REGISTRATION_READ = "registration:read";
    public static final String REGISTRATION_CREATE = "registration:create";
    public static final String REGISTRATION_REIMPORT = "registration:reimport";

    // ---- Server / Tool / 授权 ----
    public static final String SERVER_READ = "server:read";
    public static final String SERVER_WRITE = "server:write";
    public static final String TOOL_WRITE = "tool:write";
    public static final String AUTH_WRITE = "auth:write";

    // ---- 集群与发布 ----
    public static final String CLUSTER_READ = "cluster:read";
    public static final String CLUSTER_WRITE = "cluster:write";
    public static final String CLUSTER_GRANT = "cluster:grant";
    public static final String PUBLISH = "publish:execute";
    public static final String ROLLBACK = "publish:rollback";

    // ---- 审计与运维 ----
    public static final String AUDIT_READ = "audit:read";
    public static final String METRICS_READ = "metrics:read";

    public static final List<String> ALL = List.of(
            USER_READ, USER_WRITE, ROLE_READ, ROLE_WRITE, DEPT_READ, DEPT_WRITE,
            REGISTRATION_READ, REGISTRATION_CREATE, REGISTRATION_REIMPORT,
            SERVER_READ, SERVER_WRITE, TOOL_WRITE, AUTH_WRITE,
            CLUSTER_READ, CLUSTER_WRITE, CLUSTER_GRANT, PUBLISH, ROLLBACK,
            AUDIT_READ, METRICS_READ);

    /** 内置角色 → 权限点。平台管理员拥有全部权限点。 */
    public static final Map<String, Set<String>> BUILTIN_ROLES = Map.of(
            ROLE_PLATFORM_ADMIN, Set.copyOf(ALL),
            ROLE_OPS, Set.of(CLUSTER_READ, CLUSTER_WRITE, CLUSTER_GRANT, METRICS_READ, SERVER_READ, AUDIT_READ),
            ROLE_AUDITOR, Set.of(AUDIT_READ, SERVER_READ, REGISTRATION_READ, CLUSTER_READ, METRICS_READ),
            ROLE_DEPT_ADMIN, Set.of(USER_READ, USER_WRITE, DEPT_READ, REGISTRATION_READ, REGISTRATION_CREATE,
                    REGISTRATION_REIMPORT, SERVER_READ, SERVER_WRITE, TOOL_WRITE, AUTH_WRITE,
                    CLUSTER_READ, PUBLISH, ROLLBACK),
            ROLE_DEPT_DEVELOPER, Set.of(REGISTRATION_READ, REGISTRATION_CREATE, REGISTRATION_REIMPORT,
                    SERVER_READ, SERVER_WRITE, TOOL_WRITE, AUTH_WRITE, CLUSTER_READ, PUBLISH),
            ROLE_READONLY, Set.of(REGISTRATION_READ, SERVER_READ, CLUSTER_READ));

    private PermissionCatalog() {
    }
}