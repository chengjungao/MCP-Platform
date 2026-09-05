package com.mcpbridge.executor.upstream;

import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.executor.auth.UpstreamCredentials;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.mcp.McpErrorException;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游调用执行器（EXE-03 / EXE-04）。
 *
 * <p>四个刻意的实现选择：
 *
 * <ol>
 *   <li><b>URI 手写拼接，不用 {@code UriComponentsBuilder}</b>。后者的 {@code build()} 会把
 *       字符串里的 {@code {}} 当作待展开的模板变量，而我们的路径<b>已经完成了变量替换</b>——
 *       用户传一个含花括号的订单备注就能让整条请求抛
 *       {@code IllegalArgumentException: Not enough variable values}。
 *       同时这里传 {@link URI} 而不是 String 给 WebClient，避免二次模板展开。</li>
 *   <li><b>WebClient 按超时组合缓存</b>。每个 Server 可以配自己的读写超时，
 *       但 WebClient 的超时绑定在连接器上，因此按 {@code (connect, read, maxBytes)} 复用实例，
 *       既让慢接口不拖累快接口，又不会每请求新建连接池。</li>
 *   <li><b>只对幂等 tool 重试</b>。{@code POST /orders} 超时后重发可能创建两笔订单，
 *       这种代价远高于让用户自己重试一次。判定取「显式标注 idempotent」或
 *       「方法本身幂等（GET/HEAD/OPTIONS/PUT/DELETE）」。</li>
 *   <li><b>响应体按字节预算截断而不是靠编解码器抛错</b>。上游异常时可能吐出几十 MB 的错误页，
 *       聚合进内存会直接打爆堆；这里边读边计，超预算就取消订阅并标记 {@code truncated}。</li>
 * </ol>
 */
@Component
public class UpstreamInvoker {

    private static final Logger log = LoggerFactory.getLogger(UpstreamInvoker.class);

    private static final Duration RETRY_DELAY = Duration.ofMillis(200);

    private static final Set<Integer> DEFAULT_RETRY_ON_STATUS = Set.of(502, 503, 504);

    private final ExecutorProperties properties;
    private final CircuitBreakerRegistry breakers;

    /** key = "connect|read|maxBytes"；组合数量有限（等于不同超时档位的数量），不需要淘汰。 */
    private final ConcurrentHashMap<String, WebClient> clients = new ConcurrentHashMap<>();

    /** 轮询计数器按 Server 隔离，避免高频 Server 打乱低频 Server 的分发节奏。 */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public UpstreamInvoker(ExecutorProperties properties, CircuitBreakerRegistry breakers) {
        this.properties = properties;
        this.breakers = breakers;
    }

