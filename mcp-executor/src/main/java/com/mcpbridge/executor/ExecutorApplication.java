package com.mcpbridge.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MCP Executor（数据面）启动类。
 *
 * <p>职责边界（PRD §5.3）：对外暴露 MCP 2026-07-28 端点，按 PATH 末段路由到已发布的 Server，
 * 把 tools/call 翻译成上游 REST 调用。控制面的一切（配置、发布、鉴权管理）都不在这里。
 *
 * <p>两条不可动摇的约束：
 * <ul>
 *   <li><b>无状态</b>（R5）：不保存会话、不做粘性路由。任一节点被替换或重启，
 *       客户端的下一次请求照常可用——因此所有跨节点共享的状态都放 Redisson（BR-6）。</li>
 *   <li><b>Modern-only</b>（决策 D1）：只实现 2026-07-28，legacy 协议形态一律显式拒绝并给升级指引。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutorApplication.class, args);
    }
}