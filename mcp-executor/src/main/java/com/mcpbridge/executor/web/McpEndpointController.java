package com.mcpbridge.executor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcError;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.jsonrpc.JsonRpcRequest;
import com.mcpbridge.common.jsonrpc.JsonRpcResponse;
import com.mcpbridge.common.protocol.McpHeaders;
import com.mcpbridge.common.protocol.McpMethods;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.common.util.TraceContext;
import com.mcpbridge.executor.auth.DownstreamAuthenticator;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.mcp.LegacyProtocolException;
import com.mcpbridge.executor.mcp.McpDispatcher;
import com.mcpbridge.executor.mcp.McpErrorException;
import com.mcpbridge.executor.mcp.ProtocolGuard;
import com.mcpbridge.executor.snapshot.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * MCP 端点：{@code POST /{保留前缀}/{PATH 末段}}（EXE-02 / EXE-08）。
 *
 * <p>请求体用 {@code String} 接收后自行解析，而不是让 Spring 直接反序列化成
 * {@link JsonRpcRequest}。原因是解析失败时必须回 JSON-RPC 的 {@code -32700 Parse error}；
 * 交给框架的话会得到一个 Spring 风格的 400，MCP 客户端无法识别，
 * 只会显示「连接失败」——而这恰恰是最需要明确报错的场景。
 *
 * <p>处理顺序是刻意固定的，每一步都对应一个可诊断的错误：
 * <ol>
 *   <li>保留前缀校验 —— 前缀不对说明端点地址配错了；</li>
 *   <li>JSON 解析 —— 失败回 -32700；</li>
 *   <li>协议守卫 —— legacy 形态回 -32022 与升级引导（决策 D1，先于一切业务逻辑）；</li>
 *   <li>快照就绪检查 —— 未就绪回 503 + Retry-After，<b>不回空清单</b>；</li>
 *   <li>Server 查找 —— 未发布回 -32001/404；</li>
 *   <li>下行鉴权（Auth-D）—— 失败回 -32004/401；</li>
 *   <li>方法分派。</li>
 * </ol>
 * 把鉴权放在「Server 查找之后」不是疏忽：Auth-D 的配置本身就存在快照里，
 * 找不到 Server 就无从校验；而 404 与 401 的区别对调用方定位问题很有价值。
 */
@RestController
public class McpEndpointController {

    private static final Logger log = LoggerFactory.getLogger(McpEndpointController.class);

    /** 这些方法的响应带 {@code Mcp-Cache-Ttl-Ms} 头（SEP-2549）。 */
    private static final Set<String> CACHEABLE_METHODS = Set.of(
            McpMethods.SERVER_DISCOVER,
            McpMethods.TOOLS_LIST,
            McpMethods.RESOURCES_LIST,
            McpMethods.PROMPTS_LIST);

    private static final int NOT_READY_RETRY_SECONDS = 5;

    private final ExecutorProperties properties;
    private final SnapshotStore store;
    private final ProtocolGuard guard;
    private final DownstreamAuthenticator downstreamAuthenticator;
    private final McpDispatcher dispatcher;

    public McpEndpointController(ExecutorProperties properties,
                                 SnapshotStore store,
                                 ProtocolGuard guard,
                                 DownstreamAuthenticator downstreamAuthenticator,
                                 McpDispatcher dispatcher) {
        this.properties = properties;
        this.store = store;
        this.guard = guard;
        this.downstreamAuthenticator = downstreamAuthenticator;
        this.dispatcher = dispatcher;
    }

