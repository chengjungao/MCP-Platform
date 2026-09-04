package com.mcpbridge.manager.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.web.dto.RegistrationDtos;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 OpenAPI/Swagger 的 operation 入参转换成 MCP 要求的 JSON Schema 2020-12（EXE-05 / BR-1）。
 *
 * <p>MCP tool 的入参是<b>扁平</b>的参数对象，而 HTTP 接口的入参分散在 path / query / header / body 四处，
 * 因此这里做两件事：
 * <ol>
 *   <li>把四类入参合并成一个 object schema，并记录每个属性的来源位置（{@code parameterIn}），
 *       供 Executor 在调用上游时按位置还原（EXE-03）；</li>
 *   <li>把 OpenAPI 方言改写成 JSON Schema 2020-12：主要是 {@code nullable} → {@code type: [..., "null"]}，
 *       并剔除 {@code exampleSetFlag} 等序列化噪声。</li>
 * </ol>
 *
 * <p>命名冲突（同名 query 参数与 body 属性）不静默丢弃，而是保留 query 版本并给出 WARN 诊断，
 * 让开发者在覆盖编辑里改名（REG-01：解析问题必须可见）。
 */
@Component
public class JsonSchemaConverter {

    /** swagger-core 自带的 mapper 认识 OAS 的序列化约定，不能用平台的通用 mapper 替代。 */
    private static final ObjectMapper SWAGGER_MAPPER = io.swagger.v3.core.util.Json.mapper();

    private static final String SSE_MEDIA_TYPE = "text/event-stream";
    private static final String STREAM_JSON_MEDIA_TYPE = "application/stream+json";
    private static final String NDJSON_MEDIA_TYPE = "application/x-ndjson";

    /**
     * @param inputSchema         合并后的 JSON Schema 2020-12
     * @param parameterIn         属性名 → path/query/header/body
     * @param requestBodyRequired 上游是否强制请求体
     * @param streaming           是否流式
     * @param streamFormat        SSE / NDJSON / CHUNKED，非流式为 null
     * @param diagnostics         本 operation 的诊断
     */
    public record Conversion(
            JsonNode inputSchema,
            Map<String, String> parameterIn,
            boolean requestBodyRequired,
            boolean streaming,
            String streamFormat,
            List<RegistrationDtos.Diagnostic> diagnostics) {
    }

    public Conversion convert(Operation operation, String method, String path) {
        List<RegistrationDtos.Diagnostic> diagnostics = new ArrayList<>();
        String pointer = pointer(path, method);

        ObjectNode schema = Json.obj();
        schema.put("$schema", McpProtocol.JSON_SCHEMA_DIALECT);
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        Map<String, String> parameterIn = new LinkedHashMap<>();

        mergeParameters(operation, pointer, properties, required, parameterIn, diagnostics);
        boolean bodyRequired = mergeRequestBody(operation, pointer, properties, required, parameterIn, diagnostics);

        if (required.isEmpty()) {
            schema.remove("required");
        }
        if (properties.isEmpty()) {
            diagnostics.add(warn(pointer, "parameters", "该接口无入参，生成的 tool 入参为空对象"));
        }

        String streamFormat = detectStreamFormat(operation);
        return new Conversion(schema, parameterIn, bodyRequired,
                streamFormat != null, streamFormat, diagnostics);
    }

    private void mergeParameters(Operation operation,
                                 String pointer,
                                 ObjectNode properties,
                                 ArrayNode required,
                                 Map<String, String> parameterIn,
                                 List<RegistrationDtos.Diagnostic> diagnostics) {
        if (operation.getParameters() == null) {
            return;
        }
        for (Parameter parameter : operation.getParameters()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                diagnostics.add(warn(pointer, "parameters", "存在缺少 name 的参数，已跳过"));
                continue;
            }
            String name = parameter.getName();
            String location = parameter.getIn() == null ? "query" : parameter.getIn().toLowerCase(Locale.ROOT);
            if ("cookie".equals(location)) {
                // Cookie 属于会话态，与 MCP 无状态调用模型冲突（SEP-2567/2575），不映射为 tool 入参
                diagnostics.add(warn(pointer, name, "cookie 参数不映射为 MCP 入参，请改用上行授权配置携带"));
                continue;
            }
            if (properties.has(name)) {
                diagnostics.add(warn(pointer, name, "参数名与已有入参重复，保留先出现的那个"));
                continue;
            }
            properties.set(name, toDraft2020(parameter.getSchema(), parameter.getDescription()));
            parameterIn.put(name, location);
            if (Boolean.TRUE.equals(parameter.getRequired())) {
                required.add(name);
            }
            if ("path".equals(location) && !Boolean.TRUE.equals(parameter.getRequired())) {
                // path 参数在 HTTP 语义上必然必填（缺了就拼不出 URL）。swagger-models 的
                // Parameter.setIn("path") 会自动补 required=true，因此走到这里意味着文档显式写了
                // required: false —— 自相矛盾，一律按必填处理并提示
                required.add(name);
                diagnostics.add(warn(pointer, name, "path 参数未标记 required，已按必填处理"));
            }
        }
    }

    private boolean mergeRequestBody(Operation operation,
                                     String pointer,
                                     ObjectNode properties,
                                     ArrayNode required,
                                     Map<String, String> parameterIn,
                                     List<RegistrationDtos.Diagnostic> diagnostics) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null) {
            return false;
        }
        boolean bodyRequired = Boolean.TRUE.equals(requestBody.getRequired());
        MediaType mediaType = pickJsonMediaType(requestBody.getContent());
        if (mediaType == null) {
            if (requestBody.getContent() != null && !requestBody.getContent().isEmpty()) {
                diagnostics.add(warn(pointer, "requestBody",
                        "请求体不是 JSON 类型（" + String.join(",", requestBody.getContent().keySet())
                                + "），已按原始 body 透传处理"));
            }
            properties.set("body", emptyObjectSchema("请求体原文，将作为 body 透传给上游"));
            parameterIn.put("body", "body");
            if (bodyRequired) {
                required.add("body");
            }
            return bodyRequired;
        }

        ObjectNode bodyNode = toDraft2020(mediaType.getSchema(), null);
        JsonNode bodyProperties = bodyNode.get("properties");
        if (bodyProperties == null || !bodyProperties.isObject() || bodyProperties.isEmpty()) {
            // 数组、字符串、二进制等非标量 body：整体作为一个名为 body 的入参
            bodyNode.put("description", firstNonBlank(bodyNode.path("description").asText(null), "请求体"));
            properties.set("body", bodyNode);
            parameterIn.put("body", "body");
            if (bodyRequired) {
                required.add("body");
            }
            return bodyRequired;
        }

        List<String> bodyRequiredNames = new ArrayList<>();
        JsonNode requiredNode = bodyNode.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            requiredNode.forEach(n -> bodyRequiredNames.add(n.asText()));
        }
        Iterator<Map.Entry<String, JsonNode>> fields = bodyProperties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            if (properties.has(name)) {
                diagnostics.add(warn(pointer, name, "请求体字段与 path/query/header 参数同名，保留参数版本；"
                        + "如需同时传请改名"));
                continue;
            }
            properties.set(name, entry.getValue());
            parameterIn.put(name, "body");
            if (bodyRequiredNames.contains(name)) {
                required.add(name);
            }
        }
        return bodyRequired;
    }

    /**
     * 流式端点识别（BR-5）：按响应 Content-Type 判定，SSE 优先。
     *
     * @return SSE / NDJSON / CHUNKED，非流式返回 null
     */
    private String detectStreamFormat(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        String ndjson = null;
        String chunked = null;
        for (ApiResponse response : operation.getResponses().values()) {
            if (response == null || response.getContent() == null) {
                continue;
            }
            for (String contentType : response.getContent().keySet()) {
                String lower = contentType.toLowerCase(Locale.ROOT);
                if (lower.startsWith(SSE_MEDIA_TYPE)) {
                    return "SSE";
                }
                if (lower.contains("ndjson")) {
                    ndjson = "NDJSON";
                } else if (lower.startsWith(STREAM_JSON_MEDIA_TYPE) || lower.startsWith("application/grpc")) {
                    chunked = "CHUNKED";
                }
            }
        }
        if (ndjson != null) {
            return ndjson;
        }
        return chunked;
    }

    private static MediaType pickJsonMediaType(io.swagger.v3.oas.models.media.Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, MediaType> entry : content.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.startsWith("application/json") || key.endsWith("+json")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * OAS Schema → JSON Schema 2020-12 节点。递归改写方言差异，不修改入参对象。
     */
    private static ObjectNode toDraft2020(Schema<?> schema, String descriptionFallback) {
        ObjectNode node;
        if (schema == null) {
            node = Json.obj();
            node.put("type", "string");
        } else {
            JsonNode converted = SWAGGER_MAPPER.convertValue(schema, JsonNode.class);
            node = converted instanceof ObjectNode objectNode ? objectNode : Json.obj();
        }
        rewrite(node);
        if (descriptionFallback != null && !descriptionFallback.isBlank()
                && !node.hasNonNull("description")) {
            node.put("description", descriptionFallback);
        }
        return node;
    }

    private static void rewrite(ObjectNode node) {
        node.remove("exampleSetFlag");
        node.remove("discriminator");
        JsonNode nullable = node.remove("nullable");
        JsonNode type = node.get("type");
        if (nullable != null && nullable.asBoolean(false) && type != null && type.isTextual()) {
            ArrayNode union = Json.arr();
            union.add(type.asText());
            union.add("null");
            node.set("type", union);
        }
        // OpenAPI 的 exclusiveMaximum/Minimum 是布尔值，JSON Schema 2020-12 里是数值：无法等价表达时丢弃并保留边界值
        dropBooleanKeyword(node, "exclusiveMaximum");
        dropBooleanKeyword(node, "exclusiveMinimum");

        JsonNode properties = node.get("properties");
        if (properties instanceof ObjectNode propertyNode) {
            Iterator<JsonNode> values = propertyNode.elements();
            while (values.hasNext()) {
                JsonNode child = values.next();
                if (child instanceof ObjectNode childObject) {
                    rewrite(childObject);
                }
            }
        }
        rewriteChild(node, "items");
        rewriteChild(node, "additionalProperties");
        rewriteChild(node, "not");
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode array = node.get(key);
            if (array instanceof ArrayNode arrayNode) {
                for (JsonNode child : arrayNode) {
                    if (child instanceof ObjectNode childObject) {
                        rewrite(childObject);
                    }
                }
            }
        }
    }

    private static void rewriteChild(ObjectNode node, String field) {
        JsonNode child = node.get(field);
        if (child instanceof ObjectNode childObject) {
            rewrite(childObject);
        }
    }

    private static void dropBooleanKeyword(ObjectNode node, String keyword) {
        JsonNode value = node.get(keyword);
        if (value != null && value.isBoolean()) {
            node.remove(keyword);
        }
    }

    private static ObjectNode emptyObjectSchema(String description) {
        ObjectNode node = Json.obj();
        node.put("type", "object");
        node.put("description", description);
        return node;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    /** JSON Pointer（RFC 6901）形式的位置，前端可据此高亮文档中的出错节点。 */
    public static String pointer(String path, String method) {
        return "/paths/" + escapePointer(path) + "/" + method.toLowerCase(Locale.ROOT);
    }

    private static String escapePointer(String raw) {
        return raw == null ? "" : raw.replace("~", "~0").replace("/", "~1");
    }

    private static RegistrationDtos.Diagnostic warn(String pointer, String field, String message) {
        return new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_WARN, pointer, field, message);
    }
}