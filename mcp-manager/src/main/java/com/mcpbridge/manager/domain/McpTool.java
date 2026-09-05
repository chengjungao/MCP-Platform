package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一个 REST operation（method + path）默认映射一个 MCP Tool（BR-1）。
 *
 * <p>{@code base*} 字段是解析产物（只读基座），{@code overlay} 是用户精修（版本化），
 * 生效值由 OverlayService 合并；{@code anchor} 是覆盖层的定位锚点，
 * 上游文档升级导致锚点消失时 {@code overlayStatus} 置为 SUSPENDED（挂起区，REG-03）。
 */
@Entity
@Table(name = "mcp_tool")
public class McpTool extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** 锚点：{@code METHOD path}，例如 {@code GET /users/{id}}。 */
    @Column(nullable = false, length = 512)
    private String anchor;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "base_name", nullable = false, length = 64)
    private String baseName;

    @Column(name = "base_summary", length = 512)
    private String baseSummary;

    @Column(name = "base_description", length = 4000)
    private String baseDescription;

    /** JSON Schema 2020-12（由 parameters + requestBody 推导）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_input_schema", columnDefinition = "jsonb")
    private String baseInputSchema;

    /** 参数位置映射：schema 属性名 → path/query/header/cookie/body。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter_in", columnDefinition = "jsonb")
    private String parameterIn;

    @Column(name = "request_body_required", nullable = false)
    private boolean requestBodyRequired;

    /** 幂等标记：决定运行时是否允许自动重试（EXE-03）。 */
    @Column(nullable = false)
    private boolean idempotent;

    /** 流式声明（BR-5 / SVR-04）。 */
    @Column(nullable = false)
    private boolean streaming;

    @Column(name = "stream_format", length = 16)
    private String streamFormat;

    /** 指向 server_upstream.service_id，注册时自动绑定（tool 按此选所属上游）。 */
    @Column(name = "upstream_ref", length = 64)
    private String upstreamRef;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 覆盖层：name / description / inputSchema / enabled / streaming 等受限字段。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String overlay;

    @Enumerated(EnumType.STRING)
    @Column(name = "overlay_status", nullable = false, length = 16)
    private OverlayStatus overlayStatus = OverlayStatus.NONE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getBaseName() { return baseName; }
    public void setBaseName(String baseName) { this.baseName = baseName; }
    public String getBaseSummary() { return baseSummary; }
    public void setBaseSummary(String baseSummary) { this.baseSummary = baseSummary; }
    public String getBaseDescription() { return baseDescription; }
    public void setBaseDescription(String baseDescription) { this.baseDescription = baseDescription; }
    public String getBaseInputSchema() { return baseInputSchema; }
    public void setBaseInputSchema(String baseInputSchema) { this.baseInputSchema = baseInputSchema; }
    public String getParameterIn() { return parameterIn; }
    public void setParameterIn(String parameterIn) { this.parameterIn = parameterIn; }
    public boolean isRequestBodyRequired() { return requestBodyRequired; }
    public void setRequestBodyRequired(boolean requestBodyRequired) { this.requestBodyRequired = requestBodyRequired; }
    public boolean isIdempotent() { return idempotent; }
    public void setIdempotent(boolean idempotent) { this.idempotent = idempotent; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public String getStreamFormat() { return streamFormat; }
    public void setStreamFormat(String streamFormat) { this.streamFormat = streamFormat; }
    public String getUpstreamRef() { return upstreamRef; }
    public void setUpstreamRef(String upstreamRef) { this.upstreamRef = upstreamRef; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getOverlay() { return overlay; }
    public void setOverlay(String overlay) { this.overlay = overlay; }
    public OverlayStatus getOverlayStatus() { return overlayStatus; }
    public void setOverlayStatus(OverlayStatus overlayStatus) { this.overlayStatus = overlayStatus; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}