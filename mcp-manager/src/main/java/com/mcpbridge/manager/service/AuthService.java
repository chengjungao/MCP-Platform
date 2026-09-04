package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.Role;
import com.mcpbridge.manager.domain.User;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录与主体解析（MGM-01）。
 *
 * <p>principal 带 30 秒本地缓存：既避免每请求查库，又满足 MGM-02
 * 「权限点变更对已登录用户生效（≤30s）」与「禁用用户立即失效」（写操作会主动失效缓存）。
 */
@Service
public class AuthService {

    private static final Duration PRINCIPAL_CACHE_TTL = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final ManagerProperties properties;
    private final Map<Long, CacheEntry> principalCache = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService, ManagerProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** 登录成功返回令牌与主体；失败统一报「用户名或密码错误」，不区分账号是否存在。 */
    @Transactional
    public AuthPrincipal login(String username, String password) {
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty() || !passwordEncoder.matches(password, found.get().getPasswordHash())) {
            auditService.record(AuditAction.LOGIN_FAILED, "user", username, Map.of("reason", "bad_credentials"));
            throw new PlatformException(ErrorCode.UNAUTHENTICATED, "用户名或密码错误");
        }
        User user = found.get();
        if (!user.isEnabled()) {
            auditService.record(AuditAction.LOGIN_FAILED, "user", username, Map.of("reason", "disabled"));
            throw new PlatformException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        AuthPrincipal principal = toPrincipal(user);
        principalCache.put(user.getId(), new CacheEntry(principal, Instant.now().plus(PRINCIPAL_CACHE_TTL)));
        auditService.record(AuditAction.LOGIN, "user", user.getId());
        return principal;
    }

    /** 由 JwtAuthenticationFilter 调用；缓存未命中或已过期时回源查库。 */
    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> resolvePrincipal(Long userId) {
        CacheEntry cached = principalCache.get(userId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cached.principal());
        }
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty() || !user.get().isEnabled()) {
            principalCache.remove(userId);
            return Optional.empty();
        }
        AuthPrincipal principal = toPrincipal(user.get());
        principalCache.put(userId, new CacheEntry(principal, Instant.now().plus(PRINCIPAL_CACHE_TTL)));
        return Optional.of(principal);
    }

    /** 用户被禁用 / 角色权限变更时调用，保证「立即失效」而不是等缓存过期。 */
    public void invalidate(Long userId) {
        principalCache.remove(userId);
    }

    public void invalidateAll() {
        principalCache.clear();
    }

    public String issueToken(AuthPrincipal principal) {
        return jwtService.issue(principal);
    }

    /** 令牌有效期，登录响应里的 expiresIn 用。 */
    public Duration tokenTtl() {
        return properties.jwt().ttl();
    }

    private static AuthPrincipal toPrincipal(User user) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            roles.add(role.getCode());
            permissions.addAll(role.getPermissions());
        }
        return new AuthPrincipal(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getDeptId(), Set.copyOf(roles), Set.copyOf(permissions));
    }

    private record CacheEntry(AuthPrincipal principal, Instant expiresAt) {
    }
}