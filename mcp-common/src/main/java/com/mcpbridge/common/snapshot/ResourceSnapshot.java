package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Resource 定义（SVR-05，P1）。
 *
 * <p>Swagger 不含 resource 语义，因此 v1 只提供手动配置：
 * 要么是静态内容，要么映射到某个只读 GET 操作（用 {@code toolName} 引用生效模型中的 tool）。
 *
 * @param uri         资源 URI，如 {@code mcp://crm-order/schema}
 * @param name        展示名
 * @param description 描述
 * @param mimeType    内容类型
 * @param content     静态内容（与 toolName 二选一）
 * @param toolName    映射的 GET tool 名（resources/read 时透传调用）
 * @param ttlMs       list 响应缓存提示（SEP-2549）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceSnapshot(
        String uri,
        String name,
        String description,
        String mimeType,
        String content,
        String toolName,
        Integer ttlMs) {

    public boolean isStatic() {
        return content != null;
    }
}