    public Mono<UpstreamResponse> invoke(ServerSnapshot server,
                                         ToolSnapshot tool,
                                         RestRequest request,
                                         UpstreamCredentials credentials) {
        com.mcpbridge.common.snapshot.UpstreamEntry entry = server.effectiveUpstream(tool);
        UpstreamSnapshot upstream = entry.config() != null
                ? entry.config()
                : UpstreamSnapshot.defaults(List.of());
        String breakerKey = CircuitBreakerRegistry.key(server.serverId(), entry.serviceId());
        List<String> baseUrls = upstream.baseUrls() == null
                ? List.of()
                : upstream.baseUrls().stream().filter(url -> url != null && !url.isBlank()).toList();
        if (baseUrls.isEmpty()) {
            return Mono.error(upstreamError(server, "Server 未配置可用的上游地址",
                    Map.of("serverId", server.serverId(),
                            "pathSegment", nullSafe(server.pathSegment()),
                            "serviceId", nullSafe(entry.serviceId()))));
        }

        UpstreamSnapshot.CircuitBreaker breakerConfig = upstream.circuitBreaker() != null
                ? upstream.circuitBreaker()
                : UpstreamSnapshot.CircuitBreaker.defaults();
        if (!breakers.allow(breakerKey, breakerConfig)) {
            return Mono.error(upstreamError(server, "上游熔断已打开，暂时拒绝调用",
                    Map.of("serverId", server.serverId(),
                            "pathSegment", nullSafe(server.pathSegment()),
                            "serviceId", nullSafe(entry.serviceId()),
                            "circuitBreaker", "OPEN",
                            "openMs", breakerConfig.openMs())));
        }

        boolean idempotent = tool.idempotent() || tool.isIdempotentByMethod();
        Set<Integer> retryOnStatus = upstream.retryOnStatus() == null || upstream.retryOnStatus().isEmpty()
                ? DEFAULT_RETRY_ON_STATUS
                : new HashSet<>(upstream.retryOnStatus());
        int retries = idempotent ? Math.max(0, upstream.retries()) : 0;
        boolean canRetry = retries > 0;

        String baseUrl = pickBaseUrl(server, breakerKey, upstream, baseUrls);
        URI uri = URI.create(buildUri(baseUrl, request, credentials.query()));
        WebClient client = clientFor(upstream.connectTimeoutMs(), upstream.readTimeoutMs());
        int budget = properties.upstream().maxResponseBytes();

        Mono<UpstreamResponse> call = Mono.defer(() -> exchange(client, uri, request, credentials, budget)
                .flatMap(response -> {
                    if (canRetry && retryOnStatus.contains(response.status())) {
                        // 用一个内部信号把「这个状态码值得再试一次」传给 retryWhen：
                        // retryWhen 只看错误流，而正常返回的 502 响应不是错误
                        return Mono.<UpstreamResponse>error(new RetryableUpstream(response));
                    }
                    return Mono.just(response);
                }));

        if (canRetry) {
            call = call.retryWhen(Retry.fixedDelay(retries, RETRY_DELAY)
                    .filter(t -> isRetryable(t, retryOnStatus))
                    .doBeforeRetry(signal -> log.warn("上游调用重试第 {} 次 server={} tool={} 原因={}",
                            signal.totalRetries() + 1, server.pathSegment(), tool.name(),
                            LogSanitizer.sanitize(String.valueOf(signal.failure())))));
        }

        return call
                .doOnNext(response -> {
                    if (response.isServerError()) {
                        breakers.onFailure(breakerKey, breakerConfig);
                    } else {
                        breakers.onSuccess(breakerKey);
                    }
                })
                .onErrorResume(t -> recover(server, tool, uri, breakerKey, breakerConfig, t));
    }

    // ------------------------------------------------------------------ 负载均衡

    String pickBaseUrl(ServerSnapshot server, String breakerKey, UpstreamSnapshot upstream, List<String> baseUrls) {
        int size = baseUrls.size();
        if (size == 1) {
            return baseUrls.get(0);
        }
        AtomicLong counter = counters.computeIfAbsent(breakerKey, id -> new AtomicLong());
        if (upstream.lbStrategy() == UpstreamSnapshot.LbStrategy.WEIGHTED) {
            List<Integer> weights = upstream.weights();
            if (weights != null && weights.size() == size) {
                int total = 0;
                for (Integer weight : weights) {
                    total += weight == null ? 0 : Math.max(0, weight);
                }
                if (total > 0) {
                    long slot = Math.floorMod(counter.getAndIncrement(), total);
                    int accumulated = 0;
                    for (int i = 0; i < size; i++) {
                        Integer weight = weights.get(i);
                        accumulated += weight == null ? 0 : Math.max(0, weight);
                        if (slot < accumulated) {
                            return baseUrls.get(i);
                        }
                    }
                }
            }
            // 权重缺失、长度不匹配或全为 0：退回轮询而不是报错。
            // 一个配错的权重不该让整个已发布端点直接不可用。
            log.warn("server={} 配置了 WEIGHTED 但权重不可用（weights={}），本次退回轮询",
                    server.pathSegment(), upstream.weights());
        }
        return baseUrls.get((int) Math.floorMod(counter.getAndIncrement(), size));
    }

