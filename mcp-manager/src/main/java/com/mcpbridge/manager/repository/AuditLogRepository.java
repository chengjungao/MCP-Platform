package com.mcpbridge.manager.repository;

import com.mcpbridge.manager.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

/**
 * 审计日志仓储（MGM-05）。只提供查询与插入：append-only 由服务层与代码审查共同保证，
 * 本接口刻意不暴露 deleteBy* 派生方法。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditLog> findByDeptIdOrderByIdDesc(Long deptId, Pageable pageable);

    Page<AuditLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<AuditLog> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, String targetId, Pageable pageable);

    /** MGM-04：非平台管理员只能看到本部门（含下级）的审计记录。 */
    Page<AuditLog> findByDeptIdInOrderByIdDesc(Collection<Long> deptIds, Pageable pageable);

    Page<AuditLog> findByActionAndDeptIdInOrderByIdDesc(String action, Collection<Long> deptIds, Pageable pageable);
}