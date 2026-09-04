package com.mcpbridge.executor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.jsonrpc.JsonRpcRequest;
import com.mcpbridge.common.jsonrpc.JsonRpcResponse;
import com.mcpbridge.common.protocol.McpMethods;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.PromptSnapshot;
import com.mcpbridge.common.snapshot.ResourceSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.executor.config.ExecutorProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 方法分派（2026-07-28）。
 *
 * <p>只做三件事：按方法名分发、从发布快照里取数据、把结果包成 JSON-RPC {@code result}。
 * 上游调用委托给 {@link ToolCallService}，协议合法性由 {@link ProtocolGuard} 在进入这里之前保证，
 * 因此本类可以假定「server 存在、协议是 Modern、信封合法」。
 *
 * <p>失败一律<b>抛</b> {@link McpErrorException} 而不是返回 {@code JsonRpcResponse.error(...)}：
 * 错误码到 HTTP 状态码的映射集中在异常里，控制器只需一个出口就能把两者同时对齐。
 */
@Service
public class McpDispatcher {

    /** Prompt 模板占位符 {@code {{argName}}}。 */
    private static final Pattern PROMPT_ARGUMENT = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private final ExecutorProperties properties;
    private final ProtocolGuard guard;
    private final ToolCallService toolCallService;

    public McpDispatcher(ExecutorProperties properties,
                         ProtocolGuard guard,
                         ToolCallService toolCallService) {
        this.properties = properties;
        this.guard = guard;
        this.toolCallService = toolCallService;
    }

    public Mono<JsonRpcResponse> dispatch(ServerSnapshot server,
                                          String method,
                                          JsonRpcRequest request,
                                          HttpHeaders headers) {
        String resolved = method == null ? "" : method;
        return switch (resolved) {
            case McpMethods.PING -> Mono.just(JsonRpcResponse.ok(request.id(), Json.obj()));
            case McpMethods.SERVER_DISCOVER -> Mono.just(JsonRpcResponse.ok(request.id(), discover(server)));
            case McpMethods.TOOLS_LIST -> Mono.just(JsonRpcResponse.ok(request.id(), listTools(server, request)));
            case McpMethods.TOOLS_CALL ->
                    callTool(server, request, headers).map(result -> JsonRpcResponse.ok(request.id(), result));
            case McpMethods.RESOURCES_LIST -> Mono.just(JsonRpcResponse.ok(request.id(), listResources(server)));
            case McpMethods.RESOURCES_READ ->
                    readResource(server, request).map(result -> JsonRpcResponse.ok(request.id(), result));
            case McpMethods.PROMPTS_LIST -> Mono.just(JsonRpcResponse.ok(request.id(), listPrompts(server)));
            case McpMethods.PROMPTS_GET ->
                    getPrompt(server, request).map(result -> JsonRpcResponse.ok(request.id(), result));
            default -> throw unsupported(server, resolved);
        };
    }

    // ------------------------------------------------------------------ server/discover

    private ObjectNode discover(ServerSnapshot server) {
        ObjectNode result = Json.obj();
        result.put("name", server.pathSegment());
        putIfPresent(result, "title", server.title());
        putIfPresent(result, "description", server.description());
        putIfPresent(result, "version", server.version());
        result.put("protocolVersion", McpProtocol.SUPPORTED_VERSION);
        putIfPresent(result, "endpoint", server.endpoint());

        ObjectNode capabilities = result.putObject("capabilities");
        // listChanged 一律为 false：平台无会话、无服务端推送（SEP-2567），
        // 客户端靠 tools/list 响应里的 ttlMs 决定何时重新拉取
        capabilities.putObject("tools").put("listChanged", false);
        if (!safeResources(server).isEmpty()) {
            capabilities.putObject("resources").put("listChanged", false);
        }
        if (!safePrompts(server).isEmpty()) {
            capabilities.putObject("prompts").put("listChanged", false);
        }
        result.put("ttlMs", ttlMs(server));
        return result;
    }

    // ------------------------------------------------------------------ tools

