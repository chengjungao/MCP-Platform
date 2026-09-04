package com.mcpbridge.manager.service.parse;

import com.mcpbridge.manager.web.dto.RegistrationDtos;

import java.util.List;

/**
 * 一份接口文档的解析产物。
 *
 * @param specVersion 规范版本：{@code 2.0}（Swagger）或 {@code 3.x}（OpenAPI）
 * @param title       文档标题，作为 MCP Server 的默认展示名
 * @param description 文档描述
 * @param version     文档/服务版本，写入 {@code mcp_server.version}
 * @param baseUrls    上游地址候选，来自 {@code servers[].url} 或 Swagger 的 {@code host+basePath}
 * @param operations  operation 列表，顺序与文档一致
 * @param diagnostics 文档级诊断
 */
public record ParsedApi(
        String specVersion,
        String title,
        String description,
        String version,
        List<String> baseUrls,
        List<ParsedOperation> operations,
        List<RegistrationDtos.Diagnostic> diagnostics) {

    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_WARN = "WARN";

    /** 只要存在 ERROR 级诊断，注册就判定为失败，不允许生成半成品 Server（REG-01）。 */
    public boolean hasErrors() {
        return diagnostics != null && diagnostics.stream().anyMatch(d -> LEVEL_ERROR.equals(d.level()));
    }

    public List<String> anchors() {
        return operations == null ? List.of() : operations.stream().map(ParsedOperation::anchor).toList();
    }
}