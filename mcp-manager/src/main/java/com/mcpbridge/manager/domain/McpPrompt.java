package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 手动配置的 MCP Prompt（SVR-06）。
 *
 * <p>模板文本里的占位符写作 {@code {{argName}}}，规则由
 * {@link com.mcpbridge.common.util.PromptTemplate} 定义（Manager 与 Executor 共用同一份）。
 * 模板里出现的每个占位符都必须在这里的 {@code arguments} 里声明过——
 * 保存时会校验，免得 {@code {{oderId}}} 这样的拼写错误到运行时才发现。
 */
@Entity
@Table(name = "mcp_prompt")
public class McpPrompt extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** Prompt 名（Server 内唯一）。 */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 128)
    private String title;

    @Column(length = 2000)
    private String description;

    @Column(columnDefinition = "text")
    private String template;

    /** 参数声明，{@code PromptSnapshot.Argument} 数组的 JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String arguments;

    @Column(name = "ttl_ms")
    private Integer ttlMs;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public Integer getTtlMs() { return ttlMs; }
    public void setTtlMs(Integer ttlMs) { this.ttlMs = ttlMs; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
