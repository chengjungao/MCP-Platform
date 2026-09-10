package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.BindingState;
import com.mcpbridge.manager.domain.ClusterQuota;
import com.mcpbridge.manager.domain.ClusterType;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.domain.ExecutorNode;
import com.mcpbridge.manager.domain.NodeStatus;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import com.mcpbridge.manager.repository.ExecutorNodeRepository;
import com.mcpbridge.manager.repository.PublishBindingRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.ClusterDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executor 集群管理（PUB-01 / PUB-02，US-05）。
 *
 * <p>两类集群：
 * <ul>
 *   <li><b>SHARED</b>：多部门共用，通过 {@code grantedDeptIds} 显式授权；
 *       PATH 末段在其内必须唯一（BR-3）。</li>
 *   <li><b>PRIVATE</b>：单部门独占，只有 {@code ownerDeptId} 及其上级可发布。</li>
 * </ul>
 *
 * <p>节点接入令牌只存 sha256，明文仅在「生成/轮换」的响应里返回一次（SEC-01）。
 */
@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExecutorClusterRepository clusterRepository;
    private final ExecutorNodeRepository nodeRepository;
    private final PublishBindingRepository bindingRepository;
    private final DepartmentService departmentService;
    private final DepartmentScope departmentScope;
    private final AuditService auditService;
    private final ManagerProperties properties;

    public ClusterService(ExecutorClusterRepository clusterRepository,
                          ExecutorNodeRepository nodeRepository,
                          PublishBindingRepository bindingRepository,
                          DepartmentService departmentService,
                          DepartmentScope departmentScope,
                          AuditService auditService,
                          ManagerProperties properties) {
        this.clusterRepository = clusterRepository;
        this.nodeRepository = nodeRepository;
        this.bindingRepository = bindingRepository;
        this.departmentService = departmentService;
        this.departmentScope = departmentScope;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<ClusterDtos.ClusterView> list(AuthPrincipal principal) {
        List<ExecutorCluster> clusters = principal.isPlatformAdmin()
                ? clusterRepository.findAllByOrderByIdAsc()
                : clusterRepository.findByEnabledTrueOrderByIdAsc();
        return clusters.stream()
                // 非平台管理员只看到与自己部门有关的集群：被授权的、或本部门独占的
                .filter(c -> principal.isPlatformAdmin() || isRelevant(c, principal))
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClusterDtos.ClusterView view(Long id, AuthPrincipal principal) {
        ExecutorCluster cluster = require(id);
        if (!principal.isPlatformAdmin() && !isRelevant(cluster, principal)) {
            throw PlatformException.forbidden("无权访问该集群");
        }
        return toView(cluster);
    }

    @Transactional
    public ClusterDtos.ClusterView create(ClusterDtos.ClusterRequest request, AuthPrincipal principal) {
        String name = request.name().trim();
        clusterRepository.findByName(name).ifPresent(existing -> {
            throw PlatformException.conflict("集群名已存在", Map.of("name", name));
        });
        String entrypoint = normalizeEntrypoint(request.entrypoint());
        ExecutorCluster cluster = new ExecutorCluster();
        cluster.setName(name);
        cluster.setType(request.type() == null ? ClusterType.SHARED : request.type());
        cluster.setEntrypoint(entrypoint);
        cluster.setPathPrefix(normalizePrefix(request.pathPrefix()));
        cluster.setOwnerDeptId(request.ownerDeptId() != null ? request.ownerDeptId() : principal.deptId());
        cluster.setDescription(request.description());
        cluster.setEnabled(request.enabled() == null || request.enabled());
        cluster.setQuota(quotaJson(request.quota()));
        cluster.setRevision(0L);
        // 私有集群默认只授权给拥有者部门；共享集群创建后由管理员显式授权
        Set<Long> granted = new LinkedHashSet<>();
        if (cluster.getType() == ClusterType.PRIVATE && cluster.getOwnerDeptId() != null) {
            granted.add(cluster.getOwnerDeptId());
        }
        cluster.setGrantedDeptIds(granted);
        cluster.setNodeTokenHash(Hashing.sha256Hex(generateToken()));
        ExecutorCluster saved = clusterRepository.save(cluster);
        auditService.record(AuditAction.CLUSTER_CREATE, "cluster", saved.getId(), Map.of(
                "name", saved.getName(),
                "type", saved.getType().name(),
                "entrypoint", saved.getEntrypoint(),
                "pathPrefix", saved.getPathPrefix()));
        return toView(saved);
    }

    @Transactional
    public ClusterDtos.ClusterView update(Long id, ClusterDtos.ClusterRequest request, AuthPrincipal principal) {
        ExecutorCluster cluster = require(id);
        // changes 是「审计口径」：所有真实发生的变化都记；
        // endpointAffecting 是「revision 口径」：只有会影响 Executor 所加载快照的变更才推进版本号。
        // 两者分开是必要的——配额、描述这类改动该留痕，但不该让全网节点白重载一次快照。
        Map<String, Object> changes = new LinkedHashMap<>();
        boolean endpointAffecting = false;
        String name = request.name().trim();
        if (!name.equals(cluster.getName())) {
            clusterRepository.findByName(name).ifPresent(existing -> {
                throw PlatformException.conflict("集群名已存在", Map.of("name", name));
            });
            changes.put("name", Map.of("from", cluster.getName(), "to", name));
            cluster.setName(name);
            endpointAffecting = true;
        }
        String entrypoint = normalizeEntrypoint(request.entrypoint());
        if (!entrypoint.equals(cluster.getEntrypoint())) {
            changes.put("entrypoint", Map.of("from", cluster.getEntrypoint(), "to", entrypoint));
            cluster.setEntrypoint(entrypoint);
            endpointAffecting = true;
        }
        String prefix = normalizePrefix(request.pathPrefix());
        if (!prefix.equals(cluster.getPathPrefix())) {
            changes.put("pathPrefix", Map.of("from", cluster.getPathPrefix(), "to", prefix));
            cluster.setPathPrefix(prefix);
            endpointAffecting = true;
        }
        if (request.type() != null && request.type() != cluster.getType()) {
            changes.put("type", Map.of("from", cluster.getType().name(), "to", request.type().name()));
            cluster.setType(request.type());
            endpointAffecting = true;
        }
        if (request.ownerDeptId() != null && !request.ownerDeptId().equals(cluster.getOwnerDeptId())) {
            departmentService.require(request.ownerDeptId());
            changes.put("ownerDeptId", Map.of("from", String.valueOf(cluster.getOwnerDeptId()),
                    "to", String.valueOf(request.ownerDeptId())));
            cluster.setOwnerDeptId(request.ownerDeptId());
            // ownerDeptId 本身不在集群快照里，严格说不必推进 revision；
            // 这里沿用改造前的行为，让本次改动的语义差异只集中在新增的 quota / description 两项。
            endpointAffecting = true;
        }
        if (request.description() != null && !request.description().equals(cluster.getDescription())) {
            changes.put("description", Map.of("from", String.valueOf(cluster.getDescription()),
                    "to", request.description()));
            cluster.setDescription(request.description());
        }
        if (request.enabled() != null && request.enabled() != cluster.isEnabled()) {
            changes.put("enabled", request.enabled());
            cluster.setEnabled(request.enabled());
            endpointAffecting = true;
        }
        if (request.quota() != null) {
            String quota = quotaJson(request.quota());
            if (!java.util.Objects.equals(quota, cluster.getQuota())) {
                changes.put("quota", Map.of("from", String.valueOf(cluster.getQuota()),
                        "to", String.valueOf(quota)));
                cluster.setQuota(quota);
            }
        }
        ExecutorCluster saved = clusterRepository.save(cluster);
        if (endpointAffecting) {
            // 入口或前缀变更会影响已发布端点的可达性，提示运维重新发布
            saved.setRevision(saved.getRevision() + 1);
            clusterRepository.save(saved);
        }
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.CLUSTER_UPDATE, "cluster", saved.getId(), changes);
        }
        return toView(saved);
    }

    /** US-05：把集群的发布权授给部门（共享集群的授权集合是覆盖式写入）。 */
    @Transactional
    public ClusterDtos.ClusterView grant(Long id, ClusterDtos.GrantRequest request, AuthPrincipal principal) {
        ExecutorCluster cluster = require(id);
        Set<Long> deptIds = new LinkedHashSet<>(request.deptIds());
        deptIds.forEach(departmentService::require);
        Set<Long> before = new LinkedHashSet<>(cluster.getGrantedDeptIds());
        cluster.setGrantedDeptIds(deptIds);
        cluster.setRevision(cluster.getRevision() + 1);
        ExecutorCluster saved = clusterRepository.save(cluster);
        auditService.record(AuditAction.CLUSTER_GRANT, "cluster", saved.getId(), Map.of(
                "added", deptIds.stream().filter(x -> !before.contains(x)).toList(),
                "removed", before.stream().filter(x -> !deptIds.contains(x)).toList(),
                "current", deptIds));
        return toView(saved);
    }

    /**
     * 轮换节点接入令牌。明文只在本次响应返回，库里只留 sha256（SEC-01）。
     */
    @Transactional
    public String rotateNodeToken(Long id, AuthPrincipal principal) {
        ExecutorCluster cluster = require(id);
        String token = generateToken();
        cluster.setNodeTokenHash(Hashing.sha256Hex(token));
        clusterRepository.save(cluster);
        auditService.record(AuditAction.CLUSTER_UPDATE, "cluster", id,
                Map.of("action", "rotate-node-token", "tokenHash", Hashing.shortSha256(token)));
        return token;
    }

    @Transactional(readOnly = true)
    public List<ClusterDtos.NodeView> nodes(Long clusterId, AuthPrincipal principal) {
        ExecutorCluster cluster = require(clusterId);
        if (!principal.isPlatformAdmin() && !isRelevant(cluster, principal)) {
            throw PlatformException.forbidden("无权访问该集群的节点列表");
        }
        return nodeRepository.findByClusterIdOrderByIdAsc(clusterId).stream().map(ClusterService::toNodeView).toList();
    }

    @Transactional(readOnly = true)
    public ExecutorCluster require(Long id) {
        return clusterRepository.findById(id).orElseThrow(() -> PlatformException.notFound("Executor 集群", id));
    }

    @Transactional(readOnly = true)
    public ExecutorCluster requireByName(String name) {
        return clusterRepository.findByName(name)
                .orElseThrow(() -> PlatformException.notFound("Executor 集群", name));
    }

    /**
     * 保存集群实体。供 {@link PublishService} 在发布/下线/回滚时推进 {@code revision} 使用：
     * revision 单调递增是 Executor 增量轮询的唯一信号源（EXE-01），任何影响线上快照的写操作都必须过这里。
     */
    @Transactional
    public ExecutorCluster save(ExecutorCluster cluster) {
        return clusterRepository.save(cluster);
    }

    /**
     * 发布授权校验（PUB-01 / MGM-04）：
     * <ul>
     *   <li>集群必须启用；</li>
     *   <li>共享集群：Server 归属部门必须在授权集合内；</li>
     *   <li>私有集群：Server 归属部门必须是集群拥有者部门或其下级；</li>
     *   <li>平台管理员不受限。</li>
     * </ul>
     */
    public void requirePublishPermission(ExecutorCluster cluster, Long serverDeptId, AuthPrincipal principal) {
        if (!cluster.isEnabled()) {
            throw PlatformException.conflict("集群已停用，无法发布", Map.of("cluster", cluster.getName()));
        }
        if (principal.isPlatformAdmin()) {
            return;
        }
        if (cluster.getType() == ClusterType.SHARED) {
            if (!cluster.allowsDepartment(serverDeptId)) {
                throw PlatformException.forbidden(
                        "该共享集群未授权给你的部门，请联系平台管理员在「集群授权」中添加");
            }
            return;
        }
        if (!cluster.getOwnerDeptId().equals(serverDeptId)
                && !departmentScope.descendantDeptIds(cluster.getOwnerDeptId()).contains(serverDeptId)) {
            throw PlatformException.forbidden("私有集群只允许拥有者部门及其下级发布");
        }
    }

    public String endpointTemplate(ExecutorCluster cluster) {
        String prefix = cluster.getPathPrefix() == null || cluster.getPathPrefix().isBlank()
                ? properties.defaultPathPrefix() : cluster.getPathPrefix();
        return cluster.getEntrypoint().replaceAll("/+$", "") + "/" + prefix + "/{末段}";
    }

    /**
     * 读取集群配额。
     *
     * <p>解析失败时按「不限」处理并打 WARN，而不是抛异常：配额是容量保护、不是安全边界，
     * 一条手工写坏的 JSONB 不应该让整个集群彻底无法发布。V9 的 CHECK 约束已保证它至少是对象，
     * 这里兜的是键值层面的脏数据（例如 {@code "maxServers":"abc"}）。
     */
    public ClusterQuota quotaOf(ExecutorCluster cluster) {
        String json = cluster.getQuota();
        if (json == null || json.isBlank()) {
            return ClusterQuota.UNLIMITED;
        }
        try {
            return ClusterQuota.of(Json.toMap(Json.tree(json)));
        } catch (RuntimeException e) {
            log.warn("集群配额解析失败，本次按「不限」处理 clusterId={} raw={}", cluster.getId(), json, e);
            return ClusterQuota.UNLIMITED;
        }
    }

    /** 请求体里的配额 → 落库 JSON；全维度缺省时存 null（而不是 {@code {}}），让「不限」只有一种表示。 */
    private static String quotaJson(Map<String, Object> raw) {
        ClusterQuota quota = ClusterQuota.of(raw);
        return quota.isUnlimited() ? null : Json.write(quota.asMap());
    }

    private boolean isRelevant(ExecutorCluster cluster, AuthPrincipal principal) {
        if (cluster.allowsDepartment(principal.deptId())) {
            return true;
        }
        if (principal.deptId() != null && principal.deptId().equals(cluster.getOwnerDeptId())) {
            return true;
        }
        // 部门管理员能看到下级部门被授权的集群
        Set<Long> visible = departmentScope.descendantDeptIds(principal.deptId());
        return cluster.getGrantedDeptIds().stream().anyMatch(visible::contains);
    }

    private ClusterDtos.ClusterView toView(ExecutorCluster cluster) {
        // 集群列表页只需要一个「已发布数」，用 count 查询而不是把整行（含 jsonb snapshot）拉回来
        long published = bindingRepository.countByClusterIdAndCurrentTrueAndState(
                cluster.getId(), BindingState.PUBLISHED);
        // 不限时回 null 而不是三个 null 字段的对象：前端只需要判断"有没有配额"
        ClusterQuota quota = quotaOf(cluster);
        return new ClusterDtos.ClusterView(
                cluster.getId(),
                cluster.getName(),
                cluster.getType(),
                cluster.getEntrypoint(),
                cluster.getPathPrefix(),
                endpointTemplate(cluster),
                cluster.getOwnerDeptId(),
                departmentService.nameOf(cluster.getOwnerDeptId()),
                cluster.getDescription(),
                cluster.isEnabled(),
                new LinkedHashSet<>(cluster.getGrantedDeptIds()),
                nodeRepository.countByClusterId(cluster.getId()),
                nodeRepository.countByClusterIdAndStatus(cluster.getId(), NodeStatus.ONLINE),
                published,
                cluster.getRevision(),
                cluster.getCreatedAt(),
                quota.isUnlimited() ? null : quota);
    }

    static ClusterDtos.NodeView toNodeView(ExecutorNode node) {
        Map<String, Object> loadInfo = Map.of();
        if (node.getLoadInfo() != null && !node.getLoadInfo().isBlank()) {
            try {
                Map<String, Object> parsed = Json.toMap(Json.tree(node.getLoadInfo()));
                loadInfo = parsed == null ? Map.of() : parsed;
            } catch (RuntimeException e) {
                loadInfo = Map.of();
            }
        }
        return new ClusterDtos.NodeView(
                node.getId(), node.getClusterId(), node.getNodeKey(), node.getHost(), node.getPort(),
                node.getVersion(), node.getProtocolVersion(), node.getStatus(),
                node.getLastHeartbeatAt(), loadInfo);
    }

    private String normalizeEntrypoint(String entrypoint) {
        if (entrypoint == null || entrypoint.isBlank()) {
            throw PlatformException.validation("集群入口地址不能为空", Map.of("field", "entrypoint"));
        }
        String trimmed = entrypoint.trim().replaceAll("/+$", "");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw PlatformException.validation("集群入口地址必须以 http:// 或 https:// 开头",
                    Map.of("field", "entrypoint", "value", trimmed));
        }
        return trimmed;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return properties.defaultPathPrefix();
        }
        String trimmed = prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (!PathSegments.isValid(trimmed)) {
            throw PlatformException.validation("平台保留前缀只能包含小写字母、数字、连字符与下划线",
                    Map.of("field", "pathPrefix", "value", trimmed));
        }
        return trimmed;
    }

    /** 32 字节随机令牌的 base64url 编码，无填充，便于直接放进环境变量与 HTTP 头。 */
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}