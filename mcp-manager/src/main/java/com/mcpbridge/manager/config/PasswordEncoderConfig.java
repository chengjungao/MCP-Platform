package com.mcpbridge.manager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器独立配置。
 *
 * <p>刻意从 {@link SecurityConfig} 拆出：{@code SecurityConfig} 构造器注入
 * {@link com.mcpbridge.manager.security.JwtAuthenticationFilter}，后者又注入
 * {@link com.mcpbridge.manager.service.AuthService}，而 {@code AuthService} 需要注入
 * {@link PasswordEncoder}。若 {@code passwordEncoder()} 定义在 {@code SecurityConfig}，
 * 三者形成循环：{@code AuthService → PasswordEncoder bean → SecurityConfig →
 * JwtAuthenticationFilter → AuthService}。独立配置类切断这条边，且语义上
 * 「密码编码器是通用基础设施」与「安全过滤链配置」本就该分开。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
