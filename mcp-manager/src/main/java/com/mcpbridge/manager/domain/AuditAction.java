package com.mcpbridge.manager.domain;

/**
 * 审计动作码（MGM-05：注册 / 覆盖 / 发布 / 回滚 / 密钥变更 / 成员授权 必须留痕）。
 */
public final class AuditAction {

    public static final String LOGIN = "auth.login";
    public static final String LOGIN_FAILED = "auth.login_failed";

    public static final String USER_CREATE = "user.create";
    public static final String USER_UPDATE = "user.update";
    public static final String USER_TOGGLE = "user.toggle";
    public static final String USER_ROLE_CHANGE = "user.role_change";

    public static final String DEPT_CREATE = "dept.create";
    public static final String DEPT_UPDATE = "dept.update";
    public static final String DEPT_DELETE = "dept.delete";

    public static final String ROLE_CREATE = "role.create";
    public static final String ROLE_UPDATE = "role.update";
    public static final String ROLE_DELETE = "role.delete";

    public static final String REGISTRATION_CREATE = "registration.create";
    public static final String REGISTRATION_REIMPORT = "registration.reimport";
    public static final String REGISTRATION_PARSE_FAILED = "registration.parse_failed";

    public static final String SERVER_UPDATE = "server.update";
    public static final String SERVER_PATH_CHANGE = "server.path_change";
    public static final String SERVER_DELETE = "server.delete";
    public static final String TOOL_OVERLAY_UPDATE = "tool.overlay_update";
    public static final String TOOL_OVERLAY_RESET = "tool.overlay_reset";
    public static final String TOOL_TOGGLE = "tool.toggle";

    /** Resource / Prompt 手动目录变更（SVR-05/06）。 */
    public static final String RESOURCE_CHANGE = "catalog.resource_change";
    public static final String PROMPT_CHANGE = "catalog.prompt_change";

    /** 密钥变更：detail 只记类型与掩码，绝不记明文（SEC-01/SEC-02）。 */
    public static final String AUTH_B_CHANGE = "authb.change";
    public static final String AUTH_D_CHANGE = "authd.change";

    public static final String CLUSTER_CREATE = "cluster.create";
    public static final String CLUSTER_UPDATE = "cluster.update";
    public static final String CLUSTER_GRANT = "cluster.grant";

    public static final String PUBLISH = "publish.execute";
    public static final String OFFLINE = "publish.offline";
    public static final String ROLLBACK = "publish.rollback";

    public static final String NODE_REGISTER = "node.register";
    public static final String NODE_OFFLINE = "node.offline";

    /** 跨部门访问申请与授权（MGM-04 显式授权闭环）。 */
    public static final String ACCESS_APPLY = "access.apply";
    public static final String ACCESS_APPROVE = "access.approve";
    public static final String ACCESS_REJECT = "access.reject";
    public static final String ACCESS_REVOKE = "access.revoke";

    /**
     * 审计导出（MGM-05）。
     *
     * <p>读审计本身也是敏感动作：把全量操作记录导出成文件，等于把"谁在什么时候做了什么"
     * 一次性带走。必须留痕，否则导出会成为审计体系里唯一的盲区。
     */
    public static final String AUDIT_EXPORT = "audit.export";

    private AuditAction() {
    }
}