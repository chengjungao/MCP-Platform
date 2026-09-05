package com.mcpbridge.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.ToolNames;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.OverlayStatus;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * base ⊕ overlay 生效模型（BR-2）。
 *
 * <p>三层职责划分：
 * <ul>
 *   <li><b>base</b>：来自原始文档，只读。Server 存于 {@code mcp_server.base_model}（jsonb），
 *       Tool 存于 {@code mcp_tool.base_*} 列。</li>
 *   <li><b>overlay</b>：用户覆盖，稀疏存储。Server 存于 {@code mcp_server.overlay}，
 *       Tool 存于 {@code mcp_tool.overlay}；只记录被改过的键，未出现的键一律回落 base。</li>
 *   <li><b>effective</b>：合并结果。Server 的生效值直接落在实体列上（便于唯一性约束与端点拼接），
 *       Tool 的生效值在读时计算（{@link #toSnapshot}）。</li>
 * </ul>
 *
 * <p>覆盖锚点是 {@code METHOD path}（{@link ToolNames#anchor}）。文档升级后锚点消失的覆盖项
 * 进入 {@link OverlayStatus#SUSPENDED} 挂起区，绝不静默丢弃（REG-03）。
 *
 * <p>本服务不依赖仓储，只做实体上的合并计算，因此可纯单元测试覆盖。
 */
@Service
public class OverlayService {

    /** Server 级可覆盖字段。 */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_PATH_SEGMENT = "pathSegment";
    public static final String FIELD_LIST_TTL_MS = "listTtlMs";

    /** Tool 级可覆盖字段（BR-2：可覆盖集合受限，method/path/anchor 不可覆盖）。 */
    public static final String FIELD_INPUT_SCHEMA = "inputSchema";
    public static final String FIELD_STREAMING = "streaming";
    public static final String FIELD_STREAM_FORMAT = "streamFormat";

    /**
     * Server 的基座模型（原始文档推导，只读）。
     *
     * @param baseUrls 文档声明的上游地址候选
     */
    public record ServerBase(String name, String title, String description, String version,
                             String pathSegment, List<String> baseUrls) {
    }

    /**
     * 锚点对账结果。
     *
     * @param suspended 锚点消失、进入挂起区的覆盖锚点
     * @param restored  锚点重新出现、从挂起区恢复的覆盖锚点
     */
    public record ReconcileResult(List<String> suspended, List<String> restored) {
    }

    public ServerBase baseOf(McpServer server) {
        JsonNode node = readTree(server.getBaseModel());
        if (node == null) {
            return new ServerBase(server.getName(), server.getTitle(), server.getDescription(),
                    server.getVersion(), server.getPathSegment(), List.of());
        }
        List<String> baseUrls = new ArrayList<>();
        JsonNode urls = node.get("baseUrls");
        if (urls != null && urls.isArray()) {
            urls.forEach(u -> baseUrls.add(u.asText()));
        }
        return new ServerBase(
                text(node, FIELD_NAME, server.getName()),
                text(node, FIELD_TITLE, server.getTitle()),
                text(node, FIELD_DESCRIPTION, server.getDescription()),
                text(node, "version", server.getVersion()),
                text(node, FIELD_PATH_SEGMENT, server.getPathSegment()),
                baseUrls);
    }

    public void writeBaseModel(McpServer server, ServerBase base) {
        ObjectNode node = Json.obj();
        putText(node, FIELD_NAME, base.name());
        putText(node, FIELD_TITLE, base.title());
        putText(node, FIELD_DESCRIPTION, base.description());
        putText(node, "version", base.version());
        putText(node, FIELD_PATH_SEGMENT, base.pathSegment());
        node.set("baseUrls", Json.MAPPER.valueToTree(base.baseUrls() == null ? List.of() : base.baseUrls()));
        server.setBaseModel(Json.write(node));
    }

    /** 把 base ⊕ overlay 的结果写回 Server 实体列（生效值）。 */
    public void applyServerOverlay(McpServer server) {
        ServerBase base = baseOf(server);
        ObjectNode overlay = serverOverlay(server);
        server.setName(text(overlay, FIELD_NAME, base.name()));
        server.setTitle(text(overlay, FIELD_TITLE, base.title()));
        server.setDescription(text(overlay, FIELD_DESCRIPTION, base.description()));
        server.setPathSegment(text(overlay, FIELD_PATH_SEGMENT, base.pathSegment()));
        server.setVersion(base.version());
        JsonNode ttl = overlay.get(FIELD_LIST_TTL_MS);
        server.setListTtlMs(ttl != null && ttl.isNumber() ? ttl.asInt() : McpProtocol.DEFAULT_LIST_TTL_MS);
    }

    public ObjectNode serverOverlay(McpServer server) {
        ObjectNode node = asObject(readTree(server.getOverlay()));
        return node == null ? Json.obj() : node;
    }

    public ObjectNode toolOverlay(McpTool tool) {
        ObjectNode node = asObject(readTree(tool.getOverlay()));
        return node == null ? Json.obj() : node;
    }

    /** 写入 tool 覆盖；覆盖为空则清空并回到 {@link OverlayStatus#NONE}。 */
    public void writeToolOverlay(McpTool tool, ObjectNode overlay) {
        if (overlay == null || overlay.isEmpty()) {
            tool.setOverlay(null);
            tool.setOverlayStatus(OverlayStatus.NONE);
            return;
        }
        tool.setOverlay(Json.write(overlay));
        tool.setOverlayStatus(OverlayStatus.ACTIVE);
    }

    /** 覆盖 tool 名必须仍然满足 MCP 命名约束，否则 Executor 下发的 tools/list 会被客户端拒绝。 */
    public void requireValidToolName(String name) {
        if (name != null && !name.isBlank() && !ToolNames.isValid(name)) {
            throw PlatformException.validation("tool 名只能包含字母、数字、下划线与连字符，长度 1-64",
                    Map.of("field", FIELD_NAME, "value", name, "pattern", ToolNames.VALID_NAME.pattern()));
        }
    }

    public String effectiveName(McpTool tool) {
        return text(toolOverlay(tool), FIELD_NAME, tool.getBaseName());
    }

    public String effectiveDescription(McpTool tool) {
        String base = tool.getBaseDescription() != null && !tool.getBaseDescription().isBlank()
                ? tool.getBaseDescription() : tool.getBaseSummary();
        return text(toolOverlay(tool), FIELD_DESCRIPTION, base);
    }

    public JsonNode effectiveInputSchema(McpTool tool) {
        JsonNode override = toolOverlay(tool).get(FIELD_INPUT_SCHEMA);
        if (override != null && override.isObject()) {
            return override;
        }
        JsonNode base = readTree(tool.getBaseInputSchema());
        return base == null ? emptyObjectSchema() : base;
    }

    public Map<String, String> parameterInOf(McpTool tool) {
        JsonNode node = readTree(tool.getParameterIn());
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        }
        return result;
    }

    /** 生成 Executor 运行时加载的生效 tool（仅 enabled=true 的会进入快照，由调用方过滤）。 */
    public ToolSnapshot toSnapshot(McpTool tool) {
        ObjectNode overlay = toolOverlay(tool);
        boolean streaming = overlay.has(FIELD_STREAMING)
                ? overlay.get(FIELD_STREAMING).asBoolean(tool.isStreaming())
                : tool.isStreaming();
        String streamFormat = text(overlay, FIELD_STREAM_FORMAT, tool.getStreamFormat());
        return new ToolSnapshot(
                effectiveName(tool),
                tool.getBaseSummary(),
                effectiveDescription(tool),
                tool.getMethod(),
                tool.getPath(),
                tool.getAnchor(),
                effectiveInputSchema(tool),
                parameterInOf(tool),
                tool.isRequestBodyRequired(),
                streaming,
                streaming ? streamFormat : null,
                tool.isIdempotent(),
                tool.getUpstreamRef(),
                null);
    }

    public ServerDtos.ToolView toToolView(McpTool tool) {
        ObjectNode overlay = toolOverlay(tool);
        boolean streaming = overlay.has(FIELD_STREAMING)
                ? overlay.get(FIELD_STREAMING).asBoolean(tool.isStreaming())
                : tool.isStreaming();
        return new ServerDtos.ToolView(
                tool.getId(),
                tool.getAnchor(),
                tool.getMethod(),
                tool.getPath(),
                tool.getBaseName(),
                effectiveName(tool),
                tool.getBaseSummary(),
                effectiveDescription(tool),
                readTree(tool.getBaseInputSchema()),
                effectiveInputSchema(tool),
                parameterInOf(tool),
                tool.isRequestBodyRequired(),
                tool.isIdempotent(),
                tool.isEnabled(),
                streaming,
                streaming ? text(overlay, FIELD_STREAM_FORMAT, tool.getStreamFormat()) : null,
                tool.getOverlayStatus().name(),
                !overlay.isEmpty());
    }

    /**
     * 「原始 vs 生效」差异视图（BR-2 要求 UI 必须提供）。
     * 只包含无凭据信息的字段，可安全下发前端。
     */
    public ServerDtos.DiffView diff(McpServer server, List<McpTool> tools) {
        ServerBase base = baseOf(server);
        List<ServerDtos.FieldChange> serverFields = new ArrayList<>();
        serverFields.add(change(FIELD_NAME, base.name(), server.getName()));
        serverFields.add(change(FIELD_TITLE, base.title(), server.getTitle()));
        serverFields.add(change(FIELD_DESCRIPTION, base.description(), server.getDescription()));
        serverFields.add(change(FIELD_PATH_SEGMENT, base.pathSegment(), server.getPathSegment()));
        serverFields.add(change(FIELD_LIST_TTL_MS, McpProtocol.DEFAULT_LIST_TTL_MS, server.getListTtlMs()));

        List<ServerDtos.ToolDiff> toolDiffs = new ArrayList<>();
        List<String> suspended = new ArrayList<>();
        for (McpTool tool : tools) {
            if (tool.getOverlayStatus() == OverlayStatus.SUSPENDED) {
                suspended.add(tool.getAnchor());
            }
            ObjectNode overlay = toolOverlay(tool);
            if (overlay.isEmpty()) {
                continue;
            }
            List<ServerDtos.FieldChange> fields = new ArrayList<>();
            if (overlay.has(FIELD_NAME)) {
                fields.add(change(FIELD_NAME, tool.getBaseName(), effectiveName(tool)));
            }
            if (overlay.has(FIELD_DESCRIPTION)) {
                fields.add(change(FIELD_DESCRIPTION, tool.getBaseDescription(), effectiveDescription(tool)));
            }
            if (overlay.has(FIELD_INPUT_SCHEMA)) {
                fields.add(new ServerDtos.FieldChange(FIELD_INPUT_SCHEMA,
                        readTree(tool.getBaseInputSchema()), effectiveInputSchema(tool), true));
            }
            if (overlay.has(FIELD_STREAMING)) {
                fields.add(change(FIELD_STREAMING, tool.isStreaming(), overlay.get(FIELD_STREAMING).asBoolean()));
            }
            if (overlay.has(FIELD_STREAM_FORMAT)) {
                fields.add(change(FIELD_STREAM_FORMAT, tool.getStreamFormat(),
                        text(overlay, FIELD_STREAM_FORMAT, tool.getStreamFormat())));
            }
            fields.add(change("enabled", true, tool.isEnabled()));
            toolDiffs.add(new ServerDtos.ToolDiff(tool.getAnchor(), tool.getBaseName(), effectiveName(tool), fields));
        }
        return new ServerDtos.DiffView(server.getId(), serverFields, toolDiffs, suspended);
    }

    /**
     * 文档升级后的锚点对账（REG-03）。
     *
     * @param tools        Server 下现有全部 tool（含已停用）
     * @param validAnchors 新文档中存在的锚点集合
     */
    public ReconcileResult reconcileAnchors(List<McpTool> tools, Set<String> validAnchors) {
        List<String> suspended = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        for (McpTool tool : tools) {
            if (toolOverlay(tool).isEmpty()) {
                if (tool.getOverlayStatus() != OverlayStatus.NONE) {
                    tool.setOverlayStatus(OverlayStatus.NONE);
                }
                continue;
            }
            if (validAnchors.contains(tool.getAnchor())) {
                if (tool.getOverlayStatus() != OverlayStatus.ACTIVE) {
                    tool.setOverlayStatus(OverlayStatus.ACTIVE);
                    restored.add(tool.getAnchor());
                }
            } else {
                tool.setOverlayStatus(OverlayStatus.SUSPENDED);
                suspended.add(tool.getAnchor());
            }
        }
        return new ReconcileResult(suspended, restored);
    }

    private static ServerDtos.FieldChange change(String field, Object baseValue, Object effectiveValue) {
        boolean overridden = baseValue == null
                ? effectiveValue != null
                : !baseValue.equals(effectiveValue);
        return new ServerDtos.FieldChange(field, baseValue, effectiveValue, overridden);
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.tree(json);
        } catch (RuntimeException e) {
            // 库里存在非法 JSON 时不拖垮整个列表接口，交由差异视图暴露
            return null;
        }
    }

    private static ObjectNode asObject(JsonNode node) {
        return node instanceof ObjectNode objectNode ? objectNode : null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static JsonNode emptyObjectSchema() {
        ObjectNode node = Json.obj();
        node.put("$schema", McpProtocol.JSON_SCHEMA_DIALECT);
        node.put("type", "object");
        node.putObject("properties");
        return node;
    }
}