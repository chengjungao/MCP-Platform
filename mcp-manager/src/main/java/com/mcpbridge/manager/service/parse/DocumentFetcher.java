package com.mcpbridge.manager.service.parse;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.config.ManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 从 URL 拉取接口文档（REG-01）。
 *
 * <p>安全要点：
 * <ul>
 *   <li>只允许 http/https；</li>
 *   <li><b>SSRF 防护</b>：默认拒绝回环、链路本地、站点本地与任意地址，
 *       需要联调内网文档时显式打开 {@code mcp.manager.parse.allow-private-networks}；</li>
 *   <li>不跟随重定向，避免绕过地址校验跳到内网；</li>
 *   <li>响应体大小受 {@code max-document-bytes} 限制，防止内存打爆。</li>
 * </ul>
 *
 * <p>本组件不做事务，也不落库，方便与 {@link SwaggerParseService} 组合成「抓取 → 解析」两步。
 */
@Component
public class DocumentFetcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentFetcher.class);

    /**
     * @param url         实际请求地址（重定向关闭，与入参一致）
     * @param contentType 响应 Content-Type
     * @param body        文档原文
     */
    public record FetchResult(String url, String contentType, String body) {
    }

    private final ManagerProperties properties;
    private final HttpClient httpClient;

    public DocumentFetcher(ManagerProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchResult fetch(String url) {
        URI uri = requireSafeUri(url);
        Duration timeout = properties.parse().fetchTimeout();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json, application/openapi+json, application/yaml, text/yaml, */*")
                .header("User-Agent", "mcp-manager/registration")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new PlatformException(ErrorCode.PARSE_FAILED,
                        "拉取接口文档失败：上游返回 " + response.statusCode(),
                        Map.of("url", uri.toString(), "status", response.statusCode()));
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            long limit = properties.parse().maxDocumentBytes();
            if (body.length > limit) {
                throw PlatformException.validation("接口文档超过大小上限",
                        Map.of("limitBytes", limit, "actualBytes", body.length, "url", uri.toString()));
            }
            String text = new String(body, StandardCharsets.UTF_8);
            log.info("已拉取接口文档 url={} bytes={} contentType={}", uri, body.length,
                    response.headers().firstValue("Content-Type").orElse("unknown"));
            return new FetchResult(uri.toString(),
                    response.headers().firstValue("Content-Type").orElse(null), text);
        } catch (PlatformException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(ErrorCode.PARSE_FAILED, "拉取接口文档被中断", e);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.PARSE_FAILED,
                    "拉取接口文档失败：" + e.getClass().getSimpleName(), e,
                    Map.of("url", uri.toString()));
        }
    }

    private URI requireSafeUri(String url) {
        if (url == null || url.isBlank()) {
            throw PlatformException.validation("文档地址不能为空", Map.of("field", "url"));
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw PlatformException.validation("文档地址格式非法", Map.of("field", "url", "value", url));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw PlatformException.validation("只支持 http/https 协议的文档地址",
                    Map.of("field", "url", "scheme", scheme));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw PlatformException.validation("文档地址缺少主机名", Map.of("field", "url", "value", url));
        }
        if (!properties.parse().allowPrivateNetworks()) {
            checkNotPrivate(host);
        }
        return uri;
    }

    private void checkNotPrivate(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw PlatformException.validation("文档地址主机名无法解析",
                    Map.of("field", "url", "host", host));
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || isCarrierGradeNat(address)) {
                throw PlatformException.forbidden(
                        "出于 SSRF 防护，默认拒绝拉取内网/回环地址的文档；"
                                + "如确需访问请设置 MCP_MANAGER_PARSE_ALLOW_PRIVATE_NETWORKS=true");
            }
        }
    }

    /** 100.64.0.0/10 运营商级 NAT 段，isSiteLocalAddress 不覆盖。 */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127;
    }
}