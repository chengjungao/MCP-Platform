package com.mcpbridge.manager.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

/**
 * 登录与账号相关 DTO（MGM-01）。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    /**
     * @param token           JWT
     * @param tokenType       固定 Bearer
     * @param expiresInSeconds 有效期（秒）
     * @param user            登录用户视图（含权限点，前端按此渲染菜单与按钮）
     */
    public record LoginResponse(String token, String tokenType, long expiresInSeconds, OrgDtos.UserView user) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 64) String newPassword) {
    }

    /** 当前登录用户信息（GET /auth/me）。 */
    public record MeResponse(Long userId, String username, String displayName, Long deptId,
                             Set<String> roles, Set<String> permissions, Instant loginAt) {
    }
}