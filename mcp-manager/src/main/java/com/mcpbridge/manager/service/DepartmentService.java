package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.Department;
import com.mcpbridge.manager.repository.DepartmentRepository;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.web.dto.OrgDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门树管理（MGM-03 / MGM-04：资源归属部门，数据按部门树隔离）。
 *
 * <p>树形结构用 {@code parent_id} 自引用表达，不引入闭包表：部门量级在千以内，
 * 每次全量加载再内存建树的成本可接受，且能一次性拿到全部层级用于可见域计算。
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public DepartmentService(DepartmentRepository departmentRepository,
                             UserRepository userRepository,
                             AuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.DepartmentView> tree() {
        List<Department> all = departmentRepository.findAllByOrderByIdAsc();
        Map<Long, Long> memberCounts = new LinkedHashMap<>();
        all.forEach(d -> memberCounts.put(d.getId(), userRepository.countByDeptId(d.getId())));

        Map<Long, List<Department>> childrenByParent = new LinkedHashMap<>();
        for (Department d : all) {
            childrenByParent.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
        }
        return buildChildren(null, childrenByParent, memberCounts);
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.DepartmentView> flat() {
        return departmentRepository.findAllByOrderByIdAsc().stream()
                .map(d -> new OrgDtos.DepartmentView(d.getId(), d.getName(), d.getParentId(), d.getDescription(),
                        d.isEnabled(), userRepository.countByDeptId(d.getId()), List.of()))
                .toList();
    }

    @Transactional
    public OrgDtos.DepartmentView create(OrgDtos.DepartmentRequest request) {
        Long parentId = request.parentId();
        if (parentId != null) {
            require(parentId);
        }
        String name = request.name().trim();
        departmentRepository.findByNameAndParentId(name, parentId).ifPresent(existing -> {
            throw PlatformException.conflict("同级下已存在同名部门", Map.of("name", name));
        });
        Department department = new Department();
        department.setName(name);
        department.setParentId(parentId);
        department.setDescription(request.description());
        department.setEnabled(request.enabled() == null || request.enabled());
        Department saved = departmentRepository.save(department);
        auditService.record(AuditAction.DEPT_CREATE, "department", saved.getId(),
                Map.of("name", saved.getName(), "parentId", String.valueOf(parentId)));
        return toView(saved);
    }

    @Transactional
    public OrgDtos.DepartmentView update(Long id, OrgDtos.DepartmentRequest request) {
        Department department = require(id);
        if (request.parentId() != null && request.parentId().equals(id)) {
            throw PlatformException.validation("上级部门不能是自身", Map.of("field", "parentId"));
        }
        if (request.parentId() != null && isDescendant(request.parentId(), id)) {
            throw PlatformException.validation("上级部门不能是自己的下级，否则部门树出现环",
                    Map.of("field", "parentId", "deptId", id));
        }
        Long parentId = request.parentId();
        String name = request.name().trim();
        departmentRepository.findByNameAndParentId(name, parentId)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw PlatformException.conflict("同级下已存在同名部门", Map.of("name", name));
                });
        department.setName(name);
        department.setParentId(parentId);
        department.setDescription(request.description());
        if (request.enabled() != null) {
            department.setEnabled(request.enabled());
        }
        Department saved = departmentRepository.save(department);
        auditService.record(AuditAction.DEPT_UPDATE, "department", saved.getId(),
                Map.of("name", saved.getName(), "parentId", String.valueOf(parentId)));
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        Department department = require(id);
        long children = departmentRepository.countByParentId(id);
        if (children > 0) {
            throw PlatformException.conflict("存在下级部门，无法删除", Map.of("childCount", children));
        }
        long members = userRepository.countByDeptId(id);
        if (members > 0) {
            throw PlatformException.conflict("部门下仍有成员，请先迁移", Map.of("memberCount", members));
        }
        departmentRepository.delete(department);
        auditService.record(AuditAction.DEPT_DELETE, "department", id, Map.of("name", department.getName()));
    }

    @Transactional(readOnly = true)
    public Department require(Long id) {
        return departmentRepository.findById(id).orElseThrow(() -> PlatformException.notFound("部门", id));
    }

    @Transactional(readOnly = true)
    public String nameOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return departmentRepository.findById(deptId).map(Department::getName).orElse(null);
    }

    /** 批量取名，避免列表页 N+1 查询。 */
    @Transactional(readOnly = true)
    public Map<Long, String> namesOf() {
        Map<Long, String> result = new LinkedHashMap<>();
        departmentRepository.findAll().forEach(d -> result.put(d.getId(), d.getName()));
        return result;
    }

    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Department d : departmentRepository.findAll()) {
            if (d.getParentId() != null) {
                childrenByParent.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d.getId());
            }
        }
        List<Long> queue = new ArrayList<>(childrenByParent.getOrDefault(ancestorId, List.of()));
        while (!queue.isEmpty()) {
            Long current = queue.remove(0);
            if (current.equals(candidateId)) {
                return true;
            }
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        return false;
    }

    private List<OrgDtos.DepartmentView> buildChildren(Long parentId,
                                                       Map<Long, List<Department>> childrenByParent,
                                                       Map<Long, Long> memberCounts) {
        List<Department> children = childrenByParent.getOrDefault(parentId, List.of());
        List<OrgDtos.DepartmentView> views = new ArrayList<>(children.size());
        for (Department d : children) {
            views.add(new OrgDtos.DepartmentView(d.getId(), d.getName(), d.getParentId(), d.getDescription(),
                    d.isEnabled(), memberCounts.getOrDefault(d.getId(), 0L),
                    buildChildren(d.getId(), childrenByParent, memberCounts)));
        }
        return views;
    }

    private OrgDtos.DepartmentView toView(Department d) {
        return new OrgDtos.DepartmentView(d.getId(), d.getName(), d.getParentId(), d.getDescription(),
                d.isEnabled(), userRepository.countByDeptId(d.getId()),
                buildChildren(d.getId(), childIndex(), Map.of()));
    }

    private Map<Long, List<Department>> childIndex() {
        Map<Long, List<Department>> childrenByParent = new LinkedHashMap<>();
        for (Department d : departmentRepository.findAll()) {
            childrenByParent.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
        }
        return childrenByParent;
    }
}