    /**
     * 拼接最终 URI。
     *
     * <p>路径已在 {@code RestRequestBuilder} 中完成变量替换与分段编码，这里只做字符串拼接
     * 与查询串编码。查询值用 {@code %20} 而不是 {@code +} 表示空格：{@code +} 只在
     * {@code application/x-www-form-urlencoded} 里代表空格，不少上游框架按字面加号解析。
     */
    String buildUri(String baseUrl, RestRequest request, Map<String, String> credentialQuery) {
        StringBuilder uri = new StringBuilder(baseUrl.replaceAll("/+$", ""));
        uri.append(request.path() == null ? "" : request.path());

        List<RestRequest.QueryParam> params = new ArrayList<>(
                request.query() == null ? List.of() : request.query());
        if (credentialQuery != null) {
            credentialQuery.forEach((name, value) -> params.add(new RestRequest.QueryParam(name, value)));
        }
        if (!params.isEmpty()) {
            boolean first = uri.indexOf("?") < 0;
            for (RestRequest.QueryParam param : params) {
                uri.append(first ? '?' : '&');
                first = false;
                uri.append(encodeQuery(param.name())).append('=').append(encodeQuery(param.value()));
            }
        }
        return uri.toString();
    }

    private static String encodeQuery(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ 实际调用

    private Mono<UpstreamResponse> exchange(WebClient client,
                                            URI uri,
                                            RestRequest request,
                                            UpstreamCredentials credentials,
                                            int budget) {
        WebClient.RequestBodySpec spec = client
                .method(HttpMethod.valueOf(request.method()))
                .uri(uri)
                .headers(headers -> applyHeaders(headers, request, credentials));
        WebClient.RequestHeadersSpec<?> ready = request.hasBody() ? spec.bodyValue(request.body()) : spec;
        return ready.exchangeToMono(response -> readBody(response, budget));
    }

    private void applyHeaders(HttpHeaders headers, RestRequest request, UpstreamCredentials credentials) {
        headers.set(HttpHeaders.USER_AGENT, "MCP-Bridge-Executor/" + properties.node().version());
        if (request.headers() != null) {
            request.headers().forEach(headers::set);
        }
        // 上行凭据后写，覆盖同名头：调用方不能通过伪造 header 参数改掉 Authorization
        if (credentials != null && credentials.headers() != null) {
            credentials.headers().forEach(headers::set);
        }
        if (request.hasBody() && headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
    }

    /**
     * 带字节预算地读响应体。
     *
     * <p>超预算时 {@code takeWhile} 会取消订阅，剩余数据不再进内存。
     * 这里必须手动释放每个 {@link DataBuffer}——WebFlux 的堆外缓冲不靠 GC 回收，
     * 漏一次就是一次直接内存泄漏。
     */
    private Mono<UpstreamResponse> readBody(ClientResponse response, int budget) {
        int status = response.statusCode().value();
        HttpHeaders headers = response.headers().asHttpHeaders();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        return response.body(BodyExtractors.toDataBuffers())
                .map(UpstreamInvoker::toBytes)
                .doOnNext(chunk -> append(sink, chunk, budget, truncated))
                .takeWhile(chunk -> !truncated.get())
                .then(Mono.fromSupplier(() -> new UpstreamResponse(
                        status, headers, sink.toString(StandardCharsets.UTF_8), truncated.get())))
                .onErrorResume(DataBufferLimitException.class, e -> {
                    // 理论上走不到这里（预算控制在编解码器上限之前），留着兜底
                    log.warn("上游响应超出编解码器缓冲上限，已丢弃响应体");
                    return Mono.just(new UpstreamResponse(status, headers, "", true));
                });
    }

    private static void append(ByteArrayOutputStream sink, byte[] chunk, int budget, AtomicBoolean truncated) {
        int remaining = budget - sink.size();
        if (remaining <= 0) {
            truncated.set(true);
            return;
        }
        if (chunk.length > remaining) {
            sink.write(chunk, 0, remaining);
            truncated.set(true);
        } else {
            sink.write(chunk, 0, chunk.length);
        }
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private WebClient clientFor(long connectTimeoutMs, long readTimeoutMs) {
        long connect = connectTimeoutMs > 0
                ? connectTimeoutMs
                : properties.upstream().connectTimeout().toMillis();
        long read = readTimeoutMs > 0
                ? readTimeoutMs
                : properties.upstream().readTimeout().toMillis();
        int maxBytes = properties.upstream().maxResponseBytes();
        String key = connect + "|" + read + "|" + maxBytes;
        return clients.computeIfAbsent(key, ignored -> {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(connect, Integer.MAX_VALUE))
                    .responseTimeout(Duration.ofMillis(read))
                    .followRedirect(true);
            return WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxBytes))
                    .build();
        });
    }

