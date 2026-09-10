package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.PublishBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublishBindingRepository extends JpaRepository<PublishBinding, Long> {

    Optional<PublishBinding> findByServerIdAndClusterIdAndCurrentTrue(Long serverId, Long clusterId);

    List<PublishBinding> findByClusterIdAndCurrentTrue(Long clusterId);

    List<PublishBinding> findByClusterIdInAndCurrentTrue(Collection<Long> clusterIds);

    List<PublishBinding> findByServerIdOrderByIdDesc(Long serverId);

    /** 批量拉取多个 Server 的发布绑定，供列表页避免 N+1。 */
    List<PublishBinding> findByServerIdIn(Collection<Long> serverIds);

    Optional<PublishBinding> findTopByServerIdAndClusterIdOrderByVersionDesc(Long serverId, Long clusterId);

    List<PublishBinding> findByServerIdAndClusterIdOrderByVersionDesc(Long serverId, Long clusterId);

    long countByServerIdAndState(Long serverId, BindingState state);

    /** 集群列表页只需要「已发布数」，用 count 而不是把整行（含 jsonb snapshot）拉回来。 */
    long countByClusterIdAndCurrentTrueAndState(Long clusterId, BindingState state);

    /**
     * 只取当前生效绑定的指纹列（EXE-01）。
     *
     * <p>刻意用投影查询而非 {@link #findByClusterIdAndCurrentTrue}：后者会把 {@code snapshot}
     * 一起 detoast，而 {@code /revision} 每 10s 被每个节点打一次，几 MB 的 TOAST 读取代价
     * 与「只想要几个几十字节的字符串」完全不成比例。
     */
    @Query("select b.fingerprint from PublishBinding b "
            + "where b.clusterId = :clusterId and b.current = true "
            + "and b.state = :state and b.snapshot is not null")
    List<String> findCurrentFingerprints(@Param("clusterId") Long clusterId, @Param("state") BindingState state);
}