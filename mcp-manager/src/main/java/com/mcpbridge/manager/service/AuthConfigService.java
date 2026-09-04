package com.mcpbridge.manager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.AuthBSnapshot;
import com.mcpbridge.common.snapshot.AuthDSnapshot;
import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.AuthConfig;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.AuthConfigRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 双跳鉴权配置（BR-4 / SVR-03 / EXE-07，SEC-01）。
 *
 * <p><b>Auth-B（上行）</b>：Executor → 用户 REST API。凭据以 AES-256-GCM 密文入库，
 * 只有「以节点令牌拉取快照」的内部通道能拿到解密结果；任何 {@code /api/**} 查询只回掩码。
 * 密钥字段留空表示「不修改」，避免前端把掩码回传导致凭据被覆盖成 {@code ****abcd}。
 *
 * <p><b>Auth-D（下行）</b>：MCP Client → Executor。static-bearer 令牌只存 sha256，
 * 平台自身也无法还原（EXE-07 验收项）。
 */
@Service
public class AuthConfigService {

    private static final Set<String> HTTP_SCHEMES = Set.of("bearer", "basic");
    private static final TypeReference<List<AuthBSnapshot.ExtraHeader>> EXTRA_HEADERS_TYPE =
            new TypeReference<>() {
            };

    private final AuthConfigRepository authConfigRepository;
    private final McpServerRepository serverRepository;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    public AuthConfigService(AuthConfigRepository authConfigRepository,
                             McpServerRepository serverRepository,
                             CryptoService cryptoService,
                             AuditService auditService) {
        this.authConfigRepository = authConfigRepository;
        this.serverRepository = serverRepository;
        this.cryptoService = cryptoService;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- Auth-B

    @Transactional(readOnly = true)
    public ServerDtos.AuthBView authBView(McpServer server) {
        return authBViews(List.of(server.getId())).getOrDefault(server.getId(), emptyAuthBView());
    }

    /** 批量回显，列表页用；未配置的 Server 不在结果 map 里，由调用方补 {@link #emptyAuthBView()}。 */
    @Transactional(readOnly = true)
    public Map<Long, ServerDtos.AuthBView> authBViews(java.util.Collection<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ServerDtos.AuthBView> result = new LinkedHashMap<>();
        for (AuthConfig config : authConfigRepository.findByServerIdInAndToolId(serverIds, AuthConfig.SERVER_LEVEL)) {
            result.put(config.getServerId(), toAuthBView(config));
        }
        return result;
    }

    public static ServerDtos.AuthBView emptyAuthBView() {
        return new ServerDtos.AuthBView(AuthBSnapshot.Type.NONE, null, null, null, null,
                null, null, null, null, null, List.of(), null);
    }

    private static ServerDtos.AuthBView toAuthBView(AuthConfig config) {
        return new ServerDtos.AuthBView(
                config.getType(),
                config.getInLocation(),
                config.getName(),
                config.getScheme(),
                config.getUsername(),
                config.getMaskedPreview(),
                config.getTokenUrl(),
                config.getClientId(),
                config.getScope(),
                config.getHeaderTemplate(),
                extraHeadersOf(config),
                config.getUpdatedAt());
    }

    /**
     * 保存 Server 级上行授权。
     *
     * <p>凭据字段为空/空白表示保持不变；要清除凭据请把 type 改回 NONE。
     */
    @Transactional
    public ServerDtos.AuthBView saveAuthB(McpServer server, ServerDtos.AuthBRequest request) {
        AuthBSnapshot.Type type = request.type() == null ? AuthBSnapshot.Type.NONE : request.type();
        AuthConfig config = serverLevelConfig(server.getId()).orElseGet(() -> {
            AuthConfig created = new AuthConfig();
            created.setServerId(server.getId());
            created.setToolId(AuthConfig.SERVER_LEVEL);
            return created;
        });

        config.setType(type);
        config.setInLocation(request.location());
        config.setName(trimToNull(request.name()));
        config.setScheme(request.scheme() == null ? null : request.scheme().trim().toLowerCase(Locale.ROOT));
        config.setUsername(trimToNull(request.username()));
        config.setTokenUrl(trimToNull(request.tokenUrl()));
        config.setClientId(trimToNull(request.clientId()));
        config.setScope(trimToNull(request.scope()));
        config.setHeaderTemplate(trimToNull(request.headerTemplate()));
        config.setExtraHeaders(request.extraHeaders() == null || request.extraHeaders().isEmpty()
                ? null : Json.write(request.extraHeaders()));

        Map<String, Object> problems = new LinkedHashMap<>();
        String previousMask = tailMaskOf(config.getMaskedPreview());
        String newMask = null;
        switch (type) {
            case NONE -> {
                config.setSecretEnc(null);
                config.setPasswordEnc(null);
                config.setClientSecretEnc(null);
                config.setInLocation(null);
            }
            case API_KEY -> {
                if (config.getName() == null) {
                    problems.put("name", "API Key 方式必须指定 header 名或 query 参数名");
                }
                if (config.getInLocation() == null) {
                    problems.put("location", "API Key 方式必须指定注入位置 HEADER 或 QUERY");
                }
                applySecret(config, request.secret(), "secret", problems, "API Key 值");
                newMask = maskOf(request.secret());
            }
            case HTTP -> {
                String scheme = config.getScheme();
                if (scheme == null || !HTTP_SCHEMES.contains(scheme)) {
                    problems.put("scheme", "HTTP 鉴权的 scheme 只能是 bearer 或 basic");
                } else if ("bearer".equals(scheme)) {
                    applySecret(config, request.secret(), "secret", problems, "Bearer Token");
                    newMask = maskOf(request.secret());
                    config.setPasswordEnc(null);
                } else {
                    if (config.getUsername() == null) {
                        problems.put("username", "basic 鉴权必须提供用户名");
                    }
                    applyPassword(config, request.password(), problems);
                    newMask = maskOf(request.password());
                    config.setSecretEnc(null);
                }
                config.setClientSecretEnc(null);
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                if (config.getTokenUrl() == null) {
                    problems.put("tokenUrl", "client_credentials 方式必须提供令牌端点");
                }
                if (config.getClientId() == null) {
                    problems.put("clientId", "client_credentials 方式必须提供 clientId");
                }
                if (blank(request.clientSecret()) && blank(config.getClientSecretEnc())) {
                    problems.put("clientSecret", "client_credentials 方式必须提供 clientSecret");
                }
                if (!blank(request.clientSecret())) {
                    config.setClientSecretEnc(cryptoService.encrypt(request.clientSecret().trim()));
                    newMask = maskOf(request.clientSecret());
                }
                config.setSecretEnc(null);
                config.setPasswordEnc(null);
            }
            case CUSTOM_HEADER -> {
                if (config.getHeaderTemplate() == null) {
                    problems.put("headerTemplate", "自定义 Header 方式必须提供 Header 模板");
                }
                if (!blank(request.secret())) {
                    config.setSecretEnc(cryptoService.encrypt(request.secret().trim()));
                    newMask = maskOf(request.secret());
                }
                config.setPasswordEnc(null);
                config.setClientSecretEnc(null);
            }
        }
        if (!problems.isEmpty()) {
            throw PlatformException.validation("上行授权配置不完整", problems);
        }

        config.setMaskedPreview(maskedPreviewOf(config, newMask != null ? newMask : previousMask));
        AuthConfig saved = authConfigRepository.save(config);
        if (server.getStatus() == ServerStatus.DRAFT) {
            server.setStatus(ServerStatus.CONFIGURED);
        }
        serverRepository.save(server);

        // SEC-02：审计只记类型与掩码，绝不记明文
        auditService.record(AuditAction.AUTH_B_CHANGE, "server", server.getId(), Map.of(
                "type", saved.getType().name(),
                "masked", String.valueOf(saved.getMaskedPreview())));
        return authBView(server);
    }

    /** 解密并组装 Executor 需要的上行授权快照。只允许在内部通道（发布/快照）上调用。 */
    @Transactional(readOnly = true)
    public AuthBSnapshot resolveAuthB(McpServer server) {
        AuthConfig config = serverLevelConfig(server.getId()).orElse(null);
        if (config == null || config.getType() == null || config.getType() == AuthBSnapshot.Type.NONE) {
            return AuthBSnapshot.none();
        }
        return new AuthBSnapshot(
                config.getType(),
                config.getInLocation(),
                config.getName(),
                config.getScheme(),
                cryptoService.decrypt(config.getSecretEnc()),
                config.getUsername(),
                cryptoService.decrypt(config.getPasswordEnc()),
                config.getTokenUrl(),
                config.getClientId(),
                cryptoService.decrypt(config.getClientSecretEnc()),
                config.getScope(),
                config.getHeaderTemplate(),
                extraHeadersOf(config));
    }

    // ---------------------------------------------------------------- Auth-D

    @Transactional(readOnly = true)
    public ServerDtos.AuthDView authDView(McpServer server) {
        AuthDSnapshot snapshot = readAuthD(server);
        return new ServerDtos.AuthDView(
                snapshot.mode(),
                snapshot.bearerTokenHashes() == null ? 0 : snapshot.bearerTokenHashes().size(),
                snapshot.scopes() == null ? List.of() : snapshot.scopes(),
                snapshot.issuer(),
                snapshot.authorizationEndpoint(),
                snapshot.tokenEndpoint(),
                snapshot.registrationEndpoint(),
                snapshot.resourceMetadataUrl());
    }

    /**
     * 保存下行授权。static-bearer 令牌只存 sha256，明文在响应里返回一次由调用方决定如何交付。
     *
     * @return 保存后的视图
     */
    @Transactional
    public ServerDtos.AuthDView saveAuthD(McpServer server, ServerDtos.AuthDRequest request) {
        AuthDSnapshot.Mode mode = request.mode() == null ? AuthDSnapshot.Mode.NONE : request.mode();
        Map<String, Object> problems = new LinkedHashMap<>();
        List<String> hashes = new ArrayList<>();
        switch (mode) {
            case NONE -> {
            }
            case STATIC_BEARER -> {
                List<String> tokens = request.staticTokens() == null ? List.of()
                        : request.staticTokens().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
                if (tokens.isEmpty()) {
                    problems.put("staticTokens", "STATIC_BEARER 方式至少需要一个令牌");
                }
                tokens.forEach(t -> hashes.add(Hashing.sha256Hex(t)));
            }
            case OAUTH2 -> {
                // P1：先保存元数据端点，Executor 侧的授权码/DCR 流程在 P1 实现（EXE-07）
                if (blank(request.issuer())) {
                    problems.put("issuer", "OAUTH2 方式必须提供 issuer");
                }
                if (blank(request.authorizationEndpoint())) {
                    problems.put("authorizationEndpoint", "OAUTH2 方式必须提供授权端点");
                }
                if (blank(request.tokenEndpoint())) {
                    problems.put("tokenEndpoint", "OAUTH2 方式必须提供令牌端点");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw PlatformException.validation("下行授权配置不完整", problems);
        }

        AuthDSnapshot snapshot = new AuthDSnapshot(
                mode,
                hashes,
                request.scopes() == null ? List.of() : request.scopes(),
                trimToNull(request.issuer()),
                trimToNull(request.authorizationEndpoint()),
                trimToNull(request.tokenEndpoint()),
                trimToNull(request.registrationEndpoint()),
                null);
        server.setAuthD(Json.write(snapshot));
        if (server.getStatus() == ServerStatus.DRAFT && mode != AuthDSnapshot.Mode.NONE) {
            server.setStatus(ServerStatus.CONFIGURED);
        }
        serverRepository.save(server);
        auditService.record(AuditAction.AUTH_D_CHANGE, "server", server.getId(), Map.of(
                "mode", mode.name(),
                "staticTokenCount", hashes.size()));
        return authDView(server);
    }

    /**
     * 组装下行授权快照。OAUTH2 模式下按端点补齐受保护资源元数据地址
     * （{@code {endpoint}/.well-known/oauth-protected-resource}，EXE-07）。
     */
    public AuthDSnapshot resolveAuthD(McpServer server, String endpoint) {
        AuthDSnapshot snapshot = readAuthD(server);
        if (snapshot.mode() == AuthDSnapshot.Mode.OAUTH2 && snapshot.resourceMetadataUrl() == null && endpoint != null) {
            return new AuthDSnapshot(snapshot.mode(), snapshot.bearerTokenHashes(), snapshot.scopes(),
                    snapshot.issuer(), snapshot.authorizationEndpoint(), snapshot.tokenEndpoint(),
                    snapshot.registrationEndpoint(),
                    endpoint.replaceAll("/+$", "") + AuthDSnapshot.PROTECTED_RESOURCE_METADATA_PATH);
        }
        return snapshot;
    }

    public AuthDSnapshot readAuthD(McpServer server) {
        String json = server.getAuthD();
        if (json == null || json.isBlank()) {
            return AuthDSnapshot.none();
        }
        try {
            AuthDSnapshot snapshot = Json.read(json, AuthDSnapshot.class);
            return snapshot == null ? AuthDSnapshot.none() : snapshot;
        } catch (RuntimeException e) {
            return AuthDSnapshot.none();
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private java.util.Optional<AuthConfig> serverLevelConfig(Long serverId) {
        return authConfigRepository.findByServerIdAndToolId(serverId, AuthConfig.SERVER_LEVEL);
    }

    private static List<AuthBSnapshot.ExtraHeader> extraHeadersOf(AuthConfig config) {
        String json = config.getExtraHeaders();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<AuthBSnapshot.ExtraHeader> headers = Json.read(json, EXTRA_HEADERS_TYPE);
            return headers == null ? List.of() : headers;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void applySecret(AuthConfig config, String plain, String field,
                             Map<String, Object> problems, String label) {
        if (!blank(plain)) {
            config.setSecretEnc(cryptoService.encrypt(plain.trim()));
        } else if (blank(config.getSecretEnc())) {
            problems.put(field, label + "不能为空");
        }
    }

    private void applyPassword(AuthConfig config, String plain, Map<String, Object> problems) {
        if (!blank(plain)) {
            config.setPasswordEnc(cryptoService.encrypt(plain.trim()));
        } else if (blank(config.getPasswordEnc())) {
            problems.put("password", "basic 鉴权必须提供密码");
        }
    }

    private static String maskedPreviewOf(AuthConfig config, String mask) {
        String prefix = switch (config.getType()) {
            case NONE -> "未配置上行授权";
            case API_KEY -> config.getInLocation() + " " + config.getName() + "=";
            case HTTP -> "basic".equals(config.getScheme())
                    ? "basic " + config.getUsername() + ":"
                    : "bearer ";
            case OAUTH2_CLIENT_CREDENTIALS -> "client_credentials " + config.getClientId() + " ";
            case CUSTOM_HEADER -> "custom-header ";
        };
        return config.getType() == AuthBSnapshot.Type.NONE ? prefix : prefix + mask;
    }

    /** 新提交凭据的掩码：仅保留末 4 位（SEC-01）。 */
    private static String maskOf(String plain) {
        return blank(plain) ? null : Hashing.mask(plain.trim());
    }

    /**
     * 从已有回显里取出掩码尾巴。
     * 凭据未变更时沿用它，保证 UI 不会因为主密钥轮换而丢失「已配置」的提示。
     */
    private static String tailMaskOf(String preview) {
        if (preview == null || preview.isBlank()) {
            return "****";
        }
        int index = preview.indexOf("****");
        return index < 0 ? "****" : preview.substring(index);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}