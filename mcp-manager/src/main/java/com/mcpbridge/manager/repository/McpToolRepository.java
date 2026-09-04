package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.McpTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface McpToolRepository extends JpaRepository<McpTool, Long> {

    List<McpTool> findByServerIdOrderBySortOrderAsc(Long serverId);

    /** 批量拉取多个 Server 的 tool，供列表页与快照组装避免 N+1。 */
    List<McpTool> findByServerIdInOrderBySortOrderAsc(Collection<Long> serverIds);

    Optional<McpTool> findByServerIdAndAnchor(Long serverId, String anchor);

    Optional<McpTool> findByServerIdAndBaseName(Long serverId, String baseName);

    long countByServerId(Long serverId);

    long countByServerIdAndEnabledTrue(Long serverId);

    void deleteByServerId(Long serverId);
}