package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 平台用户（MGM-01）。首版仅本地账号，P2 通过标准 OIDC 对接企业 IdP（Non-Goal N7）。
 *
 * <p>表名使用 {@code platform_user}：{@code user} 在 Postgres 中是保留字。
 */
@Entity
@Table(name = "platform_user")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 散列，绝不存明文。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(length = 128)
    private String email;

    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "platform_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    /** 用户权限点全集（角色权限的并集）。 */
    public Set<String> permissions() {
        Set<String> all = new LinkedHashSet<>();
        for (Role role : roles) {
            all.addAll(role.getPermissions());
        }
        return all;
    }

    public boolean hasRole(String code) {
        return roles.stream().anyMatch(r -> code.equals(r.getCode()));
    }
}