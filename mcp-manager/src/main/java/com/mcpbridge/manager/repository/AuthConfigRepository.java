package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.AuthConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Auth-B 配置仓储。
 *
 * <p><b>查询维度注意</b>：V5 之后 {@code tool_id = 0} 同时覆盖「Server 级」与「REST 服务级」
 * 两类配置，二者靠 {@code upstream_service_id} 是否为空区分。因此凡是取<b>单条</b>
 * （返回 {@code Optional}）的 Server 级查询，都必须显式带上
 * {@code AndUpstreamServiceIdIsNull}；否则一个 Server 下有两个 REST 服务各配了 Auth-B 时，
 * 查询会命中多行并抛 {@code IncorrectResultSizeDataAccessException}，把发布链路直接打挂。
 */
public interface AuthConfigRepository extends JpaRepository<AuthConfig, Long> {

    /** 单条按 toolId 查，只对 Tool 级（{@code toolId != 0}）安全，见类注释。 */
    Optional<AuthConfig> findByServerIdAndToolId(Long serverId, long toolId);

    /** Server 级：{@code tool_id = 0} 且 {@code upstream_service_id} 为空。 */
    Optional<AuthConfig> findByServerIdAndToolIdAndUpstreamServiceIdIsNull(Long serverId, long toolId);

    /** Server 级批量版（列表页回显掩码），语义同上。 */
    List<AuthConfig> findByServerIdInAndToolIdAndUpstreamServiceIdIsNull(Collection<Long> serverIds, long toolId);

    /** Tool 级全部配置：{@code tool_id != 0}。REST 服务级行的 toolId 恒为 0，不会被带出来。 */
    List<AuthConfig> findByServerIdAndToolIdNot(Long serverId, long toolId);

    /** REST 服务级配置：每个 serviceId 一份独立上行鉴权。 */
    Optional<AuthConfig> findByServerIdAndUpstreamServiceId(Long serverId, String upstreamServiceId);

    /** 某 Server 下所有 REST 服务级配置，详情页一次性回显用。 */
    List<AuthConfig> findByServerIdAndUpstreamServiceIdIsNotNull(Long serverId);

    List<AuthConfig> findByServerId(Long serverId);

    /** 某个 Tool 被移除时清理其专属配置，避免留下孤儿行。 */
    void deleteByServerIdAndToolId(Long serverId, long toolId);

    void deleteByServerId(Long serverId);
}
