package com.mcpbridge.manager.security;

import com.mcpbridge.manager.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解析 {@code Authorization: Bearer <jwt>} 并装配安全上下文。
 *
 * <p>权限点每次从库中解析（30s 缓存），因此角色权限变更能在 30s 内对已登录用户生效（MGM-02）；
 * 用户被禁用时立即失效（MGM-01）：缓存过期后 principal 解析返回空 → 401。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthService authService;

    public JwtAuthenticationFilter(JwtService jwtService, AuthService authService) {
        this.jwtService = jwtService;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Long userId = jwtService.parseUserId(token);
            if (userId != null) {
                Optional<AuthPrincipal> principal = authService.resolvePrincipal(userId);
                principal.ifPresent(p -> SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, authorities(p))));
            }
        }
        chain.doFilter(request, response);
    }

    static List<SimpleGrantedAuthority> authorities(AuthPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return authorities;
    }
}