    private ObjectNode listTools(ServerSnapshot server, JsonRpcRequest request) {
        String cursor = request.paramText("cursor");
        ObjectNode result = Json.obj();
        ArrayNode tools = result.putArray("tools");
        for (ToolSnapshot tool : server.safeTools()) {
            if (tool.streaming()) {
                // 流式 tool 属于 P1（BR-5）。列出来却调不通比不列更糟——客户端会把它交给模型，
                // 然后模型每次都失败。这里直接从清单中剔除，并在 tools/call 上给出明确原因。
                continue;
            }
            ObjectNode entry = tools.addObject();
            entry.put("name", tool.name());
            putIfPresent(entry, "title", tool.title());
            putIfPresent(entry, "description", tool.description());
            if (tool.inputSchema() != null) {
                entry.set("inputSchema", tool.inputSchema());
            } else {
                // 无参操作也要给出合法的空 schema，否则严格的客户端会拒绝这个 tool
                entry.set("inputSchema", emptyObjectSchema());
            }
        }
        result.put("ttlMs", ttlMs(server));
        if (cursor != null && !cursor.isBlank()) {
            // P0 不分页：一个集群内单 Server 的 tool 数上限是几百，一次返回比分页游标更简单可靠。
            // 明确不回 nextCursor 表示「清单已完整」。
            result.put("nextCursor", "");
        }
        return result;
    }

    private Mono<ObjectNode> callTool(ServerSnapshot server, JsonRpcRequest request, HttpHeaders headers) {
        String toolName = guard.resolveToolName(headers, request);
        if (toolName == null || toolName.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "tools/call 缺少 tool 名：请通过 Mcp-Name 头或 params.name 指定");
        }
        ToolSnapshot tool = server.tool(toolName).orElseThrow(() -> McpErrorException.of(
                HttpStatus.NOT_FOUND.value(), JsonRpcErrorCodes.TOOL_NOT_FOUND,
                "tool " + toolName + " 在 server " + server.pathSegment() + " 下不存在或未启用",
                Map.of("tool", toolName,
                        "pathSegment", nullSafe(server.pathSegment()),
                        "availableToolCount", server.safeTools().size())));
        if (tool.streaming()) {
            throw McpErrorException.of(HttpStatus.NOT_IMPLEMENTED.value(), JsonRpcErrorCodes.TOOL_NOT_FOUND,
                    "流式端点属于 P1 能力，当前版本不支持调用",
                    Map.of("tool", toolName,
                            "streamFormat", nullSafe(tool.streamFormat()),
                            "hint", "请在覆盖配置中关闭该操作，或等待 P1 流式支持"));
        }
        return toolCallService.call(server, tool, request.param("arguments"));
    }

    // ------------------------------------------------------------------ resources

    private ObjectNode listResources(ServerSnapshot server) {
        ObjectNode result = Json.obj();
        ArrayNode resources = result.putArray("resources");
        for (ResourceSnapshot resource : safeResources(server)) {
            ObjectNode entry = resources.addObject();
            entry.put("uri", nullSafe(resource.uri()));
            putIfPresent(entry, "name", resource.name());
            putIfPresent(entry, "description", resource.description());
            putIfPresent(entry, "mimeType", resource.mimeType());
        }
        result.put("ttlMs", ttlMs(server));
        return result;
    }

    private Mono<ObjectNode> readResource(ServerSnapshot server, JsonRpcRequest request) {
        String uri = request.paramText("uri");
        if (uri == null || uri.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "resources/read 缺少 params.uri");
        }
        ResourceSnapshot resource = safeResources(server).stream()
                .filter(candidate -> uri.equals(candidate.uri()))
                .findFirst()
                .orElseThrow(() -> McpErrorException.of(HttpStatus.NOT_FOUND.value(),
                        JsonRpcErrorCodes.INVALID_PARAMS,
                        "资源 " + uri + " 在 server " + server.pathSegment() + " 下不存在",
                        Map.of("uri", uri, "pathSegment", nullSafe(server.pathSegment()))));

        if (resource.isStatic()) {
            return Mono.just(resourceContents(resource, resource.content()));
        }
        String toolName = resource.toolName();
        if (toolName == null || toolName.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                    "资源 " + uri + " 既没有静态内容也没有映射 tool，无法读取",
                    Map.of("uri", uri));
        }
        ToolSnapshot tool = server.tool(toolName).orElseThrow(() -> McpErrorException.of(
                HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                "资源 " + uri + " 映射的 tool " + toolName + " 不存在（可能是覆盖配置把它禁用了）",
                Map.of("uri", uri, "tool", toolName)));
        return toolCallService.call(server, tool, null)
                .map(callResult -> resourceContents(resource, firstText(callResult)));
    }

    private static ObjectNode resourceContents(ResourceSnapshot resource, String text) {
        ObjectNode result = Json.obj();
        ArrayNode contents = result.putArray("contents");
        ObjectNode entry = contents.addObject();
        entry.put("uri", nullSafe(resource.uri()));
        entry.put("mimeType", resource.mimeType() == null ? "text/plain" : resource.mimeType());
        entry.put("text", text == null ? "" : text);
        return result;
    }

    // ------------------------------------------------------------------ prompts

    private ObjectNode listPrompts(ServerSnapshot server) {
        ObjectNode result = Json.obj();
        ArrayNode prompts = result.putArray("prompts");
        for (PromptSnapshot prompt : safePrompts(server)) {
            ObjectNode entry = prompts.addObject();
            entry.put("name", nullSafe(prompt.name()));
            putIfPresent(entry, "title", prompt.title());
            putIfPresent(entry, "description", prompt.description());
            ArrayNode arguments = entry.putArray("arguments");
            for (PromptSnapshot.Argument argument : prompt.arguments() == null ? List.<PromptSnapshot.Argument>of()
                    : prompt.arguments()) {
                ObjectNode item = arguments.addObject();
                item.put("name", nullSafe(argument.name()));
                putIfPresent(item, "description", argument.description());
                item.put("required", argument.required());
            }
        }
        result.put("ttlMs", ttlMs(server));
        return result;
    }

    private Mono<ObjectNode> getPrompt(ServerSnapshot server, JsonRpcRequest request) {
        String name = request.paramText("name");
        if (name == null || name.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "prompts/get 缺少 params.name");
        }
        PromptSnapshot prompt = safePrompts(server).stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> McpErrorException.of(HttpStatus.NOT_FOUND.value(),
                        JsonRpcErrorCodes.INVALID_PARAMS,
                        "Prompt " + name + " 在 server " + server.pathSegment() + " 下不存在",
                        Map.of("name", name)));

        Map<String, String> arguments = new LinkedHashMap<>();
        JsonNode provided = request.param("arguments");
        if (provided != null && provided.isObject()) {
            provided.fields().forEachRemaining(entry ->
                    arguments.put(entry.getKey(), entry.getValue() == null || entry.getValue().isNull()
                            ? "" : entry.getValue().asText()));
        }
        List<String> missing = prompt.arguments() == null ? List.of() : prompt.arguments().stream()
                .filter(PromptSnapshot.Argument::required)
                .map(PromptSnapshot.Argument::name)
                .filter(argument -> arguments.get(argument) == null || arguments.get(argument).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "缺少必填 prompt 参数：" + String.join(", ", missing),
                    Map.of("prompt", name, "missing", missing));
        }

        ObjectNode result = Json.obj();
        putIfPresent(result, "description", prompt.description());
        ArrayNode messages = result.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", render(prompt.template(), arguments));
        return Mono.just(result);
    }

    /** 未提供的占位符替换成空串而不是原样保留：{@code {{orderId}}} 出现在最终提示词里会误导模型。 */
    private static String render(String template, Map<String, String> arguments) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = PROMPT_ARGUMENT.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(arguments.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    // ------------------------------------------------------------------ 公共片段

    private RuntimeException unsupported(ServerSnapshot server, String method) {
        if (McpMethods.isLegacy(method)) {
            // 正常路径下 ProtocolGuard 已经拦掉了；这里是防御性兜底，
            // 保证「守卫被绕过」时仍然给出可诊断的 -32022 而不是含糊的 -32601
            return new LegacyProtocolException(null,
                    "请求使用了 legacy 方法 " + method + "；2026-07-28 已取消 initialize 握手");
        }
        return McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.METHOD_NOT_FOUND,
                "不支持的方法：" + method,
                Map.of("method", method,
                        "pathSegment", nullSafe(server.pathSegment()),
                        "protocolVersion", McpProtocol.SUPPORTED_VERSION));
    }

    /** list 类响应的缓存 TTL（SEP-2549）：Server 未配置时回落到节点默认值。 */
    int ttlMs(ServerSnapshot server) {
        return server.listTtlMs() > 0 ? server.listTtlMs() : properties.protocol().defaultListTtlMs();
    }

    private static List<ResourceSnapshot> safeResources(ServerSnapshot server) {
        return server.resources() == null ? List.of() : server.resources();
    }

    private static List<PromptSnapshot> safePrompts(ServerSnapshot server) {
        return server.prompts() == null ? List.of() : server.prompts();
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = Json.obj();
        schema.put("$schema", McpProtocol.JSON_SCHEMA_DIALECT);
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static String firstText(ObjectNode callResult) {
        return callResult.path("content").path(0).path("text").asText("");
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}