package com.mcpbridge.manager.config;

import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.manager.domain.ClusterType;
import com.mcpbridge.manager.domain.Department;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.Role;
import com.mcpbridge.manager.domain.User;
import com.mcpbridge.manager.repository.DepartmentRepository;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import com.mcpbridge.manager.repository.RoleRepository;
import com.mcpbridge.manager.repository.UserRepository;
import com.mcpbridge.manager.service.PermissionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 首次启动种子数据（OPS-04）。
 *
 * <p>刻意用 Java 代码而不是 Flyway 插入：BCrypt 散列不写死在 SQL 里，
 * 口令来自配置 {@code MANAGER_BOOTSTRAP_ADMIN_PASSWORD}，避免仓库中出现可复用的固定散列。
 * Flyway 只负责建表（见 V1__init_schema.sql）。
 *
 * <p>幂等：按 name/code/username 判存在，重复启动不会插入重复数据。
 * 生产环境应设 {@code MCP_MANAGER_BOOTSTRAP_ENABLED=false} 并通过运维流程建号。
 */
@Component
@Order(0)
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private static final Map<String, String> BUILTIN_ROLE_NAMES = Map.of(
            PermissionCatalog.ROLE_PLATFORM_ADMIN, "平台管理员",
            PermissionCatalog.ROLE_OPS, "运维",
            PermissionCatalog.ROLE_AUDITOR, "审计员",
            PermissionCatalog.ROLE_DEPT_ADMIN, "部门管理员",
            PermissionCatalog.ROLE_DEPT_DEVELOPER, "部门开发者",
            PermissionCatalog.ROLE_READONLY, "只读");

    private final ManagerProperties properties;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ExecutorClusterRepository clusterRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapDataInitializer(ManagerProperties properties,
                                    DepartmentRepository departmentRepository,
                                    RoleRepository roleRepository,
                                    UserRepository userRepository,
                                    ExecutorClusterRepository clusterRepository,
                                    PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.departmentRepository = departmentRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.clusterRepository = clusterRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        syncBuiltinRoles();
        if (!properties.bootstrap().enabled()) {
            log.info("种子数据未启用（mcp.manager.bootstrap.enabled=false），跳过部门、管理员与默认集群初始化");
            return;
        }
        Department root = ensureRootDepartment();
        ensureAdminUser(root);
        ensureDefaultCluster(root);
    }

    /** 内置角色始终同步，保证升级后新增权限点不会缺失。 */
    private void syncBuiltinRoles() {
        PermissionCatalog.BUILTIN_ROLES.forEach((code, permissions) -> {
            Role role = roleRepository.findByCode(code).orElseGet(Role::new);
            role.setCode(code);
            role.setName(BUILTIN_ROLE_NAMES.getOrDefault(code, code));
            role.setDescription("内置角色，不可删除");
            role.setBuiltin(true);
            role.setPermissions(new LinkedHashSet<>(permissions));
            roleRepository.save(role);
        });
        log.info("内置角色已同步：{}", PermissionCatalog.BUILTIN_ROLES.keySet());
    }

    private Department ensureRootDepartment() {
        String name = properties.bootstrap().rootDepartment();
        return departmentRepository.findByNameAndParentId(name, null).orElseGet(() -> {
            Department department = new Department();
            department.setName(name);
            department.setParentId(null);
            department.setDescription("平台根部门，所有子部门挂在此节点下");
            department.setEnabled(true);
            Department saved = departmentRepository.save(department);
            log.info("已创建根部门 id={} name={}", saved.getId(), saved.getName());
            return saved;
        });
    }

    private void ensureAdminUser(Department root) {
        String username = properties.bootstrap().adminUsername();
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        Role admin = roleRepository.findByCode(PermissionCatalog.ROLE_PLATFORM_ADMIN)
                .orElseThrow(() -> new IllegalStateException("内置角色缺失：" + PermissionCatalog.ROLE_PLATFORM_ADMIN));
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(properties.bootstrap().adminPassword()));
        user.setDisplayName("平台管理员");
        user.setDeptId(root.getId());
        user.setEnabled(true);
        user.setRoles(new LinkedHashSet<>(Set.of(admin)));
        userRepository.save(user);
        log.warn("已创建引导管理员 {}，请在首次登录后立即修改口令并关闭 mcp.manager.bootstrap.enabled", username);
    }

    /**
     * 默认共享集群：授权给根部门，节点接入令牌沿用 {@code executor.bootstrap-token}，
     * 这样 Manager + Executor + Postgres 三件套起来就能跑通 PRD §5.6 的最小闭环。
     */
    private void ensureDefaultCluster(Department root) {
        String name = properties.bootstrap().clusterName();
        if (clusterRepository.findByName(name).isPresent()) {
            return;
        }
        ExecutorCluster cluster = new ExecutorCluster();
        cluster.setName(name);
        cluster.setType(ClusterType.SHARED);
        cluster.setEntrypoint(properties.bootstrap().clusterEntrypoint());
        cluster.setPathPrefix(properties.defaultPathPrefix());
        cluster.setOwnerDeptId(root.getId());
        cluster.setDescription("引导创建的默认共享集群");
        cluster.setEnabled(true);
        cluster.setGrantedDeptIds(new LinkedHashSet<>(Set.of(root.getId())));
        cluster.setNodeTokenHash(Hashing.sha256Hex(properties.executor().bootstrapToken()));
        cluster.setRevision(0L);
        ExecutorCluster saved = clusterRepository.save(cluster);
        log.info("已创建默认共享集群 id={} name={} entrypoint={}",
                saved.getId(), saved.getName(), saved.getEntrypoint());
    }
}