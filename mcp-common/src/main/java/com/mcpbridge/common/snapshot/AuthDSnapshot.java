package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 下行跳鉴权（Auth-D：MCP Client → Executor），对应 BR-4 与 EXE-07。
 *
 * <p>P0 支持 {@link Mode#NONE}（仅内网/信任网段）与 {@link Mode#STATIC_BEARER}；
 * {@link Mode#OAUTH2} 为 P1 必达（RFC 9728 / 8414 / 8707 / 9207、授权码 + PKCE(S256) + refresh rotation）。
 *
 * @param mode                 鉴权模式
 * @param bearerTokenHashes    static-bearer 模式下平台签发令牌的 sha256（不存明文）
 * @param scopes               允许的 scope，可映射到 tool 粒度（EXE-07）
 * @param issuer               OAuth2 模式下的 issuer（RFC 8707 resource 校验用）
 * @param authorizationEndpoint OAuth2 授权端点
 * @param tokenEndpoint        OAuth2 令牌端点
 * @param registrationEndpoint CIMD / DCR 客户端注册端点
 * @param resourceMetadataUrl  RFC 9728 Protected Resource Metadata 地址
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthDSnapshot(
        Mode mode,
        List<String> bearerTokenHashes,
        List<String> scopes,
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String registrationEndpoint,
        String resourceMetadataUrl) {

    public enum Mode {
        /** 不鉴权：仅限内网或信任网段部署。 */
        NONE,
        /** 平台签发的静态 Bearer 令牌（哈希比对）。 */
        STATIC_BEARER,
        /** 完整 OAuth 2.1 资源服务器（P1，GA 必达）。 */
        OAUTH2
    }

    public static AuthDSnapshot none() {
        return new AuthDSnapshot(Mode.NONE, List.of(), List.of(), null, null, null, null, null);
    }

    /** RFC 9728 的 well-known 路径（相对 Server 入口）。 */
    public static final String PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";
}
