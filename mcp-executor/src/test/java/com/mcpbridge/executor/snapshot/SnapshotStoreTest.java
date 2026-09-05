package com.mcpbridge.executor.snapshot;

import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地发布快照（EXE-01）。
 *
 * <p>这是 Executor 唯一的真相来源，也是「控制面故障不传导到数据面」的实现基础。
 * 两个必须钉死的行为：一是<b>未同步过时端点报 503 而不是回空清单</b>——
 * 空清单会被 MCP 客户端理解成「这个服务没有工具」，比报错更糟；
 * 二是<b>PATH 末段重复时保留前者并告警</b>——静默覆盖会让某个部门的端点悄悄指向别人的上游。
 */
class SnapshotStoreTest {

    private final SnapshotStore store = new SnapshotStore();

    @Test
    @DisplayName("首次同步前未就绪，端点据此返回 503 + Retry-After")
    void isNotReadyBeforeFirstSync() {
        assertThat(store.isReady()).isFalse();
        assertThat(store.revision()).isZero();
        assertThat(store.etag()).isEqualTo("\"0\"");
        assertThat(store.pathSegments()).isEmpty();
        assertThat(store.server("order")).isEmpty();

        SnapshotStore.Status status = store.status();
        assertThat(status.ready()).isFalse();
        assertThat(status.clusterKey()).isEqualTo("uninitialized");
        assertThat(status.lastSyncAt()).isNull();
        assertThat(status.staleSeconds()).isNull();
        assertThat(status.appliedRevisions()).isZero();
    }

    @Test
    @DisplayName("同步后就绪，按 PATH 末段建立索引并统计规模")
    void replacesSnapshotAndIndexesByPathSegment() {
        boolean advanced = store.replace(snapshot(7L, "\"7\"",
                server(10L, "order", tool("getOrder"), tool("createOrder")),
                server(11L, "user", tool("getUser"))));

        assertThat(advanced).isTrue();
        assertThat(store.isReady()).isTrue();
        assertThat(store.revision()).isEqualTo(7L);
        assertThat(store.etag()).isEqualTo("\"7\"");

        ServerSnapshot order = store.server("order").orElseThrow();
        assertThat(order.serverId()).isEqualTo(10L);
        assertThat(order.tool("getOrder")).isPresent();
        assertThat(order.tool("nope")).isEmpty();
        assertThat(store.pathSegments()).containsExactly("order", "user");

        SnapshotStore.Status status = store.status();
        assertThat(status.serverCount()).isEqualTo(2);
        assertThat(status.toolCount()).isEqualTo(3);
        assertThat(status.clusterKey()).isEqualTo("shared:default");
        assertThat(status.lastSyncAt()).isNotNull();
        assertThat(status.staleSeconds()).isNotNull();
        assertThat(status.appliedRevisions()).isEqualTo(1L);
    }

    @Test
    @DisplayName("同一版本重复替换不计入 appliedRevisions，避免日志与埋点被重复同步刷满")
    void doesNotCountRepeatedSameRevision() {
        store.replace(snapshot(7L, "\"7\"", server(10L, "order")));

        assertThat(store.replace(snapshot(7L, "\"7\"", server(10L, "order")))).isFalse();
        assertThat(store.status().appliedRevisions()).isEqualTo(1L);
    }

    @Test
    @DisplayName("PATH 末段重复时保留前者，绝不静默把端点指向别人的上游")
    void keepsFirstOnDuplicatePathSegment() {
        store.replace(snapshot(1L, "\"1\"", server(10L, "dup"), server(11L, "dup")));

        assertThat(store.server("dup").orElseThrow().serverId()).isEqualTo(10L);
        assertThat(store.pathSegments()).containsExactly("dup");
    }

    @Test
    @DisplayName("缺少 PATH 末段的 Server 不进索引，不会造成 null 键")
    void skipsServersWithoutPathSegment() {
        store.replace(snapshot(1L, "\"1\"", server(10L, null), server(11L, "user")));

        assertThat(store.pathSegments()).containsExactly("user");
        assertThat(store.status().serverCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("替换为 null 是空操作：轮询拿到空响应不该把已发布端点抹掉")
    void ignoresNullSnapshot() {
        assertThat(store.replace(null)).isFalse();
        assertThat(store.isReady()).isFalse();

        store.replace(snapshot(1L, "\"1\"", server(10L, "order")));
        assertThat(store.replace(null)).isFalse();
        assertThat(store.server("order")).isPresent();
    }

    @Test
    @DisplayName("整体替换会重建索引：下线的 Server 立刻不可路由")
    void rebuildsWholeIndexOnReplace() {
        store.replace(snapshot(1L, "\"1\"", server(10L, "order")));
        store.replace(snapshot(2L, "\"2\"", server(11L, "user")));

        assertThat(store.server("order")).isEmpty();
        assertThat(store.server("user")).isPresent();
        assertThat(store.pathSegments()).containsExactly("user");
        assertThat(store.status().appliedRevisions()).isEqualTo(2L);
    }

    @Test
    @DisplayName("同步失败记录原因并出现在健康视图里；下一次成功同步即清除")
    void recordsAndClearsLastError() {
        store.replace(snapshot(1L, "\"1\"", server(10L, "order")));

        store.recordError("Manager 不可达");
        assertThat(store.status().lastError()).isEqualTo("Manager 不可达");
        // 同步失败不清空已有快照：Manager 挂了也要继续用旧快照服务
        assertThat(store.server("order")).isPresent();

        store.replace(snapshot(2L, "\"2\"", server(10L, "order")));
        assertThat(store.status().lastError()).isNull();
    }

    @Test
    @DisplayName("未知末段查不到 Server，由端点层回 -32001")
    void returnsEmptyForUnknownPathSegment() {
        store.replace(snapshot(1L, "\"1\"", server(10L, "order")));

        assertThat(store.server("nope")).isEmpty();
        assertThat(store.server(null)).isEmpty();
    }

    // ------------------------------------------------------------------ 夹具

    private static ToolSnapshot tool(String name) {
        return new ToolSnapshot(name, null, null, "GET", "/x", "GET /x",
                null, Map.of(), false, false, null, true, "default", null);
    }

    private static ServerSnapshot server(long serverId, String pathSegment, ToolSnapshot... tools) {
        return new ServerSnapshot(serverId, 1L, "svc-" + serverId, pathSegment, "标题", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, 1L, "http://gw.local/mcp/" + pathSegment,
                null, null,
                List.of(com.mcpbridge.common.snapshot.UpstreamEntry.single("default",
                        UpstreamSnapshot.defaults(List.of("http://up.local")))),
                List.of(tools), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-03T00:00:00Z"));
    }

    private static PublishedSnapshot snapshot(long revision, String etag, ServerSnapshot... servers) {
        return new PublishedSnapshot(revision, etag, Instant.parse("2026-09-03T00:00:00Z"),
                "shared:default", List.of(servers));
    }
}