    // ------------------------------------------------------------------ 重试与错误归一

    private boolean isRetryable(Throwable throwable, Set<Integer> retryOnStatus) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RetryableUpstream retryable) {
            return retryOnStatus.contains(retryable.response().status());
        }
        if (cause instanceof McpErrorException) {
            // 参数、鉴权、熔断这类问题重试一万次也是同样结果
            return false;
        }
        // 连接被拒、DNS 失败、读超时、上游提前断连——都属于「换一次可能就成功」的瞬时故障
        String name = cause.getClass().getName();
        return name.contains("ConnectException")
                || name.contains("TimeoutException")
                || name.contains("PrematureCloseException")
                || name.contains("UnknownHostException")
                || name.contains("NoRouteToHostException");
    }

    private Mono<UpstreamResponse> recover(ServerSnapshot server,
                                           ToolSnapshot tool,
                                           URI uri,
                                           String breakerKey,
                                           UpstreamSnapshot.CircuitBreaker breakerConfig,
                                           Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RetryableUpstream retryable) {
            UpstreamResponse response = retryable.response();
            breakers.onFailure(breakerKey, breakerConfig);
            log.warn("上游重试后仍失败 server={} tool={} status={}",
                    server.pathSegment(), tool.name(), response.status());
            return Mono.just(response);
        }
        if (cause instanceof McpErrorException error) {
            return Mono.error(error);
        }
        breakers.onFailure(breakerKey, breakerConfig);
        String reason = LogSanitizer.sanitizeAndTruncate(String.valueOf(cause.getMessage()), 256);
        log.warn("上游调用失败 server={} tool={} host={} 原因={}",
                server.pathSegment(), tool.name(), uri.getHost(), reason);
        return Mono.error(upstreamError(server, "上游调用失败：" + cause.getClass().getSimpleName(),
                Map.of("serverId", server.serverId(),
                        "pathSegment", nullSafe(server.pathSegment()),
                        "tool", nullSafe(tool.name()),
                        "reason", nullSafe(reason))));
    }

    /** 剥掉 Reactor 与 retry 包装，拿到真正的根因。 */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = Exceptions.unwrap(throwable);
        for (int depth = 0; depth < 4 && current != null; depth++) {
            // Retry.fixedDelay 耗尽后抛的是包装异常，真实原因在 getCause() 里；
            // 不剥开就会把「上游 502」误报成「Retries exhausted」，排障时完全指错方向
            if (Exceptions.isRetryExhausted(current) && current.getCause() != null) {
                current = Exceptions.unwrap(current.getCause());
                continue;
            }
            return current;
        }
        return current == null ? throwable : current;
    }

    private static McpErrorException upstreamError(ServerSnapshot server, String message, Map<String, Object> data) {
        return McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                message + "（server=" + server.pathSegment() + "）", data);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 内部信号：用一个可重试的响应把「状态码命中重试集合」这件事传递给 retryWhen。 */
    private static final class RetryableUpstream extends RuntimeException {

        private final UpstreamResponse response;

        RetryableUpstream(UpstreamResponse response) {
            super("上游返回可重试状态码 " + response.status());
            this.response = response;
        }

        UpstreamResponse response() {
            return response;
        }
    }
}