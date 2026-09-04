package com.mcpbridge.manager.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.snapshot.AuthBSnapshot;
import com.mcpbridge.common.snapshot.AuthDSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.manager.domain.ServerStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP Server / Tool / 授权 DTO（SVR-01..04）。
 *
 * <p>安全约束：所有视图对象<b>不得</b>包含上行凭据明文，只回 {@code maskedPreview}（SEC-01）。
 */
public final class ServerDtos {

    private ServerDtos() {
    }

    public record ServerView(
            Long id,
            String name,
            String title,
            String description,
            String pathSegment,
            /** 若已发布，给出主集群上的完整端点示例；未发布则为 null。 */
            String endpointPreview,
            String version,
            String protocolVersion,
            ServerStatus status,
            int overlayVersion,
            Long deptId,
            String deptName,
            Long registrationId,
            long toolCount,
            long enabledToolCount,
            int listTtlMs,
            UpstreamSnapshot upstream,
            AuthBView authB,
            AuthDView authD,
            List<PublishDtos.BindingView> bindings,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** SVR-01：名称/描述/PATH 末段/可见性。PATH 变更会做唯一性校验（BR-3）。 */
    public record ServerUpdateRequest(
            @Size(max = 128) String name,
            @Size(max = 128) String title,
            @Size(max = 2000) String description,
            @Size(max = 64) String pathSegment,
            Integer listTtlMs) {
    }

    /** EXE-03/04：上游调用策略。 */
    public record UpstreamRequest(
            @NotEmpty List<String> baseUrls,
            UpstreamSnapshot.LbStrategy lbStrategy,
            Long connectTimeoutMs,
            Long readTimeoutMs,
            Integer retries,
            List<Integer> retryOnStatus,
            Integer cbFailureThreshold,
            Long cbOpenMs,
            Integer cbHalfOpenProbes) {
    }

    /**
     * SVR-03：上行授权写入。
     *
     * <p>密钥字段留空表示「不修改」，避免前端回传掩码导致凭据被覆盖成 {@code ****xxxx}。
     */
    public record AuthBRequest(
            AuthBSnapshot.Type type,
            AuthBSnapshot.Location location,
            @Size(max = 64) String name,
            @Size(max = 32) String scheme,
            @Size(max = 128) String username,
            String secret,
            String password,
            @Size(max = 512) String tokenUrl,
            @Size(max = 128) String clientId,
            String clientSecret,
            @Size(max = 256) String scope,
            @Size(max = 2048) String headerTemplate,
            List<AuthBSnapshot.ExtraHeader> extraHeaders) {
    }

    /** 上行授权回显：只有掩码与非敏感字段。 */
    public record AuthBView(
            AuthBSnapshot.Type type,
            AuthBSnapshot.Location location,
            String name,
            String scheme,
            String username,
            String maskedPreview,
            String tokenUrl,
            String clientId,
            String scope,
            String headerTemplate,
            List<AuthBSnapshot.ExtraHeader> extraHeaders,
            Instant updatedAt) {
    }

    /**
     * 下行授权（Auth-D）写入。P0 支持 NONE / STATIC_BEARER；OAUTH2 为 P1（EXE-07）。
     * staticTokens 为明文令牌，平台只保存其 sha256。
     */
    public record AuthDRequest(
            AuthDSnapshot.Mode mode,
            List<String> staticTokens,
            List<String> scopes,
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            String registrationEndpoint) {
    }

    public record AuthDView(
            AuthDSnapshot.Mode mode,
            int staticTokenCount,
            List<String> scopes,
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            String registrationEndpoint,
            String resourceMetadataUrl) {
    }

    /**
     * Tool 视图：同时给出基座值与生效值，前端据此渲染「原始 vs 生效」差异视图（BR-2）。
     */
    public record ToolView(
            Long id,
            String anchor,
            String method,
            String path,
            String baseName,
            String effectiveName,
            String baseSummary,
            String effectiveDescription,
            JsonNode baseInputSchema,
            JsonNode effectiveInputSchema,
            Map<String, String> parameterIn,
            boolean requestBodyRequired,
            boolean idempotent,
            boolean enabled,
            boolean streaming,
            String streamFormat,
            String overlayStatus,
            boolean hasOverlay) {
    }

    /**
     * Tool 覆盖写入（BR-2：可覆盖字段集合受限，防越界）。
     * 传入 null 的字段表示不修改；显式传空串表示清空覆盖、回落到基座值。
     */
    public record ToolOverlayRequest(
            @Size(max = 64) String name,
            @Size(max = 4000) String description,
            JsonNode inputSchema,
            Boolean enabled,
            Boolean streaming,
            @Size(max = 16) String streamFormat) {
    }

    public record ToolBatchToggleRequest(@NotEmpty List<Long> toolIds, boolean enabled) {
    }

    /** 生效模型（base ⊕ overlay）中 Server 级的字段差异。 */
    public record FieldChange(String field, Object baseValue, Object effectiveValue, boolean overridden) {
    }

    /** 单个 Tool 的覆盖差异。 */
    public record ToolDiff(String anchor, String baseName, String effectiveName, List<FieldChange> fields) {
    }

    /**
     * 差异视图（BR-2：UI 必须提供「原始 vs 生效」）。
     * 只包含无密钥信息的字段，可安全下发给前端。
     */
    public record DiffView(
            long serverId,
            List<FieldChange> serverFields,
            List<ToolDiff> tools,
            List<String> suspendedOverlays) {
    }

    /** 生效模型（运行时视图），用于「所见即所得」地预览 Executor 将加载的内容。 */
    public record EffectiveModelView(
            long serverId,
            String name,
            String pathSegment,
            String title,
            String description,
            String version,
            String protocolVersion,
            int listTtlMs,
            List<ToolSnapshot> tools) {
    }
}