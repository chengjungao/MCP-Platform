package com.mcpbridge.common.protocol;

import java.util.Set;

/**
 * MCP 方法名常量。
 *
 * <p>P0 实现范围：{@code server/discover}、{@code tools/list}、{@code tools/call}、{@code ping}；
 * {@code resources/*}、{@code prompts/*} 为 P1（SVR-05 / SVR-06），当前返回空目录 + ttlMs。
 */
public final class McpMethods {

    /** 服务器能力发现（2026-07-28 取代 initialize 握手）。 */
    public static final String SERVER_DISCOVER = "server/discover";

    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";

    public static final String RESOURCES_LIST = "resources/list";
    public static final String RESOURCES_READ = "resources/read";

    public static final String PROMPTS_LIST = "prompts/list";
    public static final String PROMPTS_GET = "prompts/get";

    public static final String PING = "ping";

    /**
     * 已被 SEP-2575 移除的握手方法。收到即判定为 legacy 客户端，
     * 返回 {@code UnsupportedProtocolVersionError}（EXE-08）。
     */
    public static final String LEGACY_INITIALIZE = "initialize";

    /** 已被移除的握手通知。 */
    public static final String LEGACY_INITIALIZED = "notifications/initialized";

    /** 出现即视为 legacy 请求形态的方法集合（SEP-2575 已移除的握手方法与通知）。 */
    public static final Set<String> LEGACY_METHODS = Set.of(
            LEGACY_INITIALIZE,
            LEGACY_INITIALIZED);

    private McpMethods() {
    }

    /** 该方法是否属于 legacy 请求形态。 */
    public static boolean isLegacy(String method) {
        return method != null && LEGACY_METHODS.contains(method);
    }
}
