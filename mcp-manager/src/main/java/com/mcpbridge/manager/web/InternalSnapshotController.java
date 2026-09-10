package com.mcpbridge.manager.web;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.security.NodePrincipal;
import com.mcpbridge.manager.service.ClusterService;
import com.mcpbridge.manager.service.PublishService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发布快照下发内部通道（EXE-01）。
 *
 * <p>两个端点分工明确：
 * <ul>
 *   <li>{@code /revision}：只回 {@code clusterKey + revision + etag} 三个标量，是 Executor 的
 *       高频轮询目标（10s 级）。<b>不读 jsonb</b>——etag 由 {@code publish_binding.fingerprint}
 *       投影列算出，代价是一次索引查询加一次短字符串哈希；</li>
 *   <li>{@code /snapshot}：回完整快照。带 {@code If-None-Match} 且匹配时返回 304 空体，
 *       避免每 10 秒把几 MB 的 JSON 重复推给每个节点。</li>
 * </ul>
 *
 * <p>隔离（R6）：URL 里的 clusterId 必须与令牌归属的集群一致；引导令牌不能拉快照，
 * 因为它还不知道自己属于哪个集群。
 */
@RestController
@RequestMapping("/internal/v1/clusters")
public class InternalSnapshotController {

    private final PublishService publishService;
    private final ClusterService clusterService;

    public InternalSnapshotController(PublishService publishService, ClusterService clusterService) {
        this.publishService = publishService;
        this.clusterService = clusterService;
    }

    /**
     * 轻量轮询端点：只回标量，不碰 {@code publish_binding.snapshot}。
     *
     * <p>改造前这里走的是集群全量装配（detoast 全部 jsonb + 反序列化 + 排序 + 算 etag），
     * 与「每 10s 每节点一次」的调用频次完全不匹配。现在读的是
     * {@code executor_cluster} 的两个标量列 + 一次 {@code fingerprint} 投影查询。
     */
    @GetMapping("/{clusterId}/revision")
    public Map<String, Object> revision(@PathVariable Long clusterId,
                                        @AuthenticationPrincipal NodePrincipal principal) {
        ExecutorCluster cluster = requireCluster(clusterId, principal);
        return Map.of(
                "clusterKey", cluster.getName(),
                "revision", cluster.getRevision(),
                "etag", publishService.clusterEtag(cluster));
    }

    @GetMapping("/{clusterId}/snapshot")
    public ResponseEntity<PublishedSnapshot> snapshot(@PathVariable Long clusterId,
                                                      @AuthenticationPrincipal NodePrincipal principal,
                                                      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH,
                                                              required = false) String ifNoneMatch) {
        PublishedSnapshot snapshot = snapshot(clusterId, principal);
        if (ifNoneMatch != null && !ifNoneMatch.isBlank() && etagMatches(ifNoneMatch, snapshot.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, snapshot.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, snapshot.etag())
                .header("X-Mcp-Revision", String.valueOf(snapshot.revision()))
                .body(snapshot);
    }

    private PublishedSnapshot snapshot(Long clusterId, NodePrincipal principal) {
        requireCluster(clusterId, principal);
        return publishService.clusterSnapshot(clusterId);
    }

    /** 令牌归属集群与请求的集群必须一致；引导令牌一律拒绝。返回集群供调用方复用。 */
    private ExecutorCluster requireCluster(Long clusterId, NodePrincipal principal) {
        if (principal == null) {
            throw new PlatformException(ErrorCode.UNAUTHENTICATED, "缺少节点身份");
        }
        if (principal.bootstrap() || principal.clusterId() == null) {
            throw new PlatformException(ErrorCode.FORBIDDEN,
                    "引导令牌只能用于节点注册，拉取快照请使用集群令牌",
                    Map.of("hint", "先调用 POST /internal/v1/nodes/register 获取集群归属"));
        }
        if (!principal.clusterId().equals(clusterId)) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "令牌不属于该集群，拒绝跨集群拉取快照",
                    Map.of("tokenClusterId", principal.clusterId(), "requestedClusterId", clusterId));
        }
        return clusterService.require(clusterId);
    }

    /** If-None-Match 可能是逗号分隔的多个 etag，也可能带 W/ 弱校验前缀。 */
    private static boolean etagMatches(String header, String etag) {
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (value.equals(etag) || "*".equals(value)) {
                return true;
            }
        }
        return false;
    }
}