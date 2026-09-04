package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.ClusterType;
import com.mcpbridge.manager.domain.ExecutorCluster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutorClusterRepository extends JpaRepository<ExecutorCluster, Long> {

    Optional<ExecutorCluster> findByName(String name);

    /** 按节点接入令牌的 sha256 反查集群：内部通道鉴权时用（PUB-02）。 */
    Optional<ExecutorCluster> findByNodeTokenHash(String nodeTokenHash);

    List<ExecutorCluster> findByEnabledTrueOrderByIdAsc();

    List<ExecutorCluster> findByTypeOrderByIdAsc(ClusterType type);

    List<ExecutorCluster> findAllByOrderByIdAsc();
}