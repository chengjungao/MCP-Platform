package com.mcpbridge.manager.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.manager.web.dto.RegistrationDtos;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger → JSON Schema 2020-12 转换（REG-01 / BR-1）。
 *
 * <p>这是整个平台「一次注册、长期可用」的地基：HTTP 入参分散在 path/query/header/body 四处，
 * 而 MCP tool 的入参必须是一个扁平对象。转换错一处，Executor 就会把参数发到错误的位置，
 * 而这种错误在上游看来只是「参数缺失」，极难回溯到注册环节。
 *
 * <p>测试里的 schema 一律用 swagger 自己的 mapper 从 JSON 反序列化得到，
 * 而不是手工 new 对象——这样得到的是与真实解析文档完全一致的对象形态。
 */
class JsonSchemaConverterTest {

    private final JsonSchemaConverter converter = new JsonSchemaConverter();

    @Test
    @DisplayName("path/query/header 参数被扁平化，并各自记录来源位置")
    void flattensParametersAndRecordsLocation() {
        Operation operation = new Operation()
                .addParametersItem(new PathParameter().name("orderId").required(true).schema(schema("{\"type\":\"string\"}")))
                .addParametersItem(new QueryParameter().name("status").schema(schema("{\"type\":\"string\"}")))
                .addParametersItem(new HeaderParameter().name("X-Tenant").schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/orders/{orderId}");

        assertThat(propertyNames(conversion)).containsExactlyInAnyOrder("orderId", "status", "X-Tenant");
        assertThat(conversion.parameterIn())
                .containsEntry("orderId", "path")
                .containsEntry("status", "query")
                .containsEntry("X-Tenant", "header");
        assertThat(required(conversion)).containsExactly("orderId");
        assertThat(conversion.inputSchema().get("$schema").asText()).isEqualTo(McpProtocol.JSON_SCHEMA_DIALECT);
        assertThat(conversion.inputSchema().get("type").asText()).isEqualTo("object");
        assertThat(conversion.requestBodyRequired()).isFalse();
        assertThat(conversion.streaming()).isFalse();
        assertThat(conversion.streamFormat()).isNull();
    }

    @Test
    @DisplayName("path 参数被文档显式标为可选时按必填处理并给出 WARN")
    void forcesPathParameterRequired() {
        // 注意：swagger-models 的 Parameter.setIn("path") 会自动把 required 置为 true，
        // 因此「path 参数非必填」只可能来自文档里显式写了 required: false。
        // 这种文档在上游看来是自相矛盾的（路径变量缺了就拼不出 URL），所以必须强制拉回必填并提醒。
        Operation operation = new Operation()
                .addParametersItem(new Parameter().in("path").name("id").required(false)
                        .schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x/{id}");

        assertThat(required(conversion)).contains("id");
        assertThat(diagnosticFields(conversion)).contains("id");
        assertThat(conversion.diagnostics())
                .anyMatch(d -> d.level().equals(ParsedApi.LEVEL_WARN) && d.message().contains("path"));
    }

    @Test
    @DisplayName("swagger 的 PathParameter 自带 required=true，不会被误判为漏标")
    void doesNotWarnForWellFormedPathParameter() {
        Operation operation = new Operation()
                .addParametersItem(new PathParameter().name("id").schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x/{id}");

        assertThat(required(conversion)).containsExactly("id");
        assertThat(conversion.diagnostics())
                .noneMatch(d -> d.message().contains("未标记 required"));
    }

    @Test
    @DisplayName("cookie 参数不映射为 tool 入参（与无状态模型冲突），只给 WARN")
    void skipsCookieParameter() {
        Operation operation = new Operation()
                .addParametersItem(new CookieParameter().name("SESSION").schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x");

        assertThat(propertyNames(conversion)).doesNotContain("SESSION");
        assertThat(conversion.parameterIn()).doesNotContainKey("SESSION");
        assertThat(conversion.diagnostics()).anyMatch(d -> d.message().contains("cookie"));
    }

    @Test
    @DisplayName("参数重名时保留先出现的，不静默丢弃另一个")
    void keepsFirstOnDuplicateParameterName() {
        Operation operation = new Operation()
                .addParametersItem(new QueryParameter().name("status").description("查询参数版")
                        .schema(schema("{\"type\":\"string\"}")))
                .addParametersItem(new HeaderParameter().name("status").description("头版")
                        .schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x");

        assertThat(propertyNames(conversion)).containsExactly("status");
        assertThat(conversion.parameterIn()).containsEntry("status", "query");
        assertThat(conversion.diagnostics()).anyMatch(d -> d.message().contains("重复"));
    }

    @Test
    @DisplayName("JSON 请求体的字段被拍平进同一个入参对象，位置标记为 body")
    void flattensJsonRequestBodyFields() {
        RequestBody requestBody = new RequestBody()
                .required(true)
                .content(new Content().addMediaType("application/json", new MediaType().schema(schema(
                        "{\"type\":\"object\",\"required\":[\"amount\"],"
                                + "\"properties\":{\"amount\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}}}"))));
        Operation operation = new Operation().requestBody(requestBody);

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "POST", "/orders");

        assertThat(conversion.requestBodyRequired()).isTrue();
        assertThat(propertyNames(conversion)).containsExactlyInAnyOrder("amount", "note");
        assertThat(conversion.parameterIn())
                .containsEntry("amount", "body")
                .containsEntry("note", "body");
        assertThat(required(conversion)).contains("amount");
    }

    @Test
    @DisplayName("请求体是数组时整体作为一个名为 body 的入参，而不是被拆散")
    void wrapsArrayRequestBodyAsSingleBodyParam() {
        RequestBody requestBody = new RequestBody()
                .required(true)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(schema("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"))));
        Operation operation = new Operation().requestBody(requestBody);

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "POST", "/tags");

        assertThat(propertyNames(conversion)).containsExactly("body");
        assertThat(conversion.parameterIn()).containsEntry("body", "body");
        assertThat(required(conversion)).contains("body");
        assertThat(properties(conversion).get("body").get("type").asText()).isEqualTo("array");
    }

    @Test
    @DisplayName("非 JSON 请求体按原文透传为 body 入参，并提示实际内容类型")
    void wrapsNonJsonRequestBodyAsRawBody() {
        RequestBody requestBody = new RequestBody()
                .required(true)
                .content(new Content().addMediaType("text/xml", new MediaType().schema(schema("{\"type\":\"string\"}"))));
        Operation operation = new Operation().requestBody(requestBody);

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "POST", "/legacy");

        assertThat(propertyNames(conversion)).containsExactly("body");
        assertThat(conversion.parameterIn()).containsEntry("body", "body");
        assertThat(conversion.requestBodyRequired()).isTrue();
        assertThat(conversion.diagnostics()).anyMatch(d -> d.message().contains("text/xml"));
    }

