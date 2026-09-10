package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.AuthConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuthConfigRepository extends JpaRepository<AuthConfig, Long> {

    Optional<AuthConfig> findByServerIdAndToolId(Long serverId, long toolId);

    /** REST 服务级配置：每个 serviceId 一份独立上行鉴权。 */
    Optional<AuthConfig> findByServerIdAndUpstreamServiceId(Long serverId, String upstreamServiceId);

    /** 某 Server 下所有 REST 服务级配置，详情页一次性回显用。 */
    List<AuthConfig> findByServerIdAndUpstreamServiceIdIsNotNull(Long serverId);

    /** 批量拉取多个 Server 的同级配置，供列表页避免 N+1。 */
    List<AuthConfig> findByServerIdInAndToolId(Collection<Long> serverIds, long toolId);

    List<AuthConfig> findByServerId(Long serverId);

    void deleteByServerId(Long serverId);
}