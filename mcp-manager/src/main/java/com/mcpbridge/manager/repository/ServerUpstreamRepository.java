package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.ServerUpstream;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerUpstreamRepository extends JpaRepository<ServerUpstream, Long> {

    /** 查某个 Server 的所有上游服务（按 serviceId 升序，保证快照装配顺序稳定）。 */
    List<ServerUpstream> findByServerIdOrderByServiceIdAsc(Long serverId);

    /** 按 Server + serviceId 精确定位（CRUD / 冲突校验）。 */
    Optional<ServerUpstream> findByServerIdAndServiceId(Long serverId, String serviceId);

    List<ServerUpstream> findByServerIdIn(List<Long> serverIds);

    long countByServerId(Long serverId);

    void deleteByServerId(Long serverId);
}