    @Test
    @DisplayName("请求体字段与查询参数同名时保留参数版本，并提示改名")
    void keepsParameterVersionOnBodyFieldCollision() {
        Operation operation = new Operation()
                .addParametersItem(new QueryParameter().name("status").schema(schema("{\"type\":\"string\"}")))
                .requestBody(new RequestBody().required(false).content(
                        new Content().addMediaType("application/json", new MediaType().schema(schema(
                                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"integer\"}}}")))));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "POST", "/orders");

        assertThat(conversion.parameterIn()).containsEntry("status", "query");
        assertThat(properties(conversion).get("status").get("type").asText()).isEqualTo("string");
        assertThat(conversion.diagnostics()).anyMatch(d -> d.message().contains("同名"));
    }

    @Test
    @DisplayName("没有任何必填项时移除空的 required 关键字，避免严格客户端报错")
    void removesEmptyRequiredKeyword() {
        Operation operation = new Operation()
                .addParametersItem(new QueryParameter().name("status").schema(schema("{\"type\":\"string\"}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/orders");

        assertThat(conversion.inputSchema().has("required")).isFalse();
    }

    @Test
    @DisplayName("无入参接口仍产出合法的 object schema，并给出 WARN")
    void yieldsEmptyObjectSchemaForParameterlessOperation() {
        JsonSchemaConverter.Conversion conversion = converter.convert(new Operation(), "GET", "/ping");

        assertThat(conversion.inputSchema().get("type").asText()).isEqualTo("object");
        assertThat(propertyNames(conversion)).isEmpty();
        assertThat(conversion.diagnostics()).anyMatch(d -> d.message().contains("无入参"));
    }

    @Test
    @DisplayName("OpenAPI 的 nullable 被改写成 2020-12 的联合类型，且原关键字被移除")
    void rewritesNullableToUnionType() {
        Operation operation = new Operation()
                .addParametersItem(new QueryParameter().name("note")
                        .schema(schema("{\"type\":\"string\",\"nullable\":true}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x");

        JsonNode note = properties(conversion).get("note");
        assertThat(note.has("nullable")).isFalse();
        JsonNode type = note.get("type");
        assertThat(type.isArray()).isTrue();
        List<String> types = new ArrayList<>();
        type.forEach(n -> types.add(n.asText()));
        assertThat(types).containsExactlyInAnyOrder("string", "null");
    }

    @Test
    @DisplayName("OpenAPI 布尔形式的 exclusiveMaximum 不会以布尔值出现在结果里")
    void dropsBooleanExclusiveMaximum() {
        Operation operation = new Operation()
                .addParametersItem(new QueryParameter().name("limit")
                        .schema(schema("{\"type\":\"integer\",\"maximum\":10,\"exclusiveMaximum\":true}")));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/x");

        JsonNode limit = properties(conversion).get("limit");
        JsonNode exclusive = limit.get("exclusiveMaximum");
        // 2020-12 里 exclusiveMaximum 必须是数值：无法等价表达时宁可丢弃，也不能留下一个非法的布尔值
        assertThat(exclusive == null || !exclusive.isBoolean()).isTrue();
        assertThat(limit.get("maximum").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("响应为 text/event-stream 时判定为 SSE 流式端点（BR-5）")
    void detectsSseStreamFormat() {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok")
                        .content(new Content().addMediaType("text/event-stream", new MediaType()))));

        JsonSchemaConverter.Conversion conversion = converter.convert(operation, "GET", "/events");

        assertThat(conversion.streaming()).isTrue();
        assertThat(conversion.streamFormat()).isEqualTo("SSE");
    }

    @Test
    @DisplayName("响应为 ndjson 时判定为 NDJSON，普通 application/json 不算流式")
    void detectsNdjsonAndRejectsPlainJson() {
        Operation ndjson = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok")
                        .content(new Content().addMediaType("application/x-ndjson", new MediaType()))));
        assertThat(converter.convert(ndjson, "GET", "/stream").streamFormat()).isEqualTo("NDJSON");

        Operation plain = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok")
                        .content(new Content().addMediaType("application/json", new MediaType()))));
        JsonSchemaConverter.Conversion conversion = converter.convert(plain, "GET", "/orders");
        assertThat(conversion.streaming()).isFalse();
        assertThat(conversion.streamFormat()).isNull();
    }

    @Test
    @DisplayName("JSON Pointer 按 RFC 6901 转义斜杠与波浪号，前端可据此高亮原文档")
    void escapesJsonPointer() {
        assertThat(JsonSchemaConverter.pointer("/orders/{id}", "GET")).isEqualTo("/paths/~1orders~1{id}/get");
        assertThat(JsonSchemaConverter.pointer("/a~b", "POST")).isEqualTo("/paths/~1a~0b/post");
    }

    // ------------------------------------------------------------------ 夹具

    private static Schema<?> schema(String json) {
        try {
            return io.swagger.v3.core.util.Json.mapper().readValue(json, Schema.class);
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具 schema 解析失败: " + json, e);
        }
    }

    private static JsonNode properties(JsonSchemaConverter.Conversion conversion) {
        return conversion.inputSchema().get("properties");
    }

    private static List<String> propertyNames(JsonSchemaConverter.Conversion conversion) {
        JsonNode node = properties(conversion);
        List<String> names = new ArrayList<>();
        if (node != null) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static List<String> required(JsonSchemaConverter.Conversion conversion) {
        JsonNode node = conversion.inputSchema().get("required");
        List<String> names = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> names.add(item.asText()));
        }
        return names;
    }

    private static List<String> diagnosticFields(JsonSchemaConverter.Conversion conversion) {
        return conversion.diagnostics().stream().map(RegistrationDtos.Diagnostic::field).toList();
    }
}