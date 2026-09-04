package com.mcpbridge.manager.web;

import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.service.PermissionCatalog;
import com.mcpbridge.manager.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台元信息。前端启动时拉一次，用于：
 * <ul>
 *   <li>在页头显示「仅支持 MCP {@value McpProtocol#SUPPORTED_VERSION}」，避免用户误以为能接 legacy 客户端（决策 D1）；</li>
 *   <li>给出 legacy 版本清单与升级指引链接，被拒绝时能直接跳转到说明文档；</li>
 *   <li>暴露默认 PATH 前缀，注册表单里做实时校验提示。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final ManagerProperties properties;

    public MetaController(ManagerProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("supportedProtocolVersion", McpProtocol.SUPPORTED_VERSION);
        meta.put("legacyProtocolVersions", List.copyOf(McpProtocol.LEGACY_VERSIONS));
        meta.put("legacySupported", false);
        meta.put("upgradeGuideUrl", McpProtocol.UPGRADE_GUIDE_URL);
        meta.put("jsonSchemaDialect", McpProtocol.JSON_SCHEMA_DIALECT);
        meta.put("defaultListTtlMs", McpProtocol.DEFAULT_LIST_TTL_MS);
        meta.put("defaultPathPrefix", properties.defaultPathPrefix());
        meta.put("pathSegmentPattern", com.mcpbridge.common.util.PathSegments.PATTERN);
        meta.put("toolNamePattern", com.mcpbridge.common.util.ToolNames.VALID_NAME);
        meta.put("builtinRoles", List.copyOf(PermissionCatalog.BUILTIN_ROLES.keySet()));
        return ApiResponse.ok(meta);
    }
}