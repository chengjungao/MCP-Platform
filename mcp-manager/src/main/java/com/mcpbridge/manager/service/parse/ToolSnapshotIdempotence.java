package com.mcpbridge.manager.service.parse;

import com.mcpbridge.common.snapshot.ToolSnapshot;

import java.util.Locale;

/**
 * 幂等性判定（EXE-03）。
 *
 * <p>P0 只按 HTTP 方法判定：GET/HEAD/OPTIONS/PUT/DELETE 视为幂等，POST/PATCH 不自动重试。
 * 后续可在 tool 覆盖里显式声明 {@code idempotent=true}（例如带幂等键的 POST）。
 */
final class ToolSnapshotIdempotence {

    private ToolSnapshotIdempotence() {
    }

    static boolean of(String method) {
        return method != null && ToolSnapshot.IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }
}