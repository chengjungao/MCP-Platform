package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 部门（MGM-03）。所有业务资源归属部门，是数据隔离的边界（MGM-04）。
 */
@Entity
@Table(name = "department")
public class Department extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name;

    /** 父部门 id，null 表示根部门（部门树）。 */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}