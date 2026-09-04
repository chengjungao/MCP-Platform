package com.mcpbridge.executor.upstream;

import java.util.List;
import java.util.Map;

/**
 * 已完成参数定位的上游 REST 请求。
 *
 * <p>这是「MCP 语义」与「HTTP 语义」之间的分界线：在它之前只有 tool 名和 arguments，
 * 在它之后只有方法、路径、查询串、头和体。{@code RestRequestBuilder} 负责跨越这条线，
 * {@code UpstreamInvoker} 只认这条线之后的东西——因此负载均衡、重试、熔断这些
 * 与业务无关的策略可以在完全不懂 MCP 的情况下被单测覆盖。
 *
 * @param method  上游 HTTP 方法（已大写）
 * @param path    上游路径，{@code {name}} 已替换为百分号编码后的实参，且以 {@code /} 开头
 * @param query   查询参数（保持插入顺序，重复名允许）
 * @param headers 请求头（不含上行鉴权头，那部分在调用时由 {@code UpstreamCredentials} 叠加）
 * @param body    请求体，null 表示无体
 */
public record RestRequest(
        String method,
        String path,
        List<QueryParam> query,
        Map<String, String> headers,
        Object body) {

    /**
     * @param name  参数名（原样，编码在拼 URI 时统一做）
     * @param value 参数值（已字符串化）
     */
    public record QueryParam(String name, String value) {
    }

    public boolean hasBody() {
        return body != null;
    }

    public boolean hasQuery() {
        return query != null && !query.isEmpty();
    }
}