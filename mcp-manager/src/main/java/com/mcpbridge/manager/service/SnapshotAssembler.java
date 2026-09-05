package com.mcpbridge.manager.service;

import com.mcpbridge.common.snapshot.AuthBSnapshot;
import com.mcpbridge.common.snapshot.AuthDSnapshot;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.ToolSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.PublishBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 发布快照组装（PUB-03 / EXE-01）。
 *
 * <p>两个方向：
 * <ul>
 *   <li><b>单 Server → ServerSnapshot</b>：发布时把 base ⊕ overlay 的生效模型 + 解密后的 Auth-B
 *       固化成 JSON 存进 {@code publish_binding.snapshot}。固化而非实时计算，
 *       是为了让「已发布内容」不随后续编辑漂移，回滚才有意义（PUB-04）。</li>
 *   <li><b>集群 → PublishedSnapshot</b>：Executor 拉取时，把该集群全部 current+PUBLISHED 绑定的
 *       快照按 PATH 末段排序拼装，并计算稳定 etag 支持增量轮询。</li>
 * </ul>
 *
 * <p>etag 只由「集群名 + revision + 各 Server 的 pathSegment:bindingVersion」决定，
 * 不含生成时间，因此多 Manager 实例与重启后都能给出一致结果（EXE-01 的 304 语义依赖它）。
 */
@Service
public class SnapshotAssembler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAssembler.class);

    private final ServerService serverService;
    private final AuthConfigService authConfigService;
    private final OverlayService overlayService;

    public SnapshotAssembler(ServerService serverService,
                             AuthConfigService authConfigService,
                             OverlayService overlayService) {
        this.serverService = serverService;
        this.authConfigService = authConfigService;
        this.overlayService = overlayService;
    }

    /**
     * 组装单个 Server 的发布快照。
     *
     * @param enabledTools   仅启用的 tool，按 sortOrder 排序
     * @param bindingVersion 本次发布的版本号
     */
    public ServerSnapshot build(McpServer server, ExecutorCluster cluster, List<McpTool> enabledTools,
                                long bindingVersion, Instant publishedAt) {
        String endpoint = PathSegments.endpoint(cluster.getEntrypoint(), cluster.getPathPrefix(),
                server.getPathSegment());
        AuthBSnapshot authB = authConfigService.resolveAuthB(server);
        AuthDSnapshot authD = authConfigService.resolveAuthD(server, endpoint);
        List<com.mcpbridge.common.snapshot.UpstreamEntry> upstreams = serverService.upstreamEntriesOf(server);
        List<ToolSnapshot> tools = enabledTools.stream().map(overlayService::toSnapshot).toList();
        return new ServerSnapshot(
                server.getId(),
                server.getDeptId() == null ? 0L : server.getDeptId(),
                server.getName(),
                server.getPathSegment(),
                server.getTitle(),
                server.getDescription(),
                server.getVersion(),
                server.getProtocolVersion(),
                bindingVersion,
                endpoint,
                authD,
                authB,
                upstreams,
                tools,
                List.of(),
                List.of(),
                server.getListTtlMs(),
                publishedAt);
    }

    /** 组装集群快照：只纳入 current 且 PUBLISHED 的绑定。 */
    public PublishedSnapshot cluster(ExecutorCluster cluster, List<PublishBinding> currentBindings) {
        List<ServerSnapshot> servers = new ArrayList<>();
        for (PublishBinding binding : currentBindings) {
            if (binding.getState() != BindingState.PUBLISHED || binding.getSnapshot() == null) {
                continue;
            }
            try {
                ServerSnapshot snapshot = Json.read(binding.getSnapshot(), ServerSnapshot.class);
                if (snapshot != null) {
                    servers.add(snapshot);
                }
            } catch (RuntimeException e) {
                // 单个绑定损坏不应让整个集群快照不可用；记录后跳过，由运维按 bindingId 重发布
                log.error("发布快照反序列化失败，已跳过 bindingId={} serverId={}",
                        binding.getId(), binding.getServerId(), e);
            }
        }
        servers.sort(Comparator.comparing(ServerSnapshot::pathSegment,
                Comparator.nullsLast(Comparator.<String>naturalOrder())));
        long revision = cluster.getRevision();
        return new PublishedSnapshot(revision, etag(cluster.getName(), revision, servers),
                Instant.now(), cluster.getName(), servers);
    }

    /** 稳定 etag：与生成时间无关，可安全用于 If-None-Match。 */
    public static String etag(String clusterKey, long revision, List<ServerSnapshot> servers) {
        String fingerprint = servers.stream()
                .map(s -> s.pathSegment() + ":" + s.bindingVersion() + ":" + s.safeTools().size())
                .sorted()
                .collect(Collectors.joining(","));
        return "\"" + Hashing.shortSha256(clusterKey + "|" + revision + "|" + fingerprint) + "\"";
    }
}