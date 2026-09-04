package com.mcpbridge.manager.security;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 当前登录主体访问入口。服务层统一通过它取 principal，再从 principal 拿 deptId 做数据隔离（MGM-04）。
 */
@Component
public class CurrentPrincipal {

    public Optional<AuthPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public AuthPrincipal require() {
        return find().orElseThrow(() -> new PlatformException(ErrorCode.UNAUTHENTICATED, "未认证"));
    }

    /** 审计与归属在系统任务（如异步解析）中可能没有登录态，此时返回 null 而不是抛错。 */
    public String actorNameOrNull() {
        return find().map(AuthPrincipal::username).orElse("system");
    }

    public Long actorIdOrNull() {
        return find().map(AuthPrincipal::userId).orElse(null);
    }

    public Long deptIdOrNull() {
        return find().map(AuthPrincipal::deptId).orElse(null);
    }
}