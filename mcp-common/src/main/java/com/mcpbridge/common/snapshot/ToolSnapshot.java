package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生效模型中的单个 Tool（base ⊕ overlay 合并后的结果，Executor 直接加载）。
 *
 * @param name         tool 名（BR-1 命名规则的产物，或 overlay 覆盖后的名字；多服务接入时带服务前缀）
 * @param title        可选展示名
 * @param description  tool 描述（overlay 可覆盖）
 * @param method       上游 HTTP 方法
 * @param path         上游路径模板，如 {@code /users/{id}}
 * @param anchor       overlay 锚点 {@code METHOD path}，用于文档升级后的漂移检测（BR-2 / REG-03）
 * @param inputSchema  JSON Schema 2020-12
 * @param parameterIn  参数位置映射：schema 属性名 → path/query/header/cookie/body
 * @param requestBodyRequired 上游是否强制请求体
 * @param streaming    是否流式端点（BR-5）
 * @param streamFormat 流格式：SSE / NDJSON / CHUNKED
 * @param idempotent   是否幂等（决定 EXE-03 的重试策略）
 * @param upstreamRef  指向 {@link UpstreamEntry#serviceId()}（注册时自动绑定，tool 按此选所属上游）
 * @param authBOverride tool 级上行授权覆盖（BR-4，P1；为 null 时用 UpstreamEntry.authB，再回落 Server.authB）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSnapshot(
        String name,
        String title,
        String description,
        String method,
        String path,
        String anchor,
        JsonNode inputSchema,
        Map<String, String> parameterIn,
        boolean requestBodyRequired,
        boolean streaming,
        String streamFormat,
        boolean idempotent,
        String upstreamRef,
        AuthBSnapshot authBOverride) {

    /** 幂等方法集合（EXE-03：仅幂等方法允许自动重试）。 */
    public static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    public boolean isIdempotentByMethod() {
        return method != null && IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }
}
