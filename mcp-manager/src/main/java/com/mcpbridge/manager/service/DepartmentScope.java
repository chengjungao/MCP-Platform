package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.Department;
import com.mcpbridge.manager.repository.DepartmentRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 部门数据隔离（MGM-04）。
 *
 * <p>规则：所有资源（注册 / MCP Server / 集群）归属创建者所在部门；
 * 部门管理员可见本部门及其子部门；平台管理员不受限；跨部门访问需显式授权，
 * 未授权时统一抛 403（越权请求返回 403，而不是 404 掩盖存在性 —— 与 PRD 验收口径一致）。
 */
@Component
public class DepartmentScope {

    private final DepartmentRepository departmentRepository;

    public DepartmentScope(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /**
     * 当前用户可见的部门 id 集合。
     *
     * @return {@code null} 表示不受限（平台管理员）；否则为本人部门 + 所有子孙部门
     */
    public Set<Long> visibleDeptIds(AuthPrincipal principal) {
        if (principal == null) {
            throw PlatformException.forbidden("未认证");
        }
        if (principal.isPlatformAdmin()) {
            return null;
        }
        Set<Long> visible = new LinkedHashSet<>();
        if (principal.deptId() != null) {
            visible.add(principal.deptId());
            visible.addAll(descendantDeptIds(principal.deptId()));
        }
        return visible;
    }

    /** 子孙部门 id（部门树向下展开）。 */
    public Set<Long> descendantDeptIds(Long rootDeptId) {
        if (rootDeptId == null) {
            return Set.of();
        }
        Map<Long, List<Long>> childrenByParent = departmentRepository.findAll().stream()
                .filter(d -> d.getParentId() != null)
                .collect(Collectors.groupingBy(Department::getParentId,
                        Collectors.mapping(Department::getId, Collectors.toList())));

        Set<Long> result = new LinkedHashSet<>();
        List<Long> queue = new ArrayList<>(childrenByParent.getOrDefault(rootDeptId, List.of()));
        while (!queue.isEmpty()) {
            Long current = queue.remove(0);
            if (result.add(current)) {
                queue.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    /** 是否可访问某部门的资源。 */
    public boolean canAccess(Long deptId, AuthPrincipal principal) {
        Set<Long> visible = visibleDeptIds(principal);
        return visible == null || (deptId != null && visible.contains(deptId));
    }

    /**
     * 部门到根的祖先链（含自身，自底向上）。
     *
     * <p>跨部门授权覆盖判定用：授权给部门 D = D 及其子树成员可访问。
     * principal 属于 D 的子树 ⇔ D ∈ chain(principal.deptId)，
     * 因此命中条件为「存在 APPROVED 授权，其 dept_id ∈ 本链」。
     */
    public List<Long> deptChainToRoot(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        Map<Long, Long> parentByDept = departmentRepository.findAll().stream()
                .filter(d -> d.getParentId() != null)
                .collect(Collectors.toMap(Department::getId, Department::getParentId));
        List<Long> chain = new ArrayList<>();
        Long current = deptId;
        Set<Long> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current)) {
            chain.add(current);
            current = parentByDept.get(current);
        }
        return chain;
    }

    /** 不可访问时抛 403。 */
    public void requireAccess(Long deptId, AuthPrincipal principal) {
        if (!canAccess(deptId, principal)) {
            throw PlatformException.forbidden("无权访问该部门的资源（跨部门访问需显式授权）");
        }
    }

    /**
     * 把「不受限」的 null 语义转成仓储可用的查询条件：
     * 平台管理员传 null 时服务层跳过部门过滤。
     */
    public boolean isUnrestricted(AuthPrincipal principal) {
        return principal != null && principal.isPlatformAdmin();
    }
}