package com.mcpbridge.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MCP Manager（控制面）启动类。
 *
 * <p>职责边界（PRD §5.2）：用户/角色/部门、REST 注册与解析、MCP Server/Tool/Resource/Prompt
 * 配置与覆盖、集群管理、发布管理。控制面不承载 MCP 协议流量，协议端点在 mcp-executor。
 *
 * <ul>
 *   <li>{@code @EnableAsync}：解析异步化（REG-01），注册接口立即返回，状态轮询可见</li>
 *   <li>{@code @EnableScheduling}：节点心跳超时巡检（PUB-02）</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class ManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagerApplication.class, args);
    }
}