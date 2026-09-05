package com.mcpbridge.manager.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server（可挂载多份 Swagger/registration，多 REST 服务支持）。
 *
 * <p>三层模型（BR-2）在此体现为：原始文档存于 {@code api_registration.raw_doc}（不可变），
 * 解析产物存于 {@code base_model} + {@code mcp_tool.base_*}（不可手工改），
 * 用户精修存于 {@code overlay} + {@code mcp_tool.overlay}（版本化）。
 * 生效模型 = base ⊕ overlay，由 OverlayService 合并后交给 PublishService 生成快照。
 *
 * <p><b>多上游</b>：一个 Server 可绑 N 份 registration（N 个 REST 服务），每份对应一条
 * {@link ServerUpstream}（按 {@code serviceId} 区分）。Tool 的 {@code McpTool.upstreamRef}
 * 指向其中某一个，运行时按此选所属上游。Auth-B 下沉到 ServerUpstream 维度。
 */
@Entity
@Table(name = "mcp_server")
public class McpServer extends BaseEntity {

    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    /**
     * 「主」registration（多服务支持后为普通 FK，可空、非 UNIQUE）。
     * 空 Server（先建基础信息再注册文档）此字段为 NULL，首次注册时回填；
     * 其余 registration 通过 server_upstream.service_id 间接关联（serviceId = registrationId 字符串）。
     */
    @Column(name = "registration_id")
    private Long registrationId;

    /** 内部名（可含版本），与对外 PATH 解耦：PATH 是稳定契约，name 可演进（BR-3）。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 对外 PATH 末段，共享集群内全局唯一（BR-3）。 */
    @Column(name = "path_segment", nullable = false, length = 64)
    private String pathSegment;

    @Column(length = 128)
    private String title;

    @Column(length = 2000)
    private String description;

    /** 上游服务版本（来自 info.version）。 */
    @Column(length = 32)
    private String version;

    /** 固定 2026-07-28（决策 D1）。 */
    @Column(name = "protocol_version", nullable = false, length = 16)
    private String protocolVersion;

    /** server 级基座模型（info/servers/securitySchemes 摘要）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_model", columnDefinition = "jsonb")
    private String baseModel;

    /** server 级覆盖层（description / PATH / 流式声明 / 授权覆盖等受限字段集合）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String overlay;

    /** 覆盖层版本号，每次保存递增，用于审计与回滚（P1）。 */
    @Column(name = "overlay_version", nullable = false)
    private int overlayVersion = 0;

    /** 下行跳（Auth-D）配置。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_d", columnDefinition = "jsonb")
    private String authD;

    /**
     * 上游服务列表（一个 Server 挂多个 REST 服务，按 serviceId 区分）。
     * 上行鉴权 Auth-B 下沉到 {@link ServerUpstream#getAuthB()} 维度。
     * Server 级不再持有 upstream 配置。
     */
    @OneToMany(mappedBy = "serverId", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ServerUpstream> upstreams = new ArrayList<>();

    @Column(name = "list_ttl_ms", nullable = false)
    private int listTtlMs = 30_000;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ServerStatus status = ServerStatus.DRAFT;

    @Column(name = "created_by")
    private Long createdBy;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPathSegment() { return pathSegment; }
    public void setPathSegment(String pathSegment) { this.pathSegment = pathSegment; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getBaseModel() { return baseModel; }
    public void setBaseModel(String baseModel) { this.baseModel = baseModel; }
    public String getOverlay() { return overlay; }
    public void setOverlay(String overlay) { this.overlay = overlay; }
    public int getOverlayVersion() { return overlayVersion; }
    public void setOverlayVersion(int overlayVersion) { this.overlayVersion = overlayVersion; }
    public String getAuthD() { return authD; }
    public void setAuthD(String authD) { this.authD = authD; }
    public List<ServerUpstream> getUpstreams() { return upstreams; }
    public void setUpstreams(List<ServerUpstream> upstreams) { this.upstreams = upstreams; }
    public int getListTtlMs() { return listTtlMs; }
    public void setListTtlMs(int listTtlMs) { this.listTtlMs = listTtlMs; }
    public ServerStatus getStatus() { return status; }
    public void setStatus(ServerStatus status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}