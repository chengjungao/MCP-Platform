package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.McpPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpPromptRepository extends JpaRepository<McpPrompt, Long> {

    /** 排序稳定：sortOrder 相同时按 id，避免发布快照顺序在不同实例间漂移。 */
    List<McpPrompt> findByServerIdOrderBySortOrderAscIdAsc(Long serverId);

    Optional<McpPrompt> findByServerIdAndName(Long serverId, String name);

    /** 发布配额校验用（只数个数，不装配 View）。 */
    long countByServerId(Long serverId);
}
