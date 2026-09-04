package com.mcpbridge.manager.domain;

import com.mcpbridge.common.snapshot.AuthBSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 上行跳鉴权配置（Auth-B，SVR-03 / BR-4）。
 *
 * <p>安全要点（SEC-01）：只有 {@code *_enc} 三列是密文（AES-GCM，密钥经环境变量/KMS 注入），
 * 非敏感字段（tokenUrl / clientId / scope / header 名）保持明文以便排障；
 * 任何查询接口都只返回 {@code maskedPreview}，绝不回明文。
 *
 * <p>{@code toolId = 0} 表示 Server 级默认配置；非 0 表示 tool 级覆盖（BR-4，P1）。
 */
@Entity
@Table(name = "auth_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_auth_config_server_tool", columnNames = {"server_id", "tool_id"}))
public class AuthConfig extends BaseEntity {

    /** Server 级配置的 toolId 取值。 */
    public static final long SERVER_LEVEL = 0L;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "tool_id", nullable = false)
    private long toolId = SERVER_LEVEL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthBSnapshot.Type type = AuthBSnapshot.Type.NONE;

    /** apiKey 注入位置：HEADER / QUERY。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "in_location", length = 16)
    private AuthBSnapshot.Location inLocation;

    /** header 名或 query 参数名。 */
    @Column(length = 64)
    private String name;

    /** http 鉴权方案：bearer / basic。 */
    @Column(length = 32)
    private String scheme;

    @Column(length = 128)
    private String username;

    /** apiKey 值或 bearer token 的密文。 */
    @Column(name = "secret_enc", length = 2048)
    private String secretEnc;

    /** basic 密码密文。 */
    @Column(name = "password_enc", length = 2048)
    private String passwordEnc;

    @Column(name = "token_url", length = 512)
    private String tokenUrl;

    @Column(name = "client_id", length = 128)
    private String clientId;

    /** oauth2 client secret 密文。 */
    @Column(name = "client_secret_enc", length = 2048)
    private String clientSecretEnc;

    @Column(length = 256)
    private String scope;

    /** 自定义 Header 模板，支持引用平台托管密钥。 */
    @Column(name = "header_template", length = 2048)
    private String headerTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_headers", columnDefinition = "jsonb")
    private String extraHeaders;

    /** 脱敏回显文本，如 {@code bearer ****abcd}。 */
    @Column(name = "masked_preview", length = 255)
    private String maskedPreview;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public long getToolId() { return toolId; }
    public void setToolId(long toolId) { this.toolId = toolId; }
    public AuthBSnapshot.Type getType() { return type; }
    public void setType(AuthBSnapshot.Type type) { this.type = type; }
    public AuthBSnapshot.Location getInLocation() { return inLocation; }
    public void setInLocation(AuthBSnapshot.Location inLocation) { this.inLocation = inLocation; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getScheme() { return scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecretEnc() { return clientSecretEnc; }
    public void setClientSecretEnc(String clientSecretEnc) { this.clientSecretEnc = clientSecretEnc; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getHeaderTemplate() { return headerTemplate; }
    public void setHeaderTemplate(String headerTemplate) { this.headerTemplate = headerTemplate; }
    public String getExtraHeaders() { return extraHeaders; }
    public void setExtraHeaders(String extraHeaders) { this.extraHeaders = extraHeaders; }
    public String getMaskedPreview() { return maskedPreview; }
    public void setMaskedPreview(String maskedPreview) { this.maskedPreview = maskedPreview; }

    public boolean isServerLevel() {
        return toolId == SERVER_LEVEL;
    }
}