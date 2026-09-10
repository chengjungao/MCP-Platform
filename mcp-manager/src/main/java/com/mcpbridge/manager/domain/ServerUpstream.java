package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一个 MCP Server 挂载的一个 REST 服务（多服务支持）。
 *
 * <p>一个 Server 可有多条 {@code ServerUpstream}（按 {@code serviceId} 区分），
 * 每条对应一份 Swagger 文档（一个 {@link ApiRegistration}）。Tool 的
 * {@code McpTool.upstreamRef} 指向其中的 {@code serviceId}，运行时按此选所属上游。
 *
 * <p>本服务的上行鉴权（Auth-B）独立存放在 {@code auth_config} 表的
 * {@code (server_id, upstream_service_id)} 维度上，由 AuthConfigService 读写——
 * 同一 Server 内不同 REST 服务因此可以各用一套凭据。
 *
 * @see McpServer#getUpstreams()
 * @see McpTool#getUpstreamRef()
 */
@Entity
@Table(name = "server_upstream")
public class ServerUpstream extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** 业务标识，tool.upstream_ref 指向它；建议用 registrationId 或 slug(title)#shortHash。 */
    @Column(name = "service_id", nullable = false, length = 64)
    private String serviceId;

    @Column(nullable = false, length = 128)
    private String name;

    /** 同一 REST 服务的多实例地址（负载均衡在此列表上），List&lt;String&gt; JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_urls", nullable = false, columnDefinition = "jsonb")
    private String baseUrls;

    @Column(name = "lb_strategy", nullable = false, length = 16)
    private String lbStrategy = "ROUND_ROBIN";

    /** 与 baseUrls 等长的权重（WEIGHTED 时使用），List&lt;Integer&gt; JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String weights;

    @Column(name = "connect_timeout", nullable = false)
    private long connectTimeout = 3_000L;

    @Column(name = "read_timeout", nullable = false)
    private long readTimeout = 30_000L;

    @Column(nullable = false)
    private int retries = 1;

    /** 触发重试的上游状态码，List&lt;Integer&gt; JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_on_status", columnDefinition = "jsonb")
    private String retryOnStatus;

    /** 熔断配置 JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "circuit_breaker", columnDefinition = "jsonb")
    private String circuitBreaker;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrls() { return baseUrls; }
    public void setBaseUrls(String baseUrls) { this.baseUrls = baseUrls; }
    public String getLbStrategy() { return lbStrategy; }
    public void setLbStrategy(String lbStrategy) { this.lbStrategy = lbStrategy; }
    public String getWeights() { return weights; }
    public void setWeights(String weights) { this.weights = weights; }
    public long getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(long connectTimeout) { this.connectTimeout = connectTimeout; }
    public long getReadTimeout() { return readTimeout; }
    public void setReadTimeout(long readTimeout) { this.readTimeout = readTimeout; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public String getRetryOnStatus() { return retryOnStatus; }
    public void setRetryOnStatus(String retryOnStatus) { this.retryOnStatus = retryOnStatus; }
    public String getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(String circuitBreaker) { this.circuitBreaker = circuitBreaker; }
}
