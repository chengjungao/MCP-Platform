package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.McpResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface McpResourceRepository extends JpaRepository<McpResource, Long> {

    /** 排序稳定：sortOrder 相同时按 id，避免发布快照顺序在不同实例间漂移。 */
    List<McpResource> findByServerIdOrderBySortOrderAscIdAsc(Long serverId);

    Optional<McpResource> findByServerIdAndUri(Long serverId, String uri);

    List<McpResource> findByServerIdIn(Collection<Long> serverIds);

    /** 发布配额校验用（只数个数，不装配 View）。 */
    long countByServerId(Long serverId);
}
