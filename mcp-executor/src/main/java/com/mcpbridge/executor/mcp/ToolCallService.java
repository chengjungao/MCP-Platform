package com.mcpbridge.executor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.common.util.TraceContext;
import com.mcpbridge.executor.auth.UpstreamCredentialProvider;
import com.mcpbridge.executor.metrics.ExecutorMetrics;
import com.mcpbridge.executor.upstream.RestRequest;
import com.mcpbridge.executor.upstream.RestRequestBuilder;
import com.mcpbridge.executor.upstream.UpstreamInvoker;
import com.mcpbridge.executor.upstream.UpstreamResponse;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * 工具调用（EXE-02 / EXE-03）。
 *
 * <p>这里体现了一个容易搞错的分界：<b>上游返回 4xx/5xx 不是协议错误</b>。
 * 对 MCP 客户端来说「工具跑了，结果是失败」是一个正常的调用结果，
 * 因此回 {@code result.isError=true} 而不是 JSON-RPC {@code error} 对象——
 * 后者会让客户端把「订单号不存在」当成「桥接平台坏了」而触发重连或告警。
 *
 * <p>只有下面这些才走 JSON-RPC error：参数装配失败（-32602）、
 * tool 不存在（-32002）、连接/超时/熔断（-32003）、下行鉴权（-32004）。
 */
@Service
public class ToolCallService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallService.class);

    /** 失败时回给调用方的响应体摘要长度：够看清错误原因，又不至于把上游的错误页整页塞进 MCP 响应。 */
    private static final int ERROR_BODY_PREVIEW = 1024;

    private final RestRequestBuilder requestBuilder;
    private final UpstreamInvoker invoker;
    private final UpstreamCredentialProvider credentialProvider;
    private final ExecutorMetrics metrics;

    public ToolCallService(RestRequestBuilder requestBuilder,
                           UpstreamInvoker invoker,
                           UpstreamCredentialProvider credentialProvider,
                           ExecutorMetrics metrics) {
        this.requestBuilder = requestBuilder;
        this.invoker = invoker;
        this.credentialProvider = credentialProvider;
        this.metrics = metrics;
    }

    /**
     * @param arguments {@code params.arguments}，可为 null（无参 tool）
     * @param trace     链路上下文（OPS-02）。发往上游的 span-id 在这里派生，
     *                  使「一次调用对应一次上游请求」在链路系统里可区分
     */
    public Mono<ObjectNode> call(ServerSnapshot server, ToolSnapshot tool, JsonNode arguments, TraceContext trace) {
        // 参数装配是同步的，失败会直接抛 McpErrorException；由控制器层的 Mono.defer 转成错误信号
        RestRequest request = requestBuilder.build(server, tool, arguments);
        // 计时从参数装配之后开始：装配失败属于「调用方传错了参数」，计入耗时只会污染这个接口的 P99
        Timer.Sample sample = metrics.startTimer();
        // 每个上游请求各派生一个 span：同一入站请求读 resource 会触发 tool 调用，
        // 复用同一个 span-id 会让链路里两次不同的调用看起来是同一次
        String traceparent = trace == null ? null : trace.child().header();
        return credentialProvider.resolve(server, tool)
                .flatMap(credentials -> invoker.invoke(server, tool, request, credentials, traceparent))
                .map(response -> assemble(server, tool, response))
                .doOnNext(result -> metrics.recordToolCall(server.pathSegment(), tool.name(),
                        result.path("isError").asBoolean(false)
                                ? ExecutorMetrics.ToolOutcome.UPSTREAM_ERROR
                                : ExecutorMetrics.ToolOutcome.SUCCESS,
                        sample))
                .doOnError(error -> metrics.recordToolCall(server.pathSegment(), tool.name(),
                        ExecutorMetrics.ToolOutcome.PLATFORM_ERROR, sample));
    }

    private ObjectNode assemble(ServerSnapshot server, ToolSnapshot tool, UpstreamResponse response) {
        if (response.status() == HttpStatus.UNAUTHORIZED.value()) {
            // 只对 401 作废上行令牌缓存。403 是「身份对了但权限不够」，换个令牌也一样，
            // 把 403 也算进去会让权限配置错误直接把上游令牌端点打成筛子
            credentialProvider.invalidate(server, tool);
        }

        ObjectNode result = Json.obj();
        ArrayNode content = result.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");

        if (response.isSuccess()) {
            textPart.put("text", response.body() == null ? "" : response.body());
            JsonNode structured = parseStructured(response);
            if (structured != null) {
                result.set("structuredContent", structured);
            }
            result.put("isError", false);
        } else {
            textPart.put("text", "上游返回 HTTP " + response.status() + "：" + preview(response.body()));
            result.put("isError", true);
            result.put("httpStatus", response.status());
            log.warn("tool 调用得到上游错误响应 server={} tool={} status={}",
                    server.pathSegment(), tool.name(), response.status());
        }

        if (response.truncated()) {
            // 必须如实告知截断：调用方拿到半截 JSON 时，第一反应应该是「平台截了」而不是「上游坏了」
            ObjectNode meta = result.putObject("_meta");
            meta.put("responseTruncated", true);
            meta.put("hint", "上游响应超出 max-response-bytes，已截断");
            // 截断是可配置阈值被撞到的事实，不是错误；但它意味着客户端拿到的是不完整数据，值得被观测到
            metrics.recordTruncatedResponse(server.pathSegment(), tool.name());
        }
        return result;
    }

    /**
     * 当上游响应是 JSON 对象时，额外给出 {@code structuredContent}。
     *
     * <p>这是「锦上添花」而不是「必需」：text 部分始终承载完整原文，
     * 因此解析失败或响应被截断时直接跳过，绝不影响主结果。
     */
    private static JsonNode parseStructured(UpstreamResponse response) {
        String body = response.body();
        if (body == null || body.isBlank() || response.truncated()) {
            return null;
        }
        String contentType = response.contentType();
        boolean looksLikeJson = (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json"))
                || body.charAt(0) == '{';
        if (!looksLikeJson) {
            return null;
        }
        try {
            JsonNode node = Json.tree(body);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            // 上游声明了 JSON 却返回了非 JSON（网关错误页最常见），text 里已有原文，无需再报一次
            return null;
        }
    }

    private static String preview(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        return LogSanitizer.sanitizeAndTruncate(body.strip(), ERROR_BODY_PREVIEW);
    }
}