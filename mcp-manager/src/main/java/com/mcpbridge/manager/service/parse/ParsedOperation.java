package com.mcpbridge.manager.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.manager.web.dto.RegistrationDtos;

import java.util.List;
import java.util.Map;

/**
 * 单个 operation 的解析产物（BR-1：1 个 operation = 1 个 Tool）。
 *
 * @param method              上游 HTTP 方法（大写）
 * @param path                上游路径模板
 * @param anchor              覆盖锚点 {@code METHOD path}，文档升级后据此判定覆盖是否仍然有效（BR-2 / REG-03）
 * @param name                按 BR-1 推导的 tool 名（operationId 优先，否则 method_path），已做同 Server 内去重
 * @param summary             文档 summary，落到 {@code mcp_tool.base_summary}
 * @param description         文档 description，落到 {@code mcp_tool.base_description}
 * @param inputSchema         JSON Schema 2020-12，MCP tools/list 直接下发
 * @param parameterIn         入参位置映射：schema 属性名 → path/query/header/body
 * @param requestBodyRequired 上游是否强制请求体
 * @param streaming           是否流式端点（BR-5）
 * @param streamFormat        SSE / NDJSON / CHUNKED
 * @param idempotent          是否幂等（EXE-03 只对幂等方法自动重试）
 * @param tags                文档标签，供 UI 分组
 * @param diagnostics         该 operation 级别的结构化诊断（REG-01）
 */
public record ParsedOperation(
        String method,
        String path,
        String anchor,
        String name,
        String summary,
        String description,
        JsonNode inputSchema,
        Map<String, String> parameterIn,
        boolean requestBodyRequired,
        boolean streaming,
        String streamFormat,
        boolean idempotent,
        List<String> tags,
        List<RegistrationDtos.Diagnostic> diagnostics) {
}