package com.mcpbridge.executor.snapshot;

import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.ServerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地发布快照（EXE-01）。
 *
 * <p>这是 Executor 唯一的「真相来源」：请求进来后，路由、tool 定义、上游地址、双向鉴权配置
 * 全部从这里读，<b>不回查 Manager</b>。这样 Manager 挂了也不影响已发布的端点继续服务，
 * 是「控制面故障不传导到数据面」这条可用性要求（R7）的实现基础。
 *
 * <p>用「不可变快照 + 原子引用整体替换」而不是「加锁改字段」：读路径完全无锁，
 * 一次替换对所有并发请求同时生效，不会出现「一半 tool 是新版一半是旧版」的中间态。
 */
@Component
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final AtomicReference<PublishedSnapshot> snapshot =
            new AtomicReference<>(PublishedSnapshot.empty("uninitialized"));
    private final AtomicReference<Map<String, ServerSnapshot>> index = new AtomicReference<>(Map.of());
    private final AtomicReference<Instant> lastSyncAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicLong appliedRevisions = new AtomicLong();

    /**
     * 整体替换快照。
     *
     * @return true 表示版本确实前进了（用于决定是否打日志与发事件）
     */
    public boolean replace(PublishedSnapshot next) {
        if (next == null) {
            return false;
        }
        PublishedSnapshot previous = snapshot.getAndSet(next);
        index.set(next.safeServers().stream()
                .filter(s -> s.pathSegment() != null)
                .collect(Collectors.toUnmodifiableMap(ServerSnapshot::pathSegment, Function.identity(),
                        // 同一末段出现两次说明 Manager 的唯一性约束被绕过；保留第一个并告警，
                        // 绝不静默覆盖——这会让某个部门的端点悄悄指向别人的上游
                        (a, b) -> {
                            log.error("快照中 PATH 末段重复：{}（serverId={} 与 {}），已保留前者",
                                    a.pathSegment(), a.serverId(), b.serverId());
                            return a;
                        })));
        lastSyncAt.set(Instant.now());
        lastError.set(null);
        boolean advanced = previous == null || previous.revision() != next.revision();
        if (advanced) {
            appliedRevisions.incrementAndGet();
            log.info("发布快照已更新 cluster={} revision={} etag={} servers={} tools={} endpoints={}",
                    next.clusterKey(), next.revision(), next.etag(), next.serverCount(), next.toolCount(),
                    endpointsOf(next));
        }
        return advanced;
    }

    /**
     * 快照内的对外发布端点清单，按 PATH 末段排序保证日志可比对。
     *
     * <p>端点直接来自快照的 {@code endpoint} 字段（{集群入口}/{保留前缀}/{末段}），
     * 运维可据此直接拷去配置 MCP 客户端，不必再回控制台查。
     */
    private String endpointsOf(PublishedSnapshot snapshot) {
        String joined = snapshot.safeServers().stream()
                .filter(s -> s.pathSegment() != null)
                .sorted(Comparator.comparing(ServerSnapshot::pathSegment))
                .map(s -> s.endpoint() == null || s.endpoint().isBlank() ? s.pathSegment() : s.endpoint())
                .collect(Collectors.joining(", "));
        // 空集群（发布了下线等场景）明确打出占位符，避免日志出现 "endpoints=" 的空值歧义
        return joined.isEmpty() ? "-" : joined;
    }

    public PublishedSnapshot current() {
        return snapshot.get();
    }

    public Optional<ServerSnapshot> server(String pathSegment) {
        if (pathSegment == null) {
            // 索引是不可变 Map，get(null) 会抛 NPE；末段缺失属于「找不到」而不是「出错了」
            return Optional.empty();
        }
        return Optional.ofNullable(index.get().get(pathSegment));
    }

    public List<String> pathSegments() {
        return index.get().keySet().stream().sorted().toList();
    }

    public long revision() {
        return snapshot.get().revision();
    }

    public String etag() {
        return snapshot.get().etag();
    }

    /** 是否已经成功同步过至少一次。未就绪时端点返回 503 而不是空列表——空列表会被误认为「没有工具」。 */
    public boolean isReady() {
        return lastSyncAt.get() != null;
    }

    public void recordError(String message) {
        lastError.set(message);
        log.warn("快照同步失败：{}", message);
    }

    /** 健康检查用：把同步状态一次性说清楚。 */
    public Status status() {
        Instant syncedAt = lastSyncAt.get();
        PublishedSnapshot current = snapshot.get();
        return new Status(
                isReady(),
                current.clusterKey(),
                current.revision(),
                current.etag(),
                current.serverCount(),
                current.toolCount(),
                syncedAt,
                syncedAt == null ? null : Duration.between(syncedAt, Instant.now()).toSeconds(),
                lastError.get(),
                appliedRevisions.get());
    }

    /**
     * @param staleSeconds 距离上次成功同步的秒数；运维据此判断轮询是否卡住
     */
    public record Status(
            boolean ready,
            String clusterKey,
            long revision,
            String etag,
            int serverCount,
            int toolCount,
            Instant lastSyncAt,
            Long staleSeconds,
            String lastError,
            long appliedRevisions) {
    }
}