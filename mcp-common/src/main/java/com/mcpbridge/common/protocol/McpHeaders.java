package com.mcpbridge.common.protocol;

/**
 * MCP Streamable HTTP 传输层头部常量。
 *
 * <p>2026-07-28（SEP-2243）要求请求携带 {@code Mcp-Method} / {@code Mcp-Name}，
 * 使网关与限流设备可以按头路由，而无需解析请求体；同时协议层已移除会话
 * （SEP-2567），因此 {@code Mcp-Session-Id} 只作为 legacy 请求形态的识别特征使用。
 */
public final class McpHeaders {

    /** 请求方法名，如 {@code tools/call}。 */
    public static final String METHOD = "Mcp-Method";

    /** 目标对象名，如 tool 名称 / resource URI。 */
    public static final String NAME = "Mcp-Name";

    /** 客户端声明的协议版本。 */
    public static final String PROTOCOL_VERSION = "Mcp-Protocol-Version";

    /**
     * 已被 2026-07-28 移除的会话头。出现即视为 legacy 请求形态，
     * 由 Executor 显式拒绝（EXE-08），平台任何位置都不得依赖它做粘性路由。
     */
    public static final String SESSION_ID = "Mcp-Session-Id";

    /** list 类响应缓存提示（SEP-2549）。 */
    public static final String CACHE_TTL = "Mcp-Cache-Ttl-Ms";

    private McpHeaders() {
    }
}
