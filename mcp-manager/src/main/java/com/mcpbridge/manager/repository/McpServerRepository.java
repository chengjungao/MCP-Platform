package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.McpServer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    /** 一个 registration 可绑到多个 Server（多服务聚合后），返回 List。 */
    List<McpServer> findByRegistrationId(Long registrationId);

    List<McpServer> findByRegistrationIdIn(Collection<Long> registrationIds);

    Page<McpServer> findByDeptIdIn(Collection<Long> deptIds, Pageable pageable);

    /** 可申请目录：排除本部门树可见的 Server（平台管理员无需申请，走 findAll 分支由服务层判空）。 */
    Page<McpServer> findByDeptIdNotIn(Collection<Long> visibleDeptIds, Pageable pageable);

    Optional<McpServer> findByIdAndDeptIdIn(Long id, Collection<Long> deptIds);

    List<McpServer> findByPathSegment(String pathSegment);

    List<McpServer> findAllByOrderByIdAsc();
}