package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.ServerAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServerAccessRepository extends JpaRepository<ServerAccess, Long> {

    /** 某 Server 上的全部申请/授权（含历史状态，一个 dept 一行）。 */
    List<ServerAccess> findByServerIdOrderByIdDesc(Long serverId);

    /** 某 Server + 申请部门（唯一键定位，状态流转用）。 */
    Optional<ServerAccess> findByServerIdAndDeptId(Long serverId, Long deptId);

    /** 目录状态标注：一批 Server 上、某个申请部门自己的申请记录。 */
    List<ServerAccess> findByServerIdInAndDeptId(Collection<Long> serverIds, Long deptId);

    /** 某状态的全部记录（待审批遍历用，量小）。 */
    List<ServerAccess> findByStatusOrderByIdAsc(AccessStatus status);

    /** 部门可见范围（本部门树）内发起的申请：申请部门 ∈ 传入集合。 */
    List<ServerAccess> findByDeptIdInOrderByIdDesc(Collection<Long> deptIds);

    /** 是否有覆盖某部门（或其祖先被授权）的生效授权 —— 跨部门只读判定核心。 */
    boolean existsByServerIdAndDeptIdInAndStatus(Long serverId, Collection<Long> deptIds, AccessStatus status);

    /** 待审批：某批 Server（资源方部门树内）上的 PENDING 申请。 */
    List<ServerAccess> findByServerIdInAndStatusOrderByIdAsc(Collection<Long> serverIds, AccessStatus status);

    /** 申请人部门树内、处于某状态的全部记录（「我发起的」列表 / 目录状态标注）。 */
    List<ServerAccess> findByDeptIdInAndStatus(Collection<Long> deptIds, AccessStatus status);
}
