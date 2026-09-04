package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 上行跳鉴权（Auth-B：Executor → 用户 REST API），对应 BR-4 与 SVR-03 的 6 种方案。
 *
 * <p>安全约束：本对象携带的是<b>已解密</b>的凭据，只允许出现在
 * 「Executor 以节点令牌拉取快照」的内部通道上（TLS + 最小授权），
 * 绝不能进入下行链路（MCP Client）、日志或工具调用结果（SEC-01 / SEC-02）。
 *
 * @param type          授权方式
 * @param in            apiKey 的注入位置（HEADER / QUERY）
 * @param name          header 名或 query 参数名
 * @param scheme        http 鉴权方案（bearer / basic）
 * @param value         apiKey 值或 bearer token 明文
 * @param username      basic 用户名
 * @param password      basic 密码
 * @param tokenUrl      oauth2 client_credentials 令牌端点
 * @param clientId      oauth2 client id
 * @param clientSecret  oauth2 client secret
 * @param scope         oauth2 scope（空格分隔）
 * @param headerTemplate 自定义 Header 模板，支持 {@code {{secret:xxx}}} 引用平台密钥
 * @param extraHeaders  需要随请求发送的附加固定 header
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthBSnapshot(
        Type type,
        Location in,
        String name,
        String scheme,
        String value,
        String username,
        String password,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String scope,
        String headerTemplate,
        List<ExtraHeader> extraHeaders) {

    public enum Type {
        /** 上游无需鉴权。 */
        NONE,
        /** API Key，注入 header 或 query。 */
        API_KEY,
        /** HTTP 鉴权：bearer / basic（scheme 字段区分）。 */
        HTTP,
        /** OAuth2 client_credentials，Executor 侧缓存令牌 + 分布式刷新锁（BR-6）。 */
        OAUTH2_CLIENT_CREDENTIALS,
        /** 自定义 Header 模板，可引用平台托管密钥。 */
        CUSTOM_HEADER
    }

    public enum Location {
        HEADER, QUERY
    }

    /** 附加固定 header（如 {@code X-Tenant}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExtraHeader(String name, String value) {
    }

    public static AuthBSnapshot none() {
        return new AuthBSnapshot(Type.NONE, null, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    public boolean requiresTokenExchange() {
        return type == Type.OAUTH2_CLIENT_CREDENTIALS;
    }
}
