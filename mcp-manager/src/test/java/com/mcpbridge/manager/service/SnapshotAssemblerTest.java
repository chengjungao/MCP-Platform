package com.mcpbridge.manager.service;

import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamEntry;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.PublishBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照指纹与集群 etag（EXE-01）。
 *
 * <p>这组测试盯的是一个<b>跨语言契约</b>：指纹算法同时存在于 Java 的
 * {@link SnapshotAssembler#fingerprint} 与 V6 迁移的回填 SQL 里。两边只要有一个字符不一致，
 * 历史行与新行的 etag 就会分叉——同一份快照算出两个 etag，Executor 的 304 永远不命中，
 * 两段式轮询静默退化成「每 10s 拉一次全量」。
 *
 * <p>另一个重点是「/revision 与 /snapshot 必须给出同一个 etag」：前者读 fingerprint 投影列，
 * 后者读反序列化后的 ServerSnapshot，两条路径必须收敛到同一个值。
 */
class SnapshotAssemblerTest {

    /** cluster() 只用到 cluster 的 name/revision，依赖的服务传 null 即可。 */
    private final SnapshotAssembler assembler = new SnapshotAssembler(null, null, null, null);

    @Test
    @DisplayName("指纹形状与 V6 回填 SQL 一致：pathSegment:version:toolCount")
    void fingerprintMatchesBackfillSqlShape() {
        String json = """
                {"serverId":1,"pathSegment":"order","bindingVersion":3,
                 "tools":[{"name":"getOrder"},{"name":"listOrder"}]}
                """;
        assertThat(SnapshotAssembler.fingerprint(json, 3)).isEqualTo("order:3:2");
    }

    @Test
    @DisplayName("序列化后的 ServerSnapshot 走同一算法，字段名漂移会立刻暴露")
    void fingerprintAgreesWithSerializedSnapshot() {
        ServerSnapshot snapshot = server("order", 5L, tool("getOrder"), tool("listOrder"));
        String json = Json.write(snapshot);

        assertThat(SnapshotAssembler.fingerprint(json, snapshot.bindingVersion()))
                .isEqualTo("order:5:2")
                .isEqualTo(snapshot.pathSegment() + ":" + snapshot.bindingVersion() + ":"
                        + snapshot.safeTools().size());
    }

    @Test
    @DisplayName("tools 缺失按 0 计（NON_NULL 序列化会省掉空数组），pathSegment 缺失按空串")
    void fingerprintToleratesMissingFields() {
        assertThat(SnapshotAssembler.fingerprint("{\"pathSegment\":\"order\"}", 1)).isEqualTo("order:1:0");
        assertThat(SnapshotAssembler.fingerprint("{}", 7)).isEqualTo(":7:0");
    }

    @Test
    @DisplayName("etag 与指纹顺序无关（/revision 的投影查询不保证行序）")
    void etagIsOrderIndependent() {
        List<String> forward = List.of("order:1:2", "user:3:4");
        List<String> backward = List.of("user:3:4", "order:1:2");

        assertThat(SnapshotAssembler.etag("shared", 9, forward))
                .isEqualTo(SnapshotAssembler.etag("shared", 9, backward));
    }

    @Test
    @DisplayName("etag 随 revision / 集群名 / 指纹集合变化，且 null 指纹不会炸")
    void etagChangesWithEveryInput() {
        String base = SnapshotAssembler.etag("shared", 9, List.of("order:1:2"));

        assertThat(SnapshotAssembler.etag("shared", 10, List.of("order:1:2"))).isNotEqualTo(base);
        assertThat(SnapshotAssembler.etag("private", 9, List.of("order:1:2"))).isNotEqualTo(base);
        assertThat(SnapshotAssembler.etag("shared", 9, List.of("order:1:3"))).isNotEqualTo(base);
        assertThat(SnapshotAssembler.etag("shared", 9, Arrays.asList("order:1:2", null))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("/snapshot 与 /revision 必须收敛到同一个 etag（否则轮询会空转）")
    void clusterSnapshotEtagMatchesRevisionEtag() {
        ServerSnapshot order = server("order", 5L, tool("getOrder"), tool("listOrder"));
        ServerSnapshot user = server("user", 2L, tool("getUser"));
        PublishBinding orderBinding = binding(order);
        PublishBinding userBinding = binding(user);

        ExecutorCluster cluster = cluster("shared", 7L);
        // /snapshot 路径：反序列化 + 排序，但 etag 取自绑定行的指纹列
        PublishedSnapshot published = assembler.cluster(cluster, List.of(orderBinding, userBinding));
        // /revision 路径：只读 fingerprint 投影列，行序不保证
        String revisionEtag = SnapshotAssembler.etag(cluster.getName(), cluster.getRevision(),
                List.of(userBinding.getFingerprint(), orderBinding.getFingerprint()));

        assertThat(published.etag()).isEqualTo(revisionEtag);
        assertThat(published.safeServers()).extracting(ServerSnapshot::pathSegment)
                .containsExactly("order", "user");
    }

    @Test
    @DisplayName("下线的绑定不进快照也不进 etag；损坏快照仍计入 etag 以免两端口径分叉")
    void clusterEtagSkipsOfflineButKeepsBrokenBindings() {
        PublishBinding published = binding(server("order", 1L, tool("getOrder")));

        PublishBinding offlined = binding(server("user", 1L, tool("getUser")));
        offlined.setState(BindingState.OFFLINE);

        PublishBinding broken = new PublishBinding();
        broken.setServerId(99L);
        broken.setClusterId(1L);
        broken.setVersion(1L);
        broken.setState(BindingState.PUBLISHED);
        broken.setCurrent(true);
        broken.setSnapshot("{ 这不是合法 JSON");
        broken.setFingerprint("ghost:1:0");

        ExecutorCluster cluster = cluster("shared", 3L);
        PublishedSnapshot snapshot = assembler.cluster(cluster, List.of(published, offlined, broken));

        assertThat(snapshot.safeServers()).extracting(ServerSnapshot::pathSegment).containsExactly("order");
        assertThat(snapshot.etag()).isEqualTo(SnapshotAssembler.etag("shared", 3L,
                List.of(published.getFingerprint(), broken.getFingerprint())));
    }

    // ------------------------------------------------------------------ 夹具

    private static ExecutorCluster cluster(String name, long revision) {
        ExecutorCluster cluster = new ExecutorCluster();
        cluster.setName(name);
        cluster.setRevision(revision);
        return cluster;
    }

    /** 模拟发布时落库的一行：snapshot 与 fingerprint 同源生成。 */
    private static PublishBinding binding(ServerSnapshot snapshot) {
        String json = Json.write(snapshot);
        PublishBinding binding = new PublishBinding();
        binding.setServerId(snapshot.serverId());
        binding.setClusterId(1L);
        binding.setVersion(snapshot.bindingVersion());
        binding.setState(BindingState.PUBLISHED);
        binding.setCurrent(true);
        binding.setSnapshot(json);
        binding.setFingerprint(SnapshotAssembler.fingerprint(json, snapshot.bindingVersion()));
        return binding;
    }

    private static ToolSnapshot tool(String name) {
        return new ToolSnapshot(name, null, null, "GET", "/x", "GET /x",
                null, Map.of(), false, false, null, true, "default", null);
    }

    private static ServerSnapshot server(String pathSegment, long bindingVersion, ToolSnapshot... tools) {
        return new ServerSnapshot(1L, 1L, "svc-" + pathSegment, pathSegment, "标题", null, "1.0",
                McpProtocol.SUPPORTED_VERSION, bindingVersion, "http://gw.local/mcp/" + pathSegment,
                null, null,
                List.of(UpstreamEntry.single("default", UpstreamSnapshot.defaults(List.of("http://up.local")))),
                List.of(tools), List.of(), List.of(), 30_000,
                Instant.parse("2026-09-10T00:00:00Z"));
    }
}
