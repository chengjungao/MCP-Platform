package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.PublishBinding;
import org.springframework.data.jpa.repository.JpaRepository;

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
}