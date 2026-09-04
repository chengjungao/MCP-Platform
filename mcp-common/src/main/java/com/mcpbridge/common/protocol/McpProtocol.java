package com.mcpbridge.common.protocol;

import java.util.Set;

/**
 * MCP 协议版本策略（决策 D1：Modern-only）。
 *
 * <p>平台全链路仅实现并应答 {@value #SUPPORTED_VERSION}；对任何 legacy 客户端
 * （{@code initialize} 握手 / {@code Mcp-Session-Id} 会话形态）显式拒绝，不做双栈、不做兼容转换。
 * 见 PRD §0.4、EXE-08、附录 A 与 docs/adr/ADR-0001-modern-only-protocol.md。
 */
public final class McpProtocol {

    /** 唯一支持的协议版本（MCP Specification 2026-07-28）。 */
    public static final String SUPPORTED_VERSION = "2026-07-28";

    /** 明确不再支持的旧版本，仅用于识别与埋点，不用于兼容。 */
    public static final Set<String> LEGACY_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            "2025-11-25");

    /** Tool inputSchema 使用的 JSON Schema 方言（2026-07-28 要求完整 JSON Schema 2020-12）。 */
    public static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** 协议升级引导 URL，随 legacy 拒绝响应一并返回（EXE-08 验收项 ③）。 */
    public static final String UPGRADE_GUIDE_URL =
            "https://modelcontextprotocol.io/specification/" + SUPPORTED_VERSION;

    /** list 类响应的默认缓存 TTL（毫秒），对应 SEP-2549 的 ttlMs。 */
    public static final int DEFAULT_LIST_TTL_MS = 30_000;

    private McpProtocol() {
    }

    /** 是否为受支持的协议版本。 */
    public static boolean isSupported(String version) {
        return SUPPORTED_VERSION.equals(version);
    }

    /** 是否为已知的 legacy 协议版本（用于埋点区分「旧版本」与「未知版本」）。 */
    public static boolean isKnownLegacy(String version) {
        return version != null && LEGACY_VERSIONS.contains(version);
    }
}
