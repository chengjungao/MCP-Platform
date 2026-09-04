package com.mcpbridge.manager.web;

import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.AuthService;
import com.mcpbridge.manager.service.UserService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.AuthDtos;
import com.mcpbridge.manager.web.dto.OrgDtos;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录与当前用户（MGM-01）。
 *
 * <p>令牌是无状态 JWT，「登出」在服务端只做主体缓存失效——真正的失效由前端丢弃令牌完成。
 * 这样即便令牌被复制走，也会在 TTL 到期前因为缓存里查不到而被拒绝（MGM-01 禁用立即生效）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        AuthPrincipal principal = authService.login(request.username(), request.password());
        OrgDtos.UserView user = userService.view(principal.userId(), principal);
        AuthDtos.LoginResponse response = new AuthDtos.LoginResponse(
                authService.issueToken(principal),
                "Bearer",
                authService.tokenTtl().toSeconds(),
                user);
        return ApiResponse.ok(response);
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.MeResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        OrgDtos.UserView user = userService.view(principal.userId(), principal);
        return ApiResponse.ok(new AuthDtos.MeResponse(
                user.id(), user.username(), user.displayName(), user.deptId(),
                user.roles(), user.permissions(), user.lastLoginAt()));
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        userService.changePassword(principal.userId(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(null, "密码已修改，请使用新密码重新登录");
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        authService.invalidate(principal.userId());
        return ApiResponse.ok(null, "已登出");
    }
}