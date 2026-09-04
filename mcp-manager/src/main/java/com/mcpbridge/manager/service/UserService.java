package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.Department;
import com.mcpbridge.manager.domain.Role;
import com.mcpbridge.manager.domain.User;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.OrgDtos;
import com.mcpbridge.manager.web.dto.PageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理（MGM-01）。
 *
 * <p>数据隔离（MGM-04）：非平台管理员只能看到并管理自己部门及其下级的成员；
 * 平台管理员的可见域为 {@code null}，表示不受限。
 *
 * <p>口令策略：BCrypt 存散列，明文只在创建/重置时经过一次；审计只记「口令已重置」不记内容（SEC-02）。
 */
@Service
public class UserService {

    /** 未显式指定角色时授予的最小权限角色。 */
    private static final String DEFAULT_ROLE = PermissionCatalog.ROLE_READONLY;

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final DepartmentService departmentService;
    private final DepartmentScope departmentScope;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AuthService authService;

    public UserService(UserRepository userRepository,
                       RoleService roleService,
                       DepartmentService departmentService,
                       DepartmentScope departmentScope,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       AuthService authService) {
        this.userRepository = userRepository;
        this.roleService = roleService;
        this.departmentService = departmentService;
        this.departmentScope = departmentScope;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.authService = authService;
    }

    @Transactional(readOnly = true)
    public PageView<OrgDtos.UserView> page(Pageable pageable, AuthPrincipal principal) {
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        Map<Long, String> deptNames = departmentService.namesOf();
        Page<User> users = visible == null
                ? userRepository.findAll(pageable)
                : userRepository.findByDeptIdIn(visible, pageable);
        return PageView.of(users, u -> toView(u, deptNames));
    }

    @Transactional(readOnly = true)
    public OrgDtos.UserView view(Long id, AuthPrincipal principal) {
        User user = require(id, principal);
        return toView(user, departmentService.namesOf());
    }

    @Transactional
    public OrgDtos.UserView create(OrgDtos.UserCreateRequest request, AuthPrincipal principal) {
        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw PlatformException.conflict("用户名已存在", Map.of("username", username));
        }
        Long deptId = request.deptId() != null ? request.deptId() : principal.deptId();
        if (deptId == null) {
            throw PlatformException.validation("必须指定归属部门", Map.of("field", "deptId"));
        }
        departmentScope.requireAccess(deptId, principal);
        Department department = departmentService.require(deptId);

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(blankToNull(request.displayName()) == null ? username : request.displayName().trim());
        user.setEmail(blankToNull(request.email()));
        user.setDeptId(department.getId());
        user.setEnabled(true);
        Set<String> codes = request.roleCodes() == null || request.roleCodes().isEmpty()
                ? Set.of(DEFAULT_ROLE) : request.roleCodes();
        user.setRoles(new LinkedHashSet<>(roleService.resolveByCodes(codes)));
        User saved = userRepository.save(user);
        auditService.record(AuditAction.USER_CREATE, "user", saved.getId(),
                Map.of("username", username, "deptId", deptId, "roles", roleCodesOf(saved)));
        return toView(saved, departmentService.namesOf());
    }

    @Transactional
    public OrgDtos.UserView update(Long id, OrgDtos.UserUpdateRequest request, AuthPrincipal principal) {
        User user = require(id, principal);
        Map<String, Object> changes = new java.util.LinkedHashMap<>();

        if (request.displayName() != null) {
            user.setDisplayName(blankToNull(request.displayName()));
            changes.put("displayName", user.getDisplayName());
        }
        if (request.email() != null) {
            user.setEmail(blankToNull(request.email()));
            changes.put("email", user.getEmail());
        }
        if (request.deptId() != null && !request.deptId().equals(user.getDeptId())) {
            // 跨部门调动会改变数据可见域，只允许平台管理员或双方部门的管理者操作
            departmentScope.requireAccess(request.deptId(), principal);
            departmentScope.requireAccess(user.getDeptId(), principal);
            Department department = departmentService.require(request.deptId());
            changes.put("deptId", Map.of("from", user.getDeptId(), "to", department.getId()));
            user.setDeptId(department.getId());
        }
        if (request.roleCodes() != null) {
            Set<String> before = roleCodesOf(user);
            Set<Role> roles = new LinkedHashSet<>(roleService.resolveByCodes(request.roleCodes()));
            // 保护：不允许把最后一个平台管理员降级，否则控制台将无人可管
            if (user.hasRole(AuthPrincipal.PLATFORM_ADMIN_ROLE)
                    && roles.stream().noneMatch(r -> AuthPrincipal.PLATFORM_ADMIN_ROLE.equals(r.getCode()))
                    && isLastPlatformAdmin(user)) {
                throw PlatformException.conflict("必须保留至少一名平台管理员", Map.of("userId", user.getId()));
            }
            user.setRoles(roles);
            changes.put("roles", Map.of("from", before, "to", roleCodesOf(user)));
        }
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            user.setEnabled(request.enabled());
            changes.put("enabled", request.enabled());
        }
        if (blankToNull(request.password()) != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            changes.put("password", "reset");
        }

        User saved = userRepository.save(user);
        // 角色/口令/启用状态变更都会影响既有 JWT 的权限视图，立即失效缓存
        authService.invalidate(saved.getId());
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.USER_UPDATE, "user", saved.getId(), changes);
        }
        return toView(saved, departmentService.namesOf());
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(() -> PlatformException.notFound("用户", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw PlatformException.validation("当前口令不正确", Map.of("field", "currentPassword"));
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        authService.invalidate(userId);
        auditService.record(AuditAction.USER_UPDATE, "user", userId, Map.of("password", "self-reset"));
    }

    @Transactional(readOnly = true)
    public User require(Long id, AuthPrincipal principal) {
        User user = userRepository.findById(id).orElseThrow(() -> PlatformException.notFound("用户", id));
        departmentScope.requireAccess(user.getDeptId(), principal);
        return user;
    }

    private boolean isLastPlatformAdmin(User candidate) {
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> u.hasRole(AuthPrincipal.PLATFORM_ADMIN_ROLE))
                .noneMatch(u -> !u.getId().equals(candidate.getId()));
    }

    private OrgDtos.UserView toView(User user, Map<Long, String> deptNames) {
        return new OrgDtos.UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getDeptId(),
                deptNames.get(user.getDeptId()),
                user.isEnabled(),
                roleCodesOf(user),
                new LinkedHashSet<>(user.permissions()),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }

    private static Set<String> roleCodesOf(User user) {
        Set<String> codes = new LinkedHashSet<>();
        if (user.getRoles() != null) {
            user.getRoles().forEach(r -> codes.add(r.getCode()));
        }
        return codes;
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供控制器直接取用户列表（如下拉选择），保持与分页一致的数据隔离。 */
    @Transactional(readOnly = true)
    public List<OrgDtos.UserView> listAll(AuthPrincipal principal) {
        Map<Long, String> deptNames = departmentService.namesOf();
        Set<Long> visible = departmentScope.visibleDeptIds(principal);
        List<User> users = visible == null
                ? userRepository.findAll()
                : userRepository.findAll().stream().filter(u -> visible.contains(u.getDeptId())).toList();
        return users.stream().map(u -> toView(u, deptNames)).toList();
    }
}