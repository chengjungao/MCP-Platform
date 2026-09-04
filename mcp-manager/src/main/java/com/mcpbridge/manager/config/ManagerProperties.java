package com.mcpbridge.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 控制面配置（前缀 {@code mcp.manager}）。
 *
 * <p>所有密钥类配置都来自环境变量，仓库内只保留开发默认值（README 有醒目提示）。
 *
 * @param jwt               登录令牌
 * @param crypto            Auth-B 凭据字段级加密主密钥（SEC-01）
 * @param executor          节点接入与心跳（PUB-02）
 * @param defaultPathPrefix 对外 PATH 保留前缀默认值（BR-3，可被集群配置覆盖）
 * @param parse             解析限制（REG-01/02）
 * @param bootstrap         首次启动初始化内置角色与管理员
 */
@ConfigurationProperties(prefix = "mcp.manager")
public record ManagerProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Crypto crypto,
        @DefaultValue Executor executor,
        @DefaultValue("mcp") String defaultPathPrefix,
        @DefaultValue Parse parse,
        @DefaultValue Bootstrap bootstrap) {

    /**
     * @param secret HS256 签名密钥源串（平台按 SHA-256 派生 32 字节密钥）
     * @param ttl    令牌有效期
     * @param issuer 签发者
     */
    public record Jwt(
            @DefaultValue("dev-only-jwt-secret-please-change-me") String secret,
            @DefaultValue("8h") Duration ttl,
            @DefaultValue("mcp-manager") String issuer) {
    }

    /** @param key AES-256-GCM 主密钥源串（生产必须由环境变量/KMS 注入） */
    public record Crypto(@DefaultValue("dev-only-crypto-key-please-change-me") String key) {
    }

    /**
     * @param bootstrapToken            节点接入令牌（Manager 侧只存 sha256）
     * @param heartbeatInterval         期望心跳周期
     * @param heartbeatTimeoutMultiplier 失联判定倍数（PUB-02：3 个周期标记离线）
     */
    public record Executor(
            @DefaultValue("dev-executor-bootstrap-token") String bootstrapToken,
            @DefaultValue("10s") Duration heartbeatInterval,
            @DefaultValue("3") int heartbeatTimeoutMultiplier) {

        public Duration offlineThreshold() {
            return heartbeatInterval.multipliedBy(Math.max(1, heartbeatTimeoutMultiplier));
        }
    }

    /**
     * @param maxDocumentBytes 文档大小上限
     * @param resolveFully     是否展开全部 $ref（大文档可关闭以提速）
     */
    public record Parse(
            @DefaultValue("20971520") long maxDocumentBytes,
            @DefaultValue("true") boolean resolveFully,
            /** URL 拉取超时。 */
            @DefaultValue("10s") Duration fetchTimeout,
            /**
             * 是否允许拉取内网/回环地址的文档。
             * 默认 false 以防 SSRF；本地联调需显式打开。
             */
            @DefaultValue("false") boolean allowPrivateNetworks) {
    }

    /**
     * @param enabled         是否启用初始化
     * @param rootDepartment  根部门名
     * @param adminUsername   初始管理员用户名
     * @param adminPassword   初始管理员密码（首次登录后应立即修改）
     */
    public record Bootstrap(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("平台总部") String rootDepartment,
            @DefaultValue("admin") String adminUsername,
            @DefaultValue("admin123") String adminPassword,
            /** 默认共享集群名：Executor 注册时按此名匹配。 */
            @DefaultValue("default") String clusterName,
            /** 默认集群对外入口，用于拼接端点与 UI 展示。 */
            @DefaultValue("http://localhost:9090") String clusterEntrypoint) {
    }
}