    @PostMapping(value = "/{prefix}/{segment}", consumes = MediaType.ALL_VALUE)
    public Mono<ResponseEntity<String>> post(@PathVariable("prefix") String prefix,
                                             @PathVariable("segment") String segment,
                                             ServerHttpRequest httpRequest,
                                             @RequestBody(required = false) String body) {
        HttpHeaders headers = httpRequest.getHeaders();
        String expectedPrefix = properties.protocol().pathPrefix();
        if (!expectedPrefix.equals(prefix)) {
            return Mono.just(json(HttpStatus.NOT_FOUND, JsonRpcResponse.error(null,
                    JsonRpcError.of(JsonRpcErrorCodes.SERVER_NOT_FOUND,
                            "端点路径不正确：平台保留前缀为 /" + expectedPrefix,
                            Map.of("expectedPrefix", expectedPrefix, "actualPrefix", nullSafe(prefix)))), null));
        }

        JsonRpcRequest request = parse(body);
        if (request == null) {
            return Mono.just(json(HttpStatus.BAD_REQUEST, JsonRpcResponse.error(null,
                    JsonRpcErrorCodes.INVALID_REQUEST, "请求体为空，期望一个 JSON-RPC 2.0 请求对象"), null));
        }
        if (request == PARSE_FAILURE) {
            return Mono.just(json(HttpStatus.BAD_REQUEST, JsonRpcResponse.error(null,
                    JsonRpcError.of(JsonRpcErrorCodes.PARSE_ERROR,
                            "请求体不是合法的 JSON-RPC 2.0 消息",
                            Map.of("protocolVersion", McpProtocol.SUPPORTED_VERSION))), null));
        }

        JsonNode id = request.id();
        // 链路上下文（OPS-02）：一次解析、全程复用。放在这里而不是各个子流程里各自解析，
        // 是为了让「回写响应头的 id」与「发给上游的 traceparent」必然是同一个 trace——
        // 两处各生成一次的话，调用方拿着响应头里的 id 去上游日志里根本搜不到。
        TraceContext trace = resolveTrace(headers, request);

        try {
            guard.requireModern(headers, request);
            guard.requireValidEnvelope(request);
        } catch (LegacyProtocolException e) {
            // 这条日志是「谁还在用旧协议」的唯一数据来源，必须留痕，但不能带上任何凭据
            log.warn("拒绝 legacy 协议形态 segment={} knownLegacy={} reason={}",
                    segment, e.isKnownLegacy(), LogSanitizer.sanitize(e.reason()));
            return Mono.just(json(HttpStatus.BAD_REQUEST, e.toResponse(id),
                    head -> head.set(McpHeaders.PROTOCOL_VERSION, McpProtocol.SUPPORTED_VERSION)));
        } catch (McpErrorException e) {
            return Mono.just(json(statusOf(e.httpStatus()), JsonRpcResponse.error(id, e.error()), null));
        }

        if (!store.isReady()) {
            return Mono.just(json(HttpStatus.SERVICE_UNAVAILABLE,
                    JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcErrorCodes.SERVER_NOT_FOUND,
                            "Executor 尚未完成发布快照同步，请稍后重试",
                            Map.of("ready", false, "revision", store.revision()))),
                    head -> head.set(HttpHeaders.RETRY_AFTER, String.valueOf(NOT_READY_RETRY_SECONDS))));
        }

        ServerSnapshot server = store.server(segment).orElse(null);
        if (server == null) {
            return Mono.just(json(HttpStatus.NOT_FOUND, JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcErrorCodes.SERVER_NOT_FOUND,
                            "PATH 末段 " + segment + " 未发布到本集群",
                            Map.of("pathSegment", nullSafe(segment),
                                    "clusterKey", nullSafe(store.current().clusterKey())))), null));
        }

        try {
            downstreamAuthenticator.authenticate(server, headers);
        } catch (McpErrorException e) {
            return Mono.just(json(statusOf(e.httpStatus()), JsonRpcResponse.error(id, e.error()),
                    head -> head.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")));
        }

        // 通知（无 id）不需要响应体。202 而不是 200 空对象，是为了让客户端明确知道「不会有结果」
        if (request.isNotification()) {
            return Mono.just(accepted());
        }

        String method = guard.resolveMethod(headers, request.method());
        // Mono.defer 是必需的：dispatcher 内部有大量同步抛出（参数缺失、tool 不存在），
        // 不包一层的话这些异常会绕过 onErrorResume 直接冒到框架层，变成一个语焉不详的 500
        return Mono.defer(() -> dispatcher.dispatch(server, method, request, headers, trace))
                .map(response -> success(response, method, server, trace))
                .onErrorResume(e -> Mono.just(failure(id, segment, e, trace)));
    }

    /**
     * 确定本次请求的链路上下文。
     *
     * <p>来源优先级：标准 HTTP {@code traceparent} 头 → MCP {@code _meta.traceparent} →
     * {@code _meta.traceId}。先看 HTTP 头是因为 W3C 感知的网关/客户端都走头，
     * 而 {@code _meta} 是 MCP 自有的通道，属于「客户端只能填 _meta」时的兜底。
     */
    private static TraceContext resolveTrace(HttpHeaders headers, JsonRpcRequest request) {
        String traceparent = firstNonBlank(headers.getFirst(TraceContext.HEADER), request.metaText("traceparent"));
        return TraceContext.inbound(traceparent, request.metaText("traceId"));
    }

    /**
     * GET 不被支持。
     *
     * <p>旧版 MCP 用 GET 打开 SSE 长连接接收服务端推送；2026-07-28 已取消会话（SEP-2567/2575），
     * 平台无状态、无服务端推送，因此没有可打开的流。回 405 + JSON-RPC 错误而不是直接 404，
     * 是为了让还在用旧传输的客户端拿到明确原因。
     */
    @GetMapping("/{prefix}/{segment}")
    public ResponseEntity<String> get(@PathVariable("prefix") String prefix,
                                      @PathVariable("segment") String segment) {
        return methodNotAllowed("GET");
    }

    /** DELETE 用于终止会话；平台无会话，因此没有可删除的资源。 */
    @DeleteMapping("/{prefix}/{segment}")
    public ResponseEntity<String> delete(@PathVariable("prefix") String prefix,
                                         @PathVariable("segment") String segment) {
        return methodNotAllowed("DELETE");
    }

    private ResponseEntity<String> methodNotAllowed(String method) {
        return json(HttpStatus.METHOD_NOT_ALLOWED,
                JsonRpcResponse.error(null, JsonRpcError.of(JsonRpcErrorCodes.METHOD_NOT_FOUND,
                        "本平台不支持 " + method + "：MCP 端点无状态，仅接受 POST",
                        Map.of("supportedHttpMethods", List.of("POST"),
                                "sessionless", true,
                                "protocolVersion", McpProtocol.SUPPORTED_VERSION,
                                "reason", "2026-07-28 已移除会话与服务端推流（SEP-2567/2575）"))),
                head -> head.set(HttpHeaders.ALLOW, "POST"));
    }

    // ------------------------------------------------------------------ 响应装配

    private ResponseEntity<String> success(JsonRpcResponse response,
                                           String method,
                                           ServerSnapshot server,
                                           TraceContext trace) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (CACHEABLE_METHODS.contains(method)) {
            int ttl = server.listTtlMs() > 0 ? server.listTtlMs() : properties.protocol().defaultListTtlMs();
            builder.header(McpHeaders.CACHE_TTL, String.valueOf(ttl));
            // 同时给 HTTP 层缓存提示：private 表示中间共享缓存不得缓存（响应含租户数据）
            builder.cacheControl(CacheControl.maxAge(Duration.ofMillis(ttl)).cachePrivate());
        }
        builder.header(McpHeaders.PROTOCOL_VERSION, McpProtocol.SUPPORTED_VERSION);
        // 回写的是<b>实际下发到上游的那个 trace-id</b>，而不是调用方原样给的值。
        // 调用方给的如果是任意字符串（非 W3C 形态），平台只能另起一个 trace，
        // 此时把新 id 告诉他才有意义——否则他拿着自己的字符串去上游日志里搜不到任何东西。
        builder.header(TraceContext.LEGACY_HEADER, trace.traceId());
        return builder.body(Json.write(response));
    }

    private ResponseEntity<String> failure(JsonNode id, String segment, Throwable throwable, TraceContext trace) {
        // 失败响应同样带上 trace-id：出问题时正是最需要它的时候
        Consumer<HttpHeaders> traceHeader = head -> head.set(TraceContext.LEGACY_HEADER, trace.traceId());
        if (throwable instanceof McpErrorException error) {
            return json(statusOf(error.httpStatus()), JsonRpcResponse.error(id, error.error()), traceHeader);
        }
        if (throwable instanceof LegacyProtocolException legacy) {
            return json(HttpStatus.BAD_REQUEST, legacy.toResponse(id), traceHeader);
        }
        // 未预期错误：不回传异常消息。上游栈信息里可能带着内部地址、SQL 片段甚至凭据
        log.error("处理 MCP 请求时发生未预期错误 segment={} traceId={}", segment, trace.traceId(), throwable);
        return json(HttpStatus.INTERNAL_SERVER_ERROR,
                JsonRpcResponse.error(id, JsonRpcError.internal("内部错误，请联系平台管理员并提供 traceId")),
                traceHeader);
    }

    /** 解析失败用哨兵对象表示，避免和「合法解析出的请求」混淆。 */
    private static final JsonRpcRequest PARSE_FAILURE = new JsonRpcRequest(null, null, null, null, null);

    private static JsonRpcRequest parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonRpcRequest parsed = Json.read(body, JsonRpcRequest.class);
            return parsed == null ? PARSE_FAILURE : parsed;
        } catch (RuntimeException e) {
            log.debug("JSON-RPC 请求体解析失败：{}", LogSanitizer.sanitizeAndTruncate(String.valueOf(e.getMessage()), 200));
            return PARSE_FAILURE;
        }
    }

    private static ResponseEntity<String> json(HttpStatus status,
                                               JsonRpcResponse response,
                                               Consumer<HttpHeaders> customizer) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (customizer != null) {
            builder.headers(customizer::accept);
        }
        return builder.body(Json.write(response));
    }

    private static ResponseEntity<String> accepted() {
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).build();
    }

    /** {@code HttpStatus.valueOf} 对非标准码会抛异常，这里兜底成 400，绝不让错误处理自身再出错。 */
    private static HttpStatus statusOf(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status == null ? HttpStatus.BAD_REQUEST : status;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 取第一个非空白值；全为空则返回 null，交由 {@link TraceContext#inbound} 决定兜底。 */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}