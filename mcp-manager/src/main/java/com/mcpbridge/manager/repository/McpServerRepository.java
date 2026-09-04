package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.McpServer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    Optional<McpServer> findByRegistrationId(Long registrationId);

    List<McpServer> findByRegistrationIdIn(Collection<Long> registrationIds);

    Page<McpServer> findByDeptIdIn(Collection<Long> deptIds, Pageable pageable);

    Optional<McpServer> findByIdAndDeptIdIn(Long id, Collection<Long> deptIds);

    List<McpServer> findByPathSegment(String pathSegment);

    List<McpServer> findAllByOrderByIdAsc();
}