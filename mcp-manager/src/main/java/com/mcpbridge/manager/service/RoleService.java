package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.Role;
import com.mcpbridge.manager.repository.RoleRepository;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.web.dto.OrgDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色与权限点管理（MGM-02）。
 *
 * <p>权限模型是「角色 → 权限点集合」，控制器用 {@code @PreAuthorize} 搭配 {@code hasAuthority} 声明，
 * 平台管理员角色（PLATFORM_ADMIN）在 {@link com.mcpbridge.manager.security.AuthPrincipal#has} 中短路为全通过。
 * 内置角色只允许调整权限点，不允许删除或改 code，避免出现无管理员的锁死状态。
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public RoleService(RoleRepository roleRepository, UserRepository userRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OrgDtos.RoleView> list() {
        return roleRepository.findAllByOrderByIdAsc().stream().map(this::toView).toList();
    }

    public OrgDtos.PermissionCatalogView catalog() {
        return new OrgDtos.PermissionCatalogView(PermissionCatalog.ALL);
    }

    @Transactional
    public OrgDtos.RoleView create(OrgDtos.RoleRequest request) {
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (roleRepository.findByCode(code).isPresent()) {
            throw PlatformException.conflict("角色编码已存在", Map.of("code", code));
        }
        Set<String> permissions = requireKnownPermissions(request.permissions());
        Role role = new Role();
        role.setCode(code);
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setBuiltin(false);
        role.setPermissions(permissions);
        Role saved = roleRepository.save(role);
        auditService.record(AuditAction.ROLE_CREATE, "role", saved.getId(),
                Map.of("code", saved.getCode(), "permissions", permissions));
        return toView(saved);
    }

    @Transactional
    public OrgDtos.RoleView update(Long id, OrgDtos.RoleRequest request) {
        Role role = require(id);
        Set<String> permissions = requireKnownPermissions(request.permissions());
        // 内置角色的 code 是代码里引用的常量，改动会导致鉴权规则失效
        if (!role.isBuiltin()) {
            String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.equals(role.getCode())) {
                roleRepository.findByCode(code).ifPresent(existing -> {
                    throw PlatformException.conflict("角色编码已存在", Map.of("code", code));
                });
                role.setCode(code);
            }
        }
        Set<String> before = new LinkedHashSet<>(role.getPermissions());
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setPermissions(permissions);
        Role saved = roleRepository.save(role);
        auditService.record(AuditAction.ROLE_UPDATE, "role", saved.getId(),
                Map.of("code", saved.getCode(), "added", diff(permissions, before), "removed", diff(before, permissions)));
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        Role role = require(id);
        if (role.isBuiltin()) {
            throw PlatformException.conflict("内置角色不可删除", Map.of("code", role.getCode()));
        }
        long refs = userRepository.countByRolesCode(role.getCode());
        if (refs > 0) {
            throw PlatformException.conflict("角色仍被用户引用，请先解除绑定",
                    Map.of("code", role.getCode(), "userCount", refs));
        }
        roleRepository.delete(role);
        auditService.record(AuditAction.ROLE_DELETE, "role", id, Map.of("code", role.getCode()));
    }

    /** 按 code 批量解析角色；缺失的 code 直接报错，避免静默丢权限。 */
    @Transactional(readOnly = true)
    public List<Role> resolveByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        codes.forEach(c -> normalized.add(c.trim().toUpperCase(java.util.Locale.ROOT)));
        List<Role> found = new ArrayList<>(roleRepository.findByCodeIn(normalized));
        if (found.size() != normalized.size()) {
            Set<String> known = new LinkedHashSet<>();
            found.forEach(r -> known.add(r.getCode()));
            normalized.removeAll(known);
            throw PlatformException.validation("角色不存在", Map.of("unknownRoles", normalized));
        }
        return found;
    }

    @Transactional(readOnly = true)
    public Role require(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> PlatformException.notFound("角色", id));
    }

    public OrgDtos.RoleView toView(Role role) {
        return new OrgDtos.RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.isBuiltin(), new LinkedHashSet<>(role.getPermissions()));
    }

    private Set<String> requireKnownPermissions(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw PlatformException.validation("至少选择一个权限点", Map.of("field", "permissions"));
        }
        Set<String> unknown = permissions.stream()
                .filter(p -> !PermissionCatalog.ALL.contains(p))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw PlatformException.validation("包含未知权限点", Map.of("unknownPermissions", unknown));
        }
        return new LinkedHashSet<>(permissions);
    }

    private static List<String> diff(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList();
    }
}