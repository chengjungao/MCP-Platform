package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.ExecutorNode;
import com.mcpbridge.manager.domain.NodeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutorNodeRepository extends JpaRepository<ExecutorNode, Long> {

    List<ExecutorNode> findByClusterIdOrderByIdAsc(Long clusterId);

    Optional<ExecutorNode> findByClusterIdAndNodeKey(Long clusterId, String nodeKey);

    List<ExecutorNode> findByStatusAndLastHeartbeatAtBefore(NodeStatus status, Instant threshold);

    long countByClusterIdAndStatus(Long clusterId, NodeStatus status);

    long countByClusterId(Long clusterId);
}