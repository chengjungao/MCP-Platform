package com.mcpbridge.manager.security;

import com.mcpbridge.manager.config.ManagerProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;

/**
 * 本地账号登录令牌（MGM-01）。
 *
 * <p>令牌只承载 uid / username / deptId，<b>不</b>放权限点：
 * 权限在每次请求时由 {@code AuthService#resolvePrincipal} 从库里取（带 30s 缓存），
 * 以满足 MGM-02「权限点变更对已登录用户生效（≤30s）」。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final ManagerProperties properties;
    private final SecretKey key;

    public JwtService(ManagerProperties properties) {
        this.properties = properties;
        this.key = deriveKey(properties.jwt().secret());
        if (properties.jwt().secret().startsWith("dev-only")) {
            log.warn("正在使用开发默认 JWT 密钥，生产环境必须通过 MANAGER_JWT_SECRET 注入随机密钥");
        }
    }

    public String issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.jwt().ttl());
        return Jwts.builder()
                .subject(principal.username())
                .issuer(properties.jwt().issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("uid", principal.userId())
                .claim("dept", principal.deptId())
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * @return 用户 id；令牌无效/过期时返回 {@code null}（由过滤器转 401）
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.jwt().issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Number uid = claims.get("uid", Number.class);
            return uid == null ? null : uid.longValue();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("令牌校验失败: {}", e.getMessage());
            return null;
        }
    }

    public Instant expiresAt() {
        return Instant.now().plus(properties.jwt().ttl());
    }

    /** 按 SHA-256 从配置源串派生 HS256 密钥（保证 32 字节，且允许注入高熵随机串）。 */
    static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new javax.crypto.spec.SecretKeySpec(digest, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}