package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一个 MCP Server 可挂载多份 Swagger（多个 REST 服务），每份对应一个 UpstreamEntry。
 *
 * <p>沿用既有 {@code effectiveAuthB(server, tool)} 的同构模式：
 * Server 持有 {@code List<UpstreamEntry>}（按 {@code serviceId} 索引），
 * Tool 带 {@code upstreamRef} 指向其中某一个。运行时由
 * {@link ServerSnapshot#effectiveUpstream(ToolSnapshot)} 按 ref 查找。
 *
 * <p>Auth-B 下沉到服务维度：同一 Server 内不同 REST 服务可能有不同鉴权，
 * 因此 {@code authB} 是本服务专属。为 null 时回落到 Server 级配置。
 *
 * @param serviceId 业务标识（来自 registrationId 或 slug(title)#shortHash），tool.upstreamRef 指向它
 * @param name      展示名（来自 Swagger info.title）
 * @param config    上游调用策略：baseUrls / LB / 超时 / 重试 / 熔断
 * @param authB     本服务专属上行鉴权；为 null 时回落到 Server 级
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpstreamEntry(
        String serviceId,
        String name,
        UpstreamSnapshot config,
        AuthBSnapshot authB) {

    /** 兼容单上游场景的便捷工厂（迁移期/单服务 Server 用）。 */
    public static UpstreamEntry single(String serviceId, UpstreamSnapshot config) {
        return new UpstreamEntry(serviceId, serviceId, config, null);
    }

    /** 带名称与鉴权的完整工厂。 */
    public static UpstreamEntry of(String serviceId, String name, UpstreamSnapshot config, AuthBSnapshot authB) {
        return new UpstreamEntry(serviceId, name, config, authB);
    }
}
