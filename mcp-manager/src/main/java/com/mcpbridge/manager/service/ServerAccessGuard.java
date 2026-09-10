package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.AccessStatus;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.repository.McpServerRepository;
import com.mcpbridge.manager.repository.ServerAccessRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server 级访问校验（MGM-04）。
 *
 * <p>抽成独立组件是为了解开一处循环依赖：{@link ResourcePromptService} 需要这套校验，
 * 而 {@link ServerService} 又需要 ResourcePromptService 提供 Resource/Prompt 的装配。
 * 与其在其中一侧用 {@code @Lazy} 绕开（会让依赖关系变得不可读），不如把这层真正共享的
 * 逻辑放到双方都能依赖的下游——它只依赖仓储与部门树，没有任何反向依赖。
 *
 * <p>两级语义：
 * <ul>
 *   <li><b>管理权</b>（{@link #requireManage}）：本部门树内，或平台管理员；</li>
 *   <li><b>读权</b>（{@link #requireRead}）：管理权之外，额外放行持有 APPROVED 跨部门授权的部门成员。
 *       授权覆盖部门子树——{@code grant.dept ∈ 祖先链(principal.deptId)} 即命中。</li>
 * </ul>
 * 越权一律 403，不返回「资源不存在」——那会让调用方分不清「没权限」和「没这个东西」。
 */
@Component
public class ServerAccessGuard {

    private final McpServerRepository serverRepository;
    private final ServerAccessRepository accessRepository;
    private final DepartmentScope departmentScope;

    public ServerAccessGuard(McpServerRepository serverRepository,
                             ServerAccessRepository accessRepository,
                             DepartmentScope departmentScope) {
        this.serverRepository = serverRepository;
        this.accessRepository = accessRepository;
        this.departmentScope = departmentScope;
    }

    /** 写权校验：本部门树内可写，或平台管理员。 */
    @Transactional(readOnly = true)
    public McpServer requireManage(Long id, AuthPrincipal principal) {
        McpServer server = require(id);
        departmentScope.requireAccess(server.getDeptId(), principal);
        return server;
    }

    /** 读权校验：管理权之外，放行持有 APPROVED 跨部门授权的部门成员。 */
    @Transactional(readOnly = true)
    public McpServer requireRead(Long id, AuthPrincipal principal) {
        McpServer server = require(id);
        if (departmentScope.canAccess(server.getDeptId(), principal)) {
            return server;
        }
        boolean granted = accessRepository.existsByServerIdAndDeptIdInAndStatus(
                id, departmentScope.deptChainToRoot(principal.deptId()), AccessStatus.APPROVED);
        if (!granted) {
            throw PlatformException.forbidden("无权访问该 Server（可发起跨部门访问申请，需资源方授权）");
        }
        return server;
    }

    private McpServer require(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> PlatformException.notFound("MCP Server", id));
    }
}
