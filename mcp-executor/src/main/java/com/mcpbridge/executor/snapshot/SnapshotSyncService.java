package com.mcpbridge.executor.snapshot;

import com.mcpbridge.common.snapshot.PublishedSnapshot;
import com.mcpbridge.common.snapshot.SnapshotChangedEvent;
import com.mcpbridge.executor.config.ExecutorProperties;
import com.mcpbridge.executor.internal.InternalDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 发布快照同步（EXE-01）。
 *
 * <p>两段式轮询，这是整个数据面里最值得讲究的一个细节：
 * <ol>
 *   <li>先打 {@code /revision}——响应只有几十字节，代价近似 Manager 侧一次索引查询，
 *       因此可以放心地按 10s 周期高频轮询；</li>
 *   <li>版本或 etag 变了才打 {@code /snapshot}，并带上 {@code If-None-Match}。
 *       Manager 的 etag 只由「集群名 + revision + 各 Server 的 pathSegment:bindingVersion:toolCount」
 *       决定，<b>不含生成时间</b>，所以多 Manager 实例与重启后都能命中 304。</li>
 * </ol>
 * 如果每 10 秒无脑拉全量快照，一个 300 接口的集群就是每节点每分钟几十 MB 的无谓流量。
 *
 * <p>失败语义：Manager 不可达时只记 WARN 并保留上一份快照，<b>绝不清空</b>。
 * 已发布的端点必须继续可用（R7），最坏情况是新发布的内容延迟生效。
 */
@Service
public class SnapshotSyncService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSyncService.class);

    private final WebClient managerWebClient;
    private final ExecutorProperties properties;
    private final SnapshotStore store;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<Long> clusterId = new AtomicReference<>();

    public SnapshotSyncService(WebClient managerWebClient,
                               ExecutorProperties properties,
                               SnapshotStore store,
                               ApplicationEventPublisher eventPublisher) {
        this.managerWebClient = managerWebClient;
        this.properties = properties;
        this.store = store;
        this.eventPublisher = eventPublisher;
        if (properties.manager().clusterId() != null) {
            this.clusterId.set(properties.manager().clusterId());
        }
    }

    /** 注册成功后由 {@link NodeLifecycleService} 绑定集群归属。 */
    public void bindCluster(long id) {
        Long previous = clusterId.getAndSet(id);
        if (previous == null || previous != id) {
            log.info("已绑定 Executor 集群 clusterId={}", id);
        }
    }

    public Long clusterId() {
        return clusterId.get();
    }

    public void poll() {
        poll(false);
    }

    /**
     * @param force true 时跳过版本比对直接拉取，用于心跳告知「你落后了」的场景
     */
    public void poll(boolean force) {
        Long id = clusterId.get();
        if (id == null) {
            // 还没注册成功（Manager 未就绪 / 令牌不对 / 集群名不存在），此时拉快照没有意义
            log.debug("尚未确定集群归属，跳过快照轮询");
            return;
        }
        try {
            InternalDtos.RevisionView revision = fetchRevision(id);
            if (revision == null) {
                store.recordError("Manager 返回空的版本信息");
                return;
            }
            if (!force && store.isReady()
                    && revision.revision() == store.revision()
                    && Objects.equals(revision.etag(), store.etag())) {
                log.debug("快照未变化 clusterId={} revision={}", id, revision.revision());
                return;
            }
            fetchSnapshot(id, force);
        } catch (RuntimeException e) {
            store.recordError(describe(e));
        }
    }

    private InternalDtos.RevisionView fetchRevision(long id) {
        return managerWebClient.get()
                .uri("/internal/v1/clusters/{clusterId}/revision", id)
                .retrieve()
                .bodyToMono(InternalDtos.RevisionView.class)
                .block(properties.manager().readTimeout());
    }

    private void fetchSnapshot(long id, boolean force) {
        String etag = force || !store.isReady() ? null : store.etag();
        PublishedSnapshot fetched = managerWebClient.get()
                .uri("/internal/v1/clusters/{clusterId}/snapshot", id)
                .headers(headers -> {
                    if (etag != null && !etag.isBlank()) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                    }
                })
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_MODIFIED.value()) {
                        // 304 是正常路径，不是错误：说明版本号和 etag 之间出现了短暂不一致
                        log.debug("Manager 返回 304，本地快照已是最新 clusterId={}", id);
                        return Mono.<PublishedSnapshot>empty();
                    }
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new IllegalStateException(
                                        "拉取快照失败 HTTP " + response.statusCode().value() + ": " + truncate(body))));
                    }
                    return response.bodyToMono(PublishedSnapshot.class);
                })
                .block(properties.manager().readTimeout());

        if (fetched == null) {
            return;
        }
        boolean advanced = store.replace(fetched);
        if (advanced) {
            // 本地事件：指标、审计与将来的「热更新回调」都挂在这里，避免同步逻辑越长越复杂
            eventPublisher.publishEvent(SnapshotChangedEvent.of(
                    fetched.clusterKey(), fetched.revision(), "manager-poll"));
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + truncate(message));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512) + "...";
    }
}