package com.mcpbridge.executor.upstream;

import org.springframework.http.HttpHeaders;

/**
 * 上游响应（EXE-03）。
 *
 * @param status    HTTP 状态码
 * @param headers   响应头
 * @param body      响应体文本，已按 {@code mcp.executor.upstream.max-response-bytes} 截断
 * @param truncated body 是否被截断——必须如实回传给调用方，
 *                  否则用户拿到半截 JSON 会以为上游坏了
 */
public record UpstreamResponse(int status, HttpHeaders headers, String body, boolean truncated) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** 5xx 与连接失败一并计入熔断失败；4xx 是「上游明确回答了不」，不算链路故障。 */
    public boolean isServerError() {
        return status >= 500;
    }

    public String contentType() {
        return headers == null ? null : headers.getFirst(HttpHeaders.CONTENT_TYPE);
    }
}