package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 手动配置的 MCP Resource（SVR-05）。
 *
 * <p>Swagger 里没有 resource 语义，所以只有两种来源，<b>二选一</b>：
 * <ul>
 *   <li><b>静态内容</b>：{@code staticContent} 非空，{@code resources/read} 直接返回它；</li>
 *   <li><b>映射 tool</b>：{@code toolId} 非空，{@code resources/read} 透传调用该 tool 并把
 *       返回的第一段文本作为内容。</li>
 * </ul>
 * 两者都为空时 {@code resources/read} 只能报错，属于配置错误，在保存时就会被拒绝。
 */
@Entity
@Table(name = "mcp_resource")
public class McpResource extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** 资源 URI（Server 内唯一），如 {@code mcp://crm-order/schema}。 */
    @Column(nullable = false, length = 512)
    private String uri;

    @Column(length = 128)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    /** 静态内容；与 {@link #toolId} 互斥。 */
    @Column(name = "static_content", columnDefinition = "text")
    private String staticContent;

    /** 映射的 tool；与 {@link #staticContent} 互斥。项目内 tool id 而非名字——名字可被覆盖改掉。 */
    @Column(name = "tool_id")
    private Long toolId;

    @Column(name = "ttl_ms")
    private Integer ttlMs;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getStaticContent() { return staticContent; }
    public void setStaticContent(String staticContent) { this.staticContent = staticContent; }
    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
    public Integer getTtlMs() { return ttlMs; }
    public void setTtlMs(Integer ttlMs) { this.ttlMs = ttlMs; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    /** 是否为静态内容型（否则为 tool 映射型）。 */
    public boolean isStaticContent() {
        return staticContent != null;
    }
}
