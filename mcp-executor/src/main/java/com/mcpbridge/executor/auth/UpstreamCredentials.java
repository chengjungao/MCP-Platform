package com.mcpbridge.executor.auth;

import java.util.Map;

/**
 * 上行鉴权（Auth-B）解析结果。
 *
 * <p>拆成 headers 与 query 两部分，是因为 API Key 模式允许把凭据放查询串
 * （很多企业内网网关只认这种），而放查询串的凭据<b>会进上游访问日志</b>——
 * 因此 UI 上选 QUERY 时必须给出风险提示，这里则在结构上把两者分开，避免混用。
 *
 * @param headers 要注入上游请求的头（已含 extraHeaders）
 * @param query   要追加到上游查询串的参数
 */
public record UpstreamCredentials(Map<String, String> headers, Map<String, String> query) {

    public static UpstreamCredentials empty() {
        return new UpstreamCredentials(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return headers.isEmpty() && query.isEmpty();
    }
}