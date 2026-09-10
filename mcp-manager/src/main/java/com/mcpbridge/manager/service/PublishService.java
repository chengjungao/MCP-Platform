package com.mcpbridge.manager.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.protocol.McpProtocol;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import com.mcpbridge.common.snapshot.UpstreamSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ClusterQuota;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.domain.OverlayStatus;
import com.mcpbridge.manager.domain.PublishBinding;
import com.mcpbridge.manager.domain.ServerStatus;
import com.mcpbridge.manager.repository.McpPromptRepository;
import com.mcpbridge.manager.repository.McpResourceRepository;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.PublishDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布、下线与回滚（PUB-01 / PUB-03 / PUB-04 / PUB-05）。
 *
 * <p>发布模型是<b>版本化绑定</b>：一个 (Server, Cluster) 下可以有多条 {@code publish_binding}，
 * 只有 {@code is_current = true} 且 {@code state = PUBLISHED} 的那条对 Executor 可见。
 * 每次发布都新增一条并把旧的那条置为非当前，因此：
 * <ul>
 *   <li>回滚不是「改回来」，而是把历史版本的快照<b>复制成新版本</b>再发布，历史链条完整可审计；</li>
 *   <li>快照内容在发布时固化（含解密后的 Auth-B），后续编辑不影响线上，必须再次发布才生效；</li>
 *   <li>集群 {@code revision} 单调递增，Executor 轮询比对即可在 30s 内感知变更（PUB-04）。</li>
 * </ul>
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    private final PublishBindingRepository bindingRepository;
    private final McpServerRepository serverRepository;
    private final McpResourceRepository resourceRepository;
    private final McpPromptRepository promptRepository;
    private final ServerService serverService;
    private final ClusterService clusterService;
    private final SnapshotAssembler snapshotAssembler;
    private final AuditService auditService;

    public PublishService(PublishBindingRepository bindingRepository,
                          McpServerRepository serverRepository,
                          McpResourceRepository resourceRepository,
                          McpPromptRepository promptRepository,
                          ServerService serverService,
                          ClusterService clusterService,
                          SnapshotAssembler snapshotAssembler,
                          AuditService auditService) {
        this.bindingRepository = bindingRepository;
        this.serverRepository = serverRepository;
        this.resourceRepository = resourceRepository;
        this.promptRepository = promptRepository;
        this.serverService = serverService;
        this.clusterService = clusterService;
        this.snapshotAssembler = snapshotAssembler;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 发布

    @Transactional
    public PublishDtos.PublishResult publish(Long serverId, PublishDtos.PublishRequest request,
                                             AuthPrincipal principal) {
        McpServer server = serverService.requireManage(serverId, principal);
        ExecutorCluster cluster = clusterService.require(request.clusterId());
        clusterService.requirePublishPermission(cluster, server.getDeptId(), principal);

        // 先取出启用 tool 列表：发布前校验与快照组装共用同一份，省一次查询也保证口径一致
        List<McpTool> enabledTools = serverService.enabledTools(serverId);
        requirePublishable(server, cluster, enabledTools);

        Instant now = Instant.now();
        long version = nextVersion(serverId, cluster.getId());
        ServerSnapshot snapshot = snapshotAssembler.build(server, cluster, enabledTools, version, now);

        demoteCurrent(serverId, cluster.getId());
        PublishBinding binding = new PublishBinding();
        binding.setServerId(serverId);
        binding.setClusterId(cluster.getId());
        binding.setVersion(version);
        binding.setState(BindingState.PUBLISHED);
        binding.setCurrent(true);
        String snapshotJson = Json.write(snapshot);
        binding.setSnapshot(snapshotJson);
        binding.setFingerprint(SnapshotAssembler.fingerprint(snapshotJson, version));
        binding.setPublishedBy(principal.userId());
        binding.setPublishedAt(now);
        PublishBinding saved = bindingRepository.save(binding);

        server.setStatus(ServerStatus.PUBLISHED);
        serverRepository.save(server);
        long revision = bumpRevision(cluster);

        long suspended = enabledTools.stream().filter(t -> t.getOverlayStatus() == OverlayStatus.SUSPENDED).count();
        String message = suspended > 0
                ? "发布成功，但有 " + suspended + " 项覆盖处于挂起状态（锚点在上游文档中已消失），请在差异视图中确认"
                : "发布成功，Executor 将在下一次轮询（≤30s）内加载新版本";
        auditService.record(AuditAction.PUBLISH, "server", serverId, publishDetail(server, cluster, version, revision,
                request.note(), enabledTools.size(), suspended));
        log.info("发布完成 serverId={} cluster={} version={} revision={} tools={}",
                serverId, cluster.getName(), version, revision, enabledTools.size());
        return new PublishDtos.PublishResult(saved.getId(), version, snapshot.endpoint(),
                cluster.getName(), BindingState.PUBLISHED, revision, message);
    }

    /**
     * PUB-01 发布前校验：宁可拦住，也不要把一个必然 500 的端点推给客户端。
     *
     * @param enabledTools 调用方已取出的启用 tool 列表，避免在这里重复查一次库
     */
    private void requirePublishable(McpServer server, ExecutorCluster cluster, List<McpTool> enabledTools) {
        Map<String, Object> problems = new LinkedHashMap<>();
        if (!McpProtocol.isSupported(server.getProtocolVersion())) {
            problems.put("protocolVersion", "Server 协议版本必须是 " + McpProtocol.SUPPORTED_VERSION);
        }
        // 多上游校验：每个 upstream 的 baseUrls 都不能为空
        List<UpstreamSnapshot> upstreams = serverService.upstreamConfigsOf(server);
        if (upstreams.isEmpty()) {
            problems.put("upstreams", "未配置任何 REST 服务，Executor 无法转发调用");
        }
        for (int i = 0; i < upstreams.size(); i++) {
            UpstreamSnapshot u = upstreams.get(i);
            if (u.baseUrls() == null || u.baseUrls().isEmpty()) {
                problems.put("upstream[" + i + "].baseUrls", "REST 服务 baseUrls 为空");
            }
        }
        if (enabledTools.isEmpty()) {
            problems.put("tools", "没有任何启用状态的 tool，发布后 tools/list 将为空");
        }
        Set<String> duplicates = serverService.duplicateEffectiveNames(server.getId());
        if (!duplicates.isEmpty()) {
            problems.put("duplicateToolNames", duplicates);
        }
        // BR-3：末段唯一性在写入时已校验，这里再确认一次，防止并发注册产生同末段
        List<McpServer> sameSegment = serverRepository.findByPathSegment(server.getPathSegment());
        if (sameSegment.size() > 1) {
            problems.put("pathSegment", "PATH 末段 " + server.getPathSegment() + " 被多个 Server 占用");
        }
        if (!PathSegments.isValid(server.getPathSegment())) {
            problems.put("pathSegment", "PATH 末段不合法");
        }
        problems.putAll(quotaProblems(server, cluster, enabledTools));
        if (!problems.isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "发布前校验未通过", problems);
        }
    }

    /**
     * 集群发布配额校验（PUB-01）。
     *
     * <p>配额是集群级的容量上限，此前 {@code executor_cluster.scopes} 字段只是「存了但从不校验」，
     * 这里补上唯一缺失的读取方。判定规则本身在 {@link ClusterQuota#violations}（纯函数、可穷举单测），
     * 本方法只负责把三处用量查出来。
     *
     * <p>用量口径刻意与「发布出去的东西」对齐：tool 只数<b>启用</b>的（停用的不进快照、不占容量），
     * Resource/Prompt 数全部（它们不分启用/停用）。
     */
    private Map<String, Object> quotaProblems(McpServer server, ExecutorCluster cluster, List<McpTool> enabledTools) {
        ClusterQuota quota = clusterService.quotaOf(cluster);
        if (quota.isUnlimited()) {
            return Map.of();
        }
        long publishedServers = bindingRepository.countByClusterIdAndCurrentTrueAndState(
                cluster.getId(), BindingState.PUBLISHED);
        boolean alreadyPublished = bindingRepository
                .findByServerIdAndClusterIdAndCurrentTrue(server.getId(), cluster.getId())
                .isPresent();
        long catalogCount = resourceRepository.countByServerId(server.getId())
                + promptRepository.countByServerId(server.getId());
        return quota.violations(publishedServers, alreadyPublished, enabledTools.size(), catalogCount);
    }

    // ------------------------------------------------------------------ 下线

    @Transactional
    public PublishDtos.PublishResult offline(Long serverId, Long clusterId, AuthPrincipal principal) {
        McpServer server = serverService.requireManage(serverId, principal);
        ExecutorCluster cluster = clusterService.require(clusterId);
        clusterService.requirePublishPermission(cluster, server.getDeptId(), principal);
        PublishBinding binding = bindingRepository.findByServerIdAndClusterIdAndCurrentTrue(serverId, clusterId)
                .orElseThrow(() -> new PlatformException(ErrorCode.INVALID_STATE,
                        "该 Server 未在此集群发布，无需下线",
                        Map.of("serverId", serverId, "clusterId", clusterId)));

        binding.setState(BindingState.OFFLINE);
        binding.setCurrent(false);
        binding.setOfflinedAt(Instant.now());
        bindingRepository.save(binding);

        boolean stillPublished = bindingRepository.findByServerIdOrderByIdDesc(serverId).stream()
                .anyMatch(b -> b.isCurrent() && b.getState() == BindingState.PUBLISHED);
        server.setStatus(stillPublished ? ServerStatus.PUBLISHED : ServerStatus.OFFLINE);
        serverRepository.save(server);
        long revision = bumpRevision(cluster);

        auditService.record(AuditAction.OFFLINE, "server", serverId, Map.of(
                "cluster", cluster.getName(),
                "bindingId", binding.getId(),
                "version", binding.getVersion(),
                "revision", revision));
        return new PublishDtos.PublishResult(binding.getId(), binding.getVersion(), null, cluster.getName(),
                BindingState.OFFLINE, revision, "已下线，Executor 将在下一次轮询（≤30s）内移除该端点");
    }

    // ------------------------------------------------------------------ 回滚

    /** PUB-04：回滚到历史版本，实现为「以历史快照内容创建一个新版本」。 */
    @Transactional
    public PublishDtos.PublishResult rollback(Long serverId, Long clusterId,
                                              PublishDtos.RollbackRequest request, AuthPrincipal principal) {
        McpServer server = serverService.requireManage(serverId, principal);
        ExecutorCluster cluster = clusterService.require(clusterId);
        clusterService.requirePublishPermission(cluster, server.getDeptId(), principal);

        PublishBinding target = bindingRepository
                .findByServerIdAndClusterIdOrderByVersionDesc(serverId, clusterId).stream()
                .filter(b -> b.getVersion() == request.version())
                .findFirst()
                .orElseThrow(() -> PlatformException.notFound("发布版本", request.version()));
        if (target.getSnapshot() == null || target.getSnapshot().isBlank()) {
            throw new PlatformException(ErrorCode.INVALID_STATE, "该版本没有可用的快照内容，无法回滚",
                    Map.of("version", request.version()));
        }

        long currentVersion = bindingRepository.findByServerIdAndClusterIdAndCurrentTrue(serverId, clusterId)
                .map(PublishBinding::getVersion).orElse(0L);
        if (currentVersion == request.version()) {
            throw PlatformException.conflict("目标版本就是当前生效版本，无需回滚",
                    Map.of("version", request.version()));
        }

        Instant now = Instant.now();
        long version = nextVersion(serverId, clusterId);
        demoteCurrent(serverId, clusterId);

        PublishBinding binding = new PublishBinding();
        binding.setServerId(serverId);
        binding.setClusterId(clusterId);
        binding.setVersion(version);
        binding.setState(BindingState.PUBLISHED);
        binding.setCurrent(true);
        // 快照里的 bindingVersion 要指向新版本，否则 etag 与实际生效版本不一致
        String snapshotJson = withBindingVersion(target.getSnapshot(), version, now);
        binding.setSnapshot(snapshotJson);
        binding.setFingerprint(SnapshotAssembler.fingerprint(snapshotJson, version));
        binding.setPublishedBy(principal.userId());
        binding.setPublishedAt(now);
        PublishBinding saved = bindingRepository.save(binding);

        server.setStatus(ServerStatus.PUBLISHED);
        serverRepository.save(server);
        long revision = bumpRevision(cluster);

        auditService.record(AuditAction.ROLLBACK, "server", serverId, Map.of(
                "cluster", cluster.getName(),
                "fromVersion", currentVersion,
                "toVersion", request.version(),
                "newVersion", version,
                "revision", revision,
                "bindingId", saved.getId()));
        log.info("回滚完成 serverId={} cluster={} {} -> {} (新版本 {})",
                serverId, cluster.getName(), currentVersion, request.version(), version);
        return new PublishDtos.PublishResult(saved.getId(), version,
                PathSegments.endpoint(cluster.getEntrypoint(), cluster.getPathPrefix(), server.getPathSegment()),
                cluster.getName(), BindingState.PUBLISHED, revision,
                "已回滚到版本 " + request.version() + "（登记为新版本 " + version + "）");
    }

    // ------------------------------------------------------------------ 快照查询

    /** Executor 内部通道拉取的集群快照（EXE-01）。 */
    @Transactional(readOnly = true)
    public PublishedSnapshot clusterSnapshot(Long clusterId) {
        ExecutorCluster cluster = clusterService.require(clusterId);
        List<PublishBinding> bindings = bindingRepository.findByClusterIdAndCurrentTrue(clusterId);
        return snapshotAssembler.cluster(cluster, bindings);
    }

    /**
     * 集群快照 etag 的轻量复算（EXE-01）。
     *
     * <p>{@code /revision} 每 10s 被每个 Executor 打一次，只需要一个字符串。这里刻意只查
     * {@code fingerprint} 投影列——不 detoast jsonb、不反序列化、不排序对象，
     * 与 {@link #clusterSnapshot} 算出的 etag 是同一份算法、同一份数据，因此两端口径必然一致。
     */
    @Transactional(readOnly = true)
    public String clusterEtag(ExecutorCluster cluster) {
        return SnapshotAssembler.etag(cluster.getName(), cluster.getRevision(),
                bindingRepository.findCurrentFingerprints(cluster.getId(), BindingState.PUBLISHED));
    }

    @Transactional(readOnly = true)
    public List<PublishDtos.BindingView> history(Long serverId, Long clusterId, AuthPrincipal principal) {
        McpServer server = serverService.requireManage(serverId, principal);
        ExecutorCluster cluster = clusterService.require(clusterId);
        return bindingRepository.findByServerIdAndClusterIdOrderByVersionDesc(serverId, clusterId).stream()
                .map(b -> serverService.toBindingView(b, server, cluster))
                .toList();
    }

    // ------------------------------------------------------------------ 内部工具

    private Map<String, Object> publishDetail(McpServer server, ExecutorCluster cluster, long version, long revision,
                                              String note, int toolCount, long suspendedOverlays) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cluster", cluster.getName());
        detail.put("version", version);
        detail.put("revision", revision);
        detail.put("pathSegment", server.getPathSegment());
        detail.put("endpoint", PathSegments.endpoint(cluster.getEntrypoint(), cluster.getPathPrefix(),
                server.getPathSegment()));
        detail.put("toolCount", toolCount);
        detail.put("suspendedOverlays", suspendedOverlays);
        if (note != null && !note.isBlank()) {
            detail.put("note", note.trim());
        }
        return detail;
    }

    private long nextVersion(Long serverId, Long clusterId) {
        return bindingRepository.findTopByServerIdAndClusterIdOrderByVersionDesc(serverId, clusterId)
                .map(b -> b.getVersion() + 1)
                .orElse(1L);
    }

    private void demoteCurrent(Long serverId, Long clusterId) {
        bindingRepository.findByServerIdAndClusterIdAndCurrentTrue(serverId, clusterId).ifPresent(previous -> {
            previous.setCurrent(false);
            bindingRepository.save(previous);
        });
    }

    private long bumpRevision(ExecutorCluster cluster) {
        cluster.setRevision(cluster.getRevision() + 1);
        clusterService.save(cluster);
        return cluster.getRevision();
    }

    private static String withBindingVersion(String snapshotJson, long version, Instant publishedAt) {
        ObjectNode node = (ObjectNode) Json.tree(snapshotJson);
        node.put("bindingVersion", version);
        node.put("publishedAt", publishedAt.toString());
        return Json.write(node);
    }
}