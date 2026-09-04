package com.mcpbridge.manager.security;

import com.mcpbridge.common.util.Hashing;
import com.mcpbridge.manager.config.ManagerProperties;
import com.mcpbridge.manager.domain.ExecutorCluster;
import com.mcpbridge.manager.repository.ExecutorClusterRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * 内部通道鉴权：Executor 节点用 {@code X-Executor-Token} 访问 {@code /internal/v1/**}
 * （快照拉取、节点注册、心跳）。
 *
 * <p>最小授权原则（SEC-01）：该令牌只能读发布快照与上报心跳，不能访问任何 {@code /api/**}。
 * 令牌按 sha256 常量时间比较，明文不落库、不落日志。
 *
 * <p>两级令牌：
 * <ul>
 *   <li><b>集群令牌</b>：{@code executor_cluster.node_token_hash} 命中，身份含 clusterId，
 *       可拉取该集群快照；</li>
 *   <li><b>引导令牌</b>：{@code mcp.manager.executor.bootstrap-token} 命中，仅用于首次注册
 *       （此时节点还不知道自己属于哪个集群）。</li>
 * </ul>
 * 集群令牌优先：默认集群在引导阶段就把 node_token_hash 设为引导令牌的哈希，
 * 因此开箱即用的部署里两者等价，而多集群部署下各集群令牌互相隔离。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 节点身份对应的权限标记。 */
    public static final String AUTHORITY_EXECUTOR_NODE = "EXECUTOR_NODE";

    public static final String TOKEN_HEADER = "X-Executor-Token";

    private final byte[] bootstrapTokenHash;
    private final ExecutorClusterRepository clusterRepository;

    public InternalTokenFilter(ManagerProperties properties, ExecutorClusterRepository clusterRepository) {
        this.bootstrapTokenHash = Hashing.sha256Hex(properties.executor().bootstrapToken())
                .getBytes(StandardCharsets.UTF_8);
        this.clusterRepository = clusterRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            authenticate(token.trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        byte[] actual = Hashing.sha256Hex(token).getBytes(StandardCharsets.UTF_8);
        // 内部通道调用频率是「节点数 × 轮询间隔」量级，直接查库可接受；后续可加短 TTL 缓存
        Optional<ExecutorCluster> cluster = clusterRepository.findByNodeTokenHash(new String(actual, StandardCharsets.UTF_8));
        if (cluster.isPresent() && cluster.get().isEnabled()) {
            grant(new NodePrincipal(cluster.get().getId(), cluster.get().getName(), false));
            return;
        }
        if (MessageDigest.isEqual(bootstrapTokenHash, actual)) {
            grant(NodePrincipal.ofBootstrap());
        }
    }

    private void grant(NodePrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(AUTHORITY_EXECUTOR_NODE))));
    }
}