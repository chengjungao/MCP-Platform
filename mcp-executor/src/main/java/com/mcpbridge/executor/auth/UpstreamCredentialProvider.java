package com.mcpbridge.executor.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcpbridge.common.jsonrpc.JsonRpcErrorCodes;
import com.mcpbridge.common.snapshot.AuthBSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.util.LogSanitizer;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.mcp.McpErrorException;
import com.mcpbridge.executor.state.SharedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 上行凭据装配（Auth-B / EXE-05，令牌部分对应 BR-6）。
 *
 * <p>核心安全约束：<b>上行密钥只在 Executor 内存里出现</b>。它由 Manager 用主密钥加密后
 * 固化进发布快照，Executor 解密使用，永不回传客户端、永不写日志（日志一律过
 * {@link LogSanitizer}）。
 *
 * <p>OAuth2 client_credentials 是这里唯一需要跨节点协调的场景，也是最容易写错的地方：
 * <ul>
 *   <li>令牌进共享缓存，避免 N 个节点各换一次把上游令牌端点打爆；</li>
 *   <li>刷新走<b>分布式锁 + 双重检查</b>：拿到锁之后必须再查一次缓存，
 *       否则等锁的节点会依次各刷新一次，锁就白加了；</li>
 *   <li>等不到锁时不报错，而是用「当前缓存值」兜底——宁可拿一个可能刚过期的令牌试一次，
 *       也不要让一次上游抖动把所有并发请求全部打失败；</li>
 *   <li>上游返回 401 时主动失效并广播，其它节点立刻丢弃缓存，避免用废令牌反复重试。</li>
 * </ul>
 */
@Component
public class UpstreamCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCredentialProvider.class);

    /** 提前刷新窗口：令牌还剩不到这么久就当作过期，避免请求在飞行途中失效。 */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    private static final Duration MIN_TOKEN_TTL = Duration.ofSeconds(30);

    private final WebClient upstreamWebClient;
    private final SharedState sharedState;
    private final ExecutorProperties properties;

    public UpstreamCredentialProvider(WebClient upstreamWebClient,
                                      SharedState sharedState,
                                      ExecutorProperties properties) {
        this.upstreamWebClient = upstreamWebClient;
        this.sharedState = sharedState;
        this.properties = properties;
    }

    /** 订阅失效广播：任一节点发现令牌作废，所有节点同步丢弃。 */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeInvalidation() {
        sharedState.subscribe(SharedState.TOKEN_INVALIDATION_TOPIC, key -> {
            sharedState.evictToken(key);
            log.info("收到令牌失效广播，已清除本地/共享缓存 key={}", key);
        });
    }

    /**
     * 解析本次调用应注入的凭据。
     *
     * <p>Tool 级覆盖优先于 Server 级：一个 Server 下的不同接口可能对接不同的下游系统
     * （例如聚合网关），这是覆盖模型存在的实际理由之一。
     */
    public Mono<UpstreamCredentials> resolve(ServerSnapshot server, ToolSnapshot tool) {
        AuthBSnapshot authB = effectiveAuthB(server, tool);
        if (authB == null || authB.type() == null || authB.type() == AuthBSnapshot.Type.NONE) {
            return Mono.just(UpstreamCredentials.empty());
        }
        return switch (authB.type()) {
            case NONE -> Mono.just(UpstreamCredentials.empty());
            case API_KEY -> Mono.just(apiKey(authB));
            case HTTP -> Mono.just(http(authB));
            case CUSTOM_HEADER -> Mono.just(customHeader(authB));
            // 令牌交换是阻塞调用（要等上游），必须挪出 Netty 的 event loop
            case OAUTH2_CLIENT_CREDENTIALS -> Mono
                    .fromCallable(() -> oauthToken(server, tool, authB))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(token -> bearer(token, authB));
        };
    }

    /** 上游返回 401 时调用：作废缓存并广播给其它节点。 */
    public void invalidate(ServerSnapshot server, ToolSnapshot tool) {
        AuthBSnapshot authB = effectiveAuthB(server, tool);
        if (authB == null || authB.type() != AuthBSnapshot.Type.OAUTH2_CLIENT_CREDENTIALS) {
            return;
        }
        String key = cacheKey(server, tool);
        sharedState.evictToken(key);
        sharedState.publish(SharedState.TOKEN_INVALIDATION_TOPIC, key);
        log.warn("上游返回 401，已作废并广播上游令牌失效 server={} tool={}", server.pathSegment(), tool.name());
    }

    static AuthBSnapshot effectiveAuthB(ServerSnapshot server, ToolSnapshot tool) {
        AuthBSnapshot override = tool == null ? null : tool.authBOverride();
        if (override != null && override.type() != null && override.type() != AuthBSnapshot.Type.NONE) {
            return override;
        }
        return server.authB();
    }

    // ------------------------------------------------------------------ 各类型装配

    private UpstreamCredentials apiKey(AuthBSnapshot authB) {
        requireCredential(authB.value(), "API Key 的密钥值");
        requireCredential(authB.name(), "API Key 的参数名");
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        if (authB.in() == AuthBSnapshot.Location.QUERY) {
            query.put(authB.name(), authB.value());
        } else {
            headers.put(authB.name(), authB.value());
        }
        headers.putAll(extraHeaders(authB));
        return new UpstreamCredentials(Map.copyOf(headers), Map.copyOf(query));
    }

    private UpstreamCredentials http(AuthBSnapshot authB) {
        String scheme = authB.scheme() == null ? "bearer" : authB.scheme().trim().toLowerCase();
        Map<String, String> headers = new LinkedHashMap<>();
        if (scheme.equals("basic")) {
            String username = authB.username() == null ? "" : authB.username();
            requireCredential(authB.password(), "HTTP Basic 的密码");
            String raw = username + ":" + authB.password();
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else {
            requireCredential(authB.value(), "HTTP " + scheme + " 的凭据");
            headers.put("Authorization", capitalize(scheme) + " " + authB.value());
        }
        headers.putAll(extraHeaders(authB));
        return new UpstreamCredentials(Map.copyOf(headers), Map.of());
    }

    private UpstreamCredentials bearer(String token, AuthBSnapshot authB) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.putAll(extraHeaders(authB));
        return new UpstreamCredentials(Map.copyOf(headers), Map.of());
    }

    /**
     * 自定义 Header 模板：每行一个 {@code Name: Value}，支持
     * {@code {value}} {@code {username}} {@code {password}} {@code {clientId}} {@code {scope}} 占位符。
     *
     * <p>用模板而不是固定字段，是为了适配「签名头 + 时间戳 + 应用码」这类企业自定义方案，
     * 而不需要为每家上游改一次代码。
     */
    private UpstreamCredentials customHeader(AuthBSnapshot authB) {
        String template = authB.headerTemplate();
        if (template == null || template.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                    "CUSTOM_HEADER 鉴权缺少 headerTemplate 配置");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : template.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                log.warn("忽略无法解析的 headerTemplate 行（应为 Name: Value）：{}", LogSanitizer.sanitize(trimmed));
                continue;
            }
            String name = trimmed.substring(0, colon).trim();
            String value = renderPlaceholders(trimmed.substring(colon + 1).trim(), authB);
            headers.put(name, value);
        }
        headers.putAll(extraHeaders(authB));
        return new UpstreamCredentials(Map.copyOf(headers), Map.of());
    }

    private static String renderPlaceholders(String value, AuthBSnapshot authB) {
        return value
                .replace("{value}", nullToEmpty(authB.value()))
                .replace("{username}", nullToEmpty(authB.username()))
                .replace("{password}", nullToEmpty(authB.password()))
                .replace("{clientId}", nullToEmpty(authB.clientId()))
                .replace("{clientSecret}", nullToEmpty(authB.clientSecret()))
                .replace("{scope}", nullToEmpty(authB.scope()));
    }

    private static Map<String, String> extraHeaders(AuthBSnapshot authB) {
        List<AuthBSnapshot.ExtraHeader> extras = authB.extraHeaders();
        if (extras == null || extras.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (AuthBSnapshot.ExtraHeader extra : extras) {
            if (extra != null && extra.name() != null && !extra.name().isBlank()) {
                headers.put(extra.name().trim(), nullToEmpty(extra.value()));
            }
        }
        return headers;
    }

    // ------------------------------------------------------------------ OAuth2

    /** 阻塞方法，只在 boundedElastic 上调用。 */
    private String oauthToken(ServerSnapshot server, ToolSnapshot tool, AuthBSnapshot authB) {
        requireCredential(authB.tokenUrl(), "OAuth2 的 tokenUrl");
        requireCredential(authB.clientId(), "OAuth2 的 clientId");
        requireCredential(authB.clientSecret(), "OAuth2 的 clientSecret");
        String key = cacheKey(server, tool);
        Optional<String> cached = sharedState.getCachedToken(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        return sharedState.withLock(
                "oauth:" + key,
                properties.redis().lockWait(),
                properties.redis().lockLease(),
                () -> {
                    // 双重检查：等锁期间别的节点可能已经刷新完并写进了共享缓存
                    Optional<String> recheck = sharedState.getCachedToken(key);
                    if (recheck.isPresent()) {
                        return recheck.get();
                    }
                    TokenResponse token = exchange(authB);
                    sharedState.cacheToken(key, token.accessToken(), tokenTtl(token.expiresIn()));
                    log.info("已换取上游 OAuth2 令牌 server={} tool={} expiresIn={}s shared={}",
                            server.pathSegment(), tool == null ? "-" : tool.name(),
                            token.expiresIn(), sharedState.isShared());
                    return token.accessToken();
                },
                () -> sharedState.getCachedToken(key).orElseThrow(() -> McpErrorException.of(
                        HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                        "获取上游 OAuth2 令牌失败：等锁超时且缓存中没有可用令牌",
                        Map.of("server", server.pathSegment(), "sharedState", sharedState.mode()))));
    }

    private TokenResponse exchange(AuthBSnapshot authB) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        boolean basicAuth = authB.scheme() != null
                && authB.scheme().trim().equalsIgnoreCase("client_secret_basic");
        if (!basicAuth) {
            form.put("client_id", authB.clientId());
            form.put("client_secret", authB.clientSecret());
        }
        if (authB.scope() != null && !authB.scope().isBlank()) {
            form.put("scope", authB.scope().trim());
        }
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        try {
            JsonNode response = upstreamWebClient.post()
                    .uri(authB.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(headers -> {
                        if (basicAuth) {
                            String raw = authB.clientId() + ":" + authB.clientSecret();
                            headers.set("Authorization", "Basic " + Base64.getEncoder()
                                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
                        }
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.upstream().readTimeout());
            if (response == null || !response.hasNonNull("access_token")) {
                throw McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                        "上游令牌响应中没有 access_token 字段",
                        Map.of("tokenUrl", authB.tokenUrl()));
            }
            return new TokenResponse(response.get("access_token").asText(),
                    response.path("expires_in").asLong(0L));
        } catch (McpErrorException e) {
            throw e;
        } catch (RuntimeException e) {
            // 上游返回的错误体里可能带 client_secret 回显，必须脱敏后再落日志
            throw McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                    "向上游换取 OAuth2 令牌失败：" + LogSanitizer.sanitize(String.valueOf(e.getMessage())),
                    Map.of("tokenUrl", authB.tokenUrl()));
        }
    }

    /** 令牌缓存时长：以令牌自身的 expires_in 为准，扣掉刷新窗口，并不超过配置上限。 */
    private Duration tokenTtl(long expiresIn) {
        Duration configured = properties.redis().tokenTtl();
        if (expiresIn <= 0) {
            return configured;
        }
        Duration fromToken = Duration.ofSeconds(expiresIn).minus(REFRESH_SKEW);
        if (fromToken.compareTo(MIN_TOKEN_TTL) < 0) {
            fromToken = MIN_TOKEN_TTL;
        }
        return fromToken.compareTo(configured) < 0 ? fromToken : configured;
    }

    /**
     * 缓存键包含 serverId 与 toolId：Tool 级覆盖意味着不同 tool 用不同凭据，
     * 混用一个键会让 A 接口的令牌被发到 B 接口。
     */
    static String cacheKey(ServerSnapshot server, ToolSnapshot tool) {
        AuthBSnapshot override = tool == null ? null : tool.authBOverride();
        boolean toolLevel = override != null && override.type() != null
                && override.type() != AuthBSnapshot.Type.NONE;
        return toolLevel ? "s" + server.serverId() + ":t" + tool.name() : "s" + server.serverId();
    }

    private static void requireCredential(String value, String what) {
        if (value == null || value.isBlank()) {
            throw McpErrorException.of(HttpStatus.BAD_GATEWAY.value(), JsonRpcErrorCodes.UPSTREAM_ERROR,
                    "上行鉴权配置不完整：缺少" + what + "，请在 Manager 的「上行鉴权」中补齐后重新发布");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String capitalize(String scheme) {
        return scheme.isEmpty() ? scheme : Character.toUpperCase(scheme.charAt(0)) + scheme.substring(1);
    }

    private record TokenResponse(String accessToken, long expiresIn) {
    }
}