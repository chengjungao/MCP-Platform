package com.mcpbridge.manager.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.ToolNames;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.web.dto.RegistrationDtos;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Swagger 2.0 / OpenAPI 3.x 解析入口（REG-01 / REG-02）。
 *
 * <p>解析器只做「文档 → 结构化产物」，不落库、不做权限判断，因此可以纯单元测试覆盖（见 t11）。
 * 2.0 文档由 swagger-parser 的 v2-converter 先转成 OpenAPI 3 模型，后续流程与 3.x 完全一致。
 *
 * <p>失败语义：文档无法解析成模型时抛 {@link ErrorCode#PARSE_FAILED}，
 * details 里带解析器的原始 messages，前端按 JSON Pointer 定位错误（REG-01：不白屏）。
 */
@Service
public class SwaggerParseService {

    private static final Logger log = LoggerFactory.getLogger(SwaggerParseService.class);

    private final ManagerProperties properties;
    private final JsonSchemaConverter schemaConverter;

    public SwaggerParseService(ManagerProperties properties, JsonSchemaConverter schemaConverter) {
        this.properties = properties;
        this.schemaConverter = schemaConverter;
    }

    public ParsedApi parse(String rawDoc) {
        if (rawDoc == null || rawDoc.isBlank()) {
            throw PlatformException.validation("接口文档内容为空", Map.of("field", "rawDoc"));
        }
        byte[] bytes = rawDoc.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > properties.parse().maxDocumentBytes()) {
            throw PlatformException.validation("接口文档超过大小上限",
                    Map.of("limitBytes", properties.parse().maxDocumentBytes(), "actualBytes", bytes.length));
        }

        String specVersion = detectSpecVersion(rawDoc);
        SwaggerParseResult result = new OpenAPIParser().readContents(rawDoc, null, parseOptions());
        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        OpenAPI openApi = result.getOpenAPI();
        if (openApi == null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("specVersion", specVersion);
            details.put("messages", messages);
            throw new PlatformException(ErrorCode.PARSE_FAILED, "接口文档解析失败，请检查文档格式", details);
        }

        List<RegistrationDtos.Diagnostic> diagnostics = new ArrayList<>();
        messages.stream()
                .filter(m -> m != null && !m.isBlank())
                .forEach(m -> diagnostics.add(new RegistrationDtos.Diagnostic(
                        ParsedApi.LEVEL_WARN, null, null, m)));

        String title = openApi.getInfo() == null || openApi.getInfo().getTitle() == null
                ? "未命名服务" : openApi.getInfo().getTitle().trim();
        String description = openApi.getInfo() == null ? null : openApi.getInfo().getDescription();
        String version = openApi.getInfo() == null || openApi.getInfo().getVersion() == null
                ? "1.0.0" : openApi.getInfo().getVersion().trim();

        List<String> baseUrls = collectBaseUrls(openApi, diagnostics);
        List<ParsedOperation> operations = collectOperations(openApi, diagnostics);

        if (operations.isEmpty()) {
            diagnostics.add(new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_ERROR, "/paths", "paths",
                    "文档中没有任何可暴露的接口（paths 为空或全部被过滤）"));
        }
        log.info("解析完成：spec={} title={} operations={} diagnostics={}",
                specVersion, title, operations.size(), diagnostics.size());
        return new ParsedApi(specVersion, title, description, version, baseUrls, operations, diagnostics);
    }

    /** 判定规范版本：有 {@code swagger} 字段即 2.0，否则取 {@code openapi} 字段。 */
    private String detectSpecVersion(String rawDoc) {
        try {
            JsonNode root = rawDoc.trim().startsWith("{") ? Json.tree(rawDoc) : readYaml(rawDoc);
            if (root == null) {
                return "unknown";
            }
            if (root.hasNonNull("swagger")) {
                return root.get("swagger").asText();
            }
            if (root.hasNonNull("openapi")) {
                return root.get("openapi").asText();
            }
        } catch (RuntimeException e) {
            log.debug("规范版本预检失败，交由解析器给出权威错误", e);
        }
        return "unknown";
    }

    private static JsonNode readYaml(String rawDoc) {
        try {
            return new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(rawDoc);
        } catch (Exception e) {
            return null;
        }
    }

    private ParseOptions parseOptions() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // resolveFully 会把 $ref 全部内联：生成的 schema 自包含，Executor 侧无需二次解析
        options.setResolveFully(properties.parse().resolveFully());
        options.setResolveCombinators(false);
        options.setResolveRequestBody(true);
        options.setFlatten(false);
        return options;
    }

    private List<String> collectBaseUrls(OpenAPI openApi, List<RegistrationDtos.Diagnostic> diagnostics) {
        List<String> urls = new ArrayList<>();
        if (openApi.getServers() != null) {
            for (Server server : openApi.getServers()) {
                if (server != null && server.getUrl() != null && !server.getUrl().isBlank()) {
                    String url = stripTrailingSlash(server.getUrl().trim());
                    // 含变量的 server url 无法直接作为上游地址，跳过并提示在「上游配置」里补齐
                    if (url.contains("{")) {
                        diagnostics.add(new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_WARN, "/servers", "url",
                                "server url 含变量占位符（" + url + "），已跳过，请在发布前配置上游地址"));
                        continue;
                    }
                    urls.add(url);
                }
            }
        }
        if (urls.isEmpty()) {
            diagnostics.add(new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_WARN, "/servers", "url",
                    "文档未声明可用的上游地址，发布前必须在上游配置中补齐 baseUrls"));
        }
        return urls;
    }

    private List<ParsedOperation> collectOperations(OpenAPI openApi, List<RegistrationDtos.Diagnostic> diagnostics) {
        List<ParsedOperation> operations = new ArrayList<>();
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return operations;
        }
        Set<String> usedNames = new HashSet<>();
        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null || pathItem.readOperationsMap() == null) {
                continue;
            }
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                Operation operation = opEntry.getValue();
                if (operation == null) {
                    continue;
                }
                String method = opEntry.getKey().name().toUpperCase(Locale.ROOT);
                String pointer = JsonSchemaConverter.pointer(path, method);
                JsonSchemaConverter.Conversion conversion = schemaConverter.convert(operation, method, path);
                diagnostics.addAll(conversion.diagnostics());

                String operationId = operation.getOperationId();
                if (operationId == null || operationId.isBlank()) {
                    diagnostics.add(new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_WARN, pointer, "operationId",
                            "缺少 operationId，tool 名已按 method+path 推导，建议在文档中补齐以提升可读性"));
                }
                String candidate = ToolNames.derive(operationId, method, path);
                String name = ToolNames.unique(candidate, usedNames);
                usedNames.add(name);
                if (!name.equals(candidate)) {
                    diagnostics.add(new RegistrationDtos.Diagnostic(ParsedApi.LEVEL_WARN, pointer, "operationId",
                            "tool 名 " + candidate + " 与同文档内其他接口重复，已改为 " + name));
                }

                operations.add(new ParsedOperation(
                        method,
                        path,
                        ToolNames.anchor(method, path),
                        name,
                        operation.getSummary(),
                        firstNonBlank(operation.getDescription(), operation.getSummary()),
                        conversion.inputSchema(),
                        conversion.parameterIn(),
                        conversion.requestBodyRequired(),
                        conversion.streaming(),
                        conversion.streamFormat(),
                        ToolSnapshotIdempotence.of(method),
                        operation.getTags() == null ? List.of() : List.copyOf(operation.getTags()),
                        conversion.diagnostics()));
            }
        }
        return operations;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}