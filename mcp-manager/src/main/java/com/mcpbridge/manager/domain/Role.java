package com.mcpbridge.manager.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 角色与权限点（MGM-02）。内置角色：平台管理员 / 运维 / 审计 / 部门管理员 / 部门开发者 / 只读。
 * 权限点清单见 {@code com.mcpbridge.manager.service.PermissionCatalog}。
 */
@Entity
@Table(name = "role")
public class Role extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    /** 内置角色不可删除。 */
    @Column(nullable = false)
    private boolean builtin = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 64)
    private Set<String> permissions = new LinkedHashSet<>();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }
}