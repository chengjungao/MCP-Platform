package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.common.util.PathSegments;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.repository.McpServerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 对外 PATH 末段的合法性与唯一性守卫（BR-3 / SVR-01）。
 *
 * <p>端点结构 {@code {集群入口}/{平台保留前缀}/{自定义末段}}：用户只能改末段。
 * 末段是<b>稳定契约</b>——MCP Client 会把端点写进配置，改名等于断链，因此：
 * <ul>
 *   <li>必须匹配 {@code ^[a-z0-9]([a-z0-9-_]{0,62}[a-z0-9])?$}；</li>
 *   <li>在共享集群内唯一。P0 直接按全局唯一实现（比 PRD 更严格，
 *       等私有集群需要复用同名末段时再按 clusterId 收窄），冲突时返回 409 与占用方信息；</li>
 *   <li>注册时若用户未指定，则从服务名推导；中文名等无法推导的场景用 {@code api-<短哈希>} 兜底，
 *       保证一定有合法且稳定的末段。</li>
 * </ul>
 */
@Component
public class PathSegmentGuard {

    private final McpServerRepository serverRepository;

    public PathSegmentGuard(McpServerRepository serverRepository) {
        this.serverRepository = serverRepository;
    }

    /** 规范化并校验唯一性。{@code excludeServerId} 用于「更新自身」时排除自己。 */
    @Transactional(readOnly = true)
    public String requireAvailable(String rawSegment, Long excludeServerId) {
        String segment = PathSegments.requireValid(rawSegment);
        List<McpServer> conflicts = serverRepository.findByPathSegment(segment).stream()
                .filter(s -> excludeServerId == null || !s.getId().equals(excludeServerId))
                .toList();
        if (!conflicts.isEmpty()) {
            McpServer first = conflicts.get(0);
            throw PlatformException.conflict(
                    "PATH 末段已被占用，请换一个（末段在共享集群内必须唯一）",
                    Map.of("field", "pathSegment",
                            "value", segment,
                            "occupiedByServerId", first.getId(),
                            "occupiedByServerName", String.valueOf(first.getName())));
        }
        return segment;
    }

    /**
     * 从服务名推导末段：能规范化就用规范化结果，否则用 {@code api-<短哈希>} 兜底。
     * 兜底值由 seed 决定，保证同一次注册重复调用得到相同结果（可重入）。
     */
    @Transactional(readOnly = true)
    public String derive(String preferred, String seed) {
        String candidate = preferred == null ? null : PathSegments.normalize(
                preferred.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-"));
        if (candidate != null && PathSegments.isValid(candidate)) {
            return requireAvailable(candidate, null);
        }
        String fallback = "api-" + Hashing.shortSha256(seed == null ? String.valueOf(System.nanoTime()) : seed);
        return requireAvailable(fallback, null);
    }

    /** 拼接完整对外端点，供 UI 预览与快照写入。 */
    public String endpoint(String entrypoint, String reservedPrefix, String segment) {
        return PathSegments.endpoint(entrypoint, reservedPrefix, segment);
    }
}