package com.mcpbridge.executor.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.executor.mcp.McpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 MCP {@code tools/call} 的 arguments 装配成一次上游 REST 调用（EXE-02）。
 *
 * <p>参数去向由发布快照里的 {@code parameterIn} 决定，它来自 Swagger 的 {@code in} 字段
 * （path/query/header/cookie）与请求体建模。这里有三条规则值得说明：
 *
 * <ol>
 *   <li><b>缺省位置按方法推断</b>：{@code parameterIn} 没覆盖到的参数，
 *       GET/HEAD/DELETE/OPTIONS 进查询串，POST/PUT/PATCH 进请求体。
 *       这不是偷懒——Swagger 里 {@code requestBody} 内的字段本就没有 {@code in}，
 *       逐个标注会让注册解析的结果比原文档还啰嗦。</li>
 *   <li><b>名为 {@code body} 的参数代表整个请求体</b>。当上游请求体是一个不可拆的整体
 *       （例如透传一段第三方 JSON）时，注册解析会生成这样一个参数；
 *       其余映射到 body 的参数则被聚合成一个 JSON 对象。</li>
 *   <li><b>路径参数缺失是硬错误</b>：{@code /users/{id}} 少了 id 就不可能拼出合法的 URL，
 *       与其打一个 {@code /users/null} 出去让上游回一个莫名其妙的 404，
 *       不如直接回 -32602 告诉调用方少传了什么。</li>
 * </ol>
 *
 * <p>只做「必填」这一层轻量校验，<b>不引入 JSON Schema 校验库</b>：完整的类型/格式校验
 * 交给上游，理由是上游的错误信息更准确，而且引入校验器会带来「平台与上游对同一份 schema
 * 理解不一致」这类极难排查的问题。
 */
@Component
public class RestRequestBuilder {

    private static final Logger log = LoggerFactory.getLogger(RestRequestBuilder.class);

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^{}]+)}");

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    public RestRequest build(ServerSnapshot server, ToolSnapshot tool, JsonNode arguments) {
        String method = tool.method() == null ? "GET" : tool.method().toUpperCase(Locale.ROOT);
        Map<String, Object> args = toArguments(tool, arguments);
        requireAllPresent(tool, args);

        Map<String, String> parameterIn = tool.parameterIn() == null ? Map.of() : tool.parameterIn();
        String defaultLocation = BODY_METHODS.contains(method) ? "body" : "query";

        Map<String, Object> pathValues = new LinkedHashMap<>();
        List<RestRequest.QueryParam> query = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> cookies = new ArrayList<>();
        Map<String, Object> bodyFields = new LinkedHashMap<>();
        Object wholeBody = null;
        boolean wholeBodyPresent = false;

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            String location = parameterIn
                    .getOrDefault(name, defaultLocation)
                    .toLowerCase(Locale.ROOT);
            switch (location) {
                case "path" -> pathValues.put(name, value);
                case "query" -> query.add(new RestRequest.QueryParam(name, stringify(value)));
                case "header" -> headers.put(name, stringify(value));
                case "cookie" -> cookies.add(name + "=" + stringify(value));
                case "body" -> {
                    if ("body".equals(name)) {
                        wholeBody = value;
                        wholeBodyPresent = true;
                    } else {
                        bodyFields.put(name, value);
                    }
                }
                default -> {
                    log.warn("server={} tool={} 的参数 {} 声明了未知位置 \"{}\"，按 {} 处理",
                            server.pathSegment(), tool.name(), name, location, defaultLocation);
                    if ("query".equals(defaultLocation)) {
                        query.add(new RestRequest.QueryParam(name, stringify(value)));
                    } else {
                        bodyFields.put(name, value);
                    }
                }
            }
        }
        if (!cookies.isEmpty()) {
            headers.put("Cookie", String.join("; ", cookies));
        }

        String path = substitutePath(server, tool, pathValues);
        Object body = wholeBodyPresent ? wholeBody : (bodyFields.isEmpty() ? null : bodyFields);
        if (tool.requestBodyRequired() && body == null) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "上游接口要求请求体，但本次调用没有可映射为请求体的参数",
                    Map.of("tool", tool.name(), "method", method));
        }
        return new RestRequest(method, path, List.copyOf(query), Map.copyOf(headers), body);
    }

    /**
     * 替换路径模板里的 {@code {name}}。
     *
     * <p>值在这里就完成百分号编码，{@code UpstreamInvoker} 拿到的是「已经是最终形态」的路径，
     * 因此拼接 URI 时不需要再编码一次。双重编码会把 {@code %} 变成 {@code %25}，
     * 这类 bug 在测试里很难被发现（普通字母数字不受影响），只在传中文或空格时才炸。
     */
    private String substitutePath(ServerSnapshot server, ToolSnapshot tool, Map<String, Object> pathValues) {
        String template = tool.path() == null ? "" : tool.path();
        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder path = new StringBuilder();
        Set<String> used = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = pathValues.get(name);
            if (value == null) {
                throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                        "缺少路径参数 " + name,
                        Map.of("tool", tool.name(), "pathTemplate", template, "missing", name));
            }
            used.add(name);
            matcher.appendReplacement(path, Matcher.quoteReplacement(encodePathSegment(stringify(value))));
        }
        matcher.appendTail(path);

        pathValues.keySet().stream()
                .filter(name -> !used.contains(name))
                .forEach(name -> log.warn(
                        "server={} tool={} 收到路径参数 {}，但路径模板 {} 中没有对应占位符（可能是文档漂移）",
                        server.pathSegment(), tool.name(), name, template));

        String result = path.toString();
        return result.startsWith("/") ? result : "/" + result;
    }

    /** 按 {@code inputSchema.required} 做必填校验，一次性报全所有缺失项。 */
    private void requireAllPresent(ToolSnapshot tool, Map<String, Object> args) {
        JsonNode schema = tool.inputSchema();
        if (schema == null) {
            return;
        }
        JsonNode required = schema.get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode node : required) {
            if (node.isTextual() && args.get(node.asText()) == null) {
                missing.add(node.asText());
            }
        }
        if (!missing.isEmpty()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "缺少必填参数：" + String.join(", ", missing),
                    Map.of("tool", tool.name(), "missing", missing));
        }
    }

    private Map<String, Object> toArguments(ToolSnapshot tool, JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return Map.of();
        }
        if (!arguments.isObject()) {
            throw McpErrorException.of(HttpStatus.BAD_REQUEST.value(), JsonRpcErrorCodes.INVALID_PARAMS,
                    "params.arguments 必须是 JSON 对象",
                    Map.of("tool", tool.name(), "actualType", arguments.getNodeType().name()));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        arguments.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(),
                    value == null || value.isNull() ? null : Json.MAPPER.convertValue(value, Object.class));
        });
        return args;
    }

    /** 标量直接取字符串，对象/数组序列化成 JSON 文本（查询串里传结构化参数的唯一可行方式）。 */
    static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return Json.write(value);
    }

    /**
     * 路径分段编码。
     *
     * <p>{@link URLEncoder} 是表单编码，有两个必须纠正的行为：空格编成 {@code +}（路径里
     * {@code +} 是字面加号，不是空格），以及它不认识路径分段边界。这里只对单个分段调用，
     * 因此分段内的 {@code /} 被编成 {@code %2F} 恰恰是期望结果——一个 id 里带斜杠时，
     * 不能让它把上游路径结构改掉。
     */
    static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}