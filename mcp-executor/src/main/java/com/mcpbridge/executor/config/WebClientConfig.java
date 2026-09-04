package com.mcpbridge.executor.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient 装配。
 *
 * <p>刻意分成两个客户端：
 * <ul>
 *   <li><b>managerWebClient</b>：固定 baseUrl 与节点令牌，只打内部通道。超时短、失败快速返回，
 *       因为它是后台轮询，拖久了会积压任务。</li>
 *   <li><b>upstreamWebClient</b>：不带 baseUrl（目标由快照里的 upstream.baseUrls 决定），
 *       读超时取兜底值；<b>每个 Server 的实际超时在调用时用 {@code responseTimeout} 覆盖</b>，
 *       因此慢接口不会拖累快接口。</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient managerWebClient(ExecutorProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.manager().connectTimeout().toMillis())
                .responseTimeout(properties.manager().readTimeout());
        return WebClient.builder()
                .baseUrl(stripTrailingSlash(properties.manager().baseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("X-Executor-Token", properties.manager().token())
                // 集群快照可能到几 MB，默认 256KB 的缓冲上限会直接抛 DataBufferLimitException
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient upstreamWebClient(ExecutorProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.upstream().connectTimeout().toMillis())
                .responseTimeout(properties.upstream().readTimeout())
                // 上游可能返回 chunked/SSE，关闭自动解压之外不做任何缓冲聚合
                .followRedirect(true);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(Math.max(properties.upstream().maxResponseBytes(), 256 * 1024)))
                .build();
    }

    /** baseUrl 末尾的斜杠会让 WebClient 拼出 {@code //internal/...}，部分网关会直接 404。 */
    private static String stripTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}