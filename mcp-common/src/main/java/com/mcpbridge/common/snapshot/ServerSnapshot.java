package com.mcpbridge.common.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 单个已发布 MCP Server 的生效模型（base ⊕ overlay 合并结果），是 Executor 运行时唯一加载的配置单元。
 *
 * <p>由控制面在发布时生成并写入 {@code publish_binding.snapshot}；Executor 通过
 * {@code GET /internal/v1/clusters/{clusterId}/snapshot} 拉取（EXE-01）。
 *
 * <p><b>多上游模型</b>：一个 Server 可挂载多份 Swagger（多个 REST 服务），每份对应一个
 * {@link UpstreamEntry}（按 {@code serviceId} 索引）。Tool 的 {@code upstreamRef} 指向其中某一个，
 * 运行时由 {@link #effectiveUpstream(ToolSnapshot)} 按 ref 查找（同 {@code effectiveAuthB} 同构模式）。
 * Auth-B 下沉到 UpstreamEntry，{@link #authB()} 作为 Server 级回落默认值。
 *
 * @param serverId        Server 主键
 * @param deptId          归属部门（共享集群多租户隔离与埋点用，R6）
 * @param name            内部名（可含版本，与 PATH 解耦，BR-3）
 * @param pathSegment     对外 PATH 末段（稳定契约）
 * @param title           展示标题
 * @param description     Server 描述
 * @param version         服务/文档版本
 * @param protocolVersion 固定 2026-07-28（决策 D1）
 * @param bindingVersion  发布版本号（回滚以此为准，PUB-04）
 * @param endpoint        完整对外端点 {集群入口}/{保留前缀}/{末段}
 * @param authD           下行跳鉴权
 * @param authB           上行跳鉴权（Server 级默认；具体服务在 upstreams 里覆盖）
 * @param upstreams       上游服务列表（每个 REST 服务一个 UpstreamEntry，按 serviceId 索引）
 * @param tools           生效 tool 列表（仅含 enabled=true）
 * @param resources       P1
 * @param prompts         P1
 * @param listTtlMs       tools/list 等 list 响应的缓存 TTL（SEP-2549）
 * @param publishedAt     发布时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerSnapshot(
        long serverId,
        long deptId,
        String name,
        String pathSegment,
        String title,
        String description,
        String version,
        String protocolVersion,
        long bindingVersion,
        String endpoint,
        AuthDSnapshot authD,
        AuthBSnapshot authB,
        List<UpstreamEntry> upstreams,
        List<ToolSnapshot> tools,
        List<ResourceSnapshot> resources,
        List<PromptSnapshot> prompts,
        int listTtlMs,
        Instant publishedAt) {

    public Optional<ToolSnapshot> tool(String name) {
        if (tools == null || name == null) {
            return Optional.empty();
        }
        return tools.stream().filter(t -> name.equals(t.name())).findFirst();
    }

    public List<ToolSnapshot> safeTools() {
        return tools == null ? List.of() : tools;
    }

    public List<UpstreamEntry> safeUpstreams() {
        return upstreams == null ? List.of() : upstreams;
    }

    /** 按 serviceId 精确查找上游；找不到返回空。 */
    public Optional<UpstreamEntry> upstream(String serviceId) {
        if (upstreams == null || serviceId == null) {
            return Optional.empty();
        }
        return upstreams.stream().filter(u -> serviceId.equals(u.serviceId())).findFirst();
    }

    /**
     * 运行时按 {@code tool.upstreamRef} 查找所属上游。与 {@code effectiveAuthB} 同构模式：
     * ref 命中则用命中的 UpstreamEntry；ref 为空回落到第一个 upstream；upstreams 为空返回兜底实例。
     */
    public UpstreamEntry effectiveUpstream(ToolSnapshot tool) {
        String ref = tool == null ? null : tool.upstreamRef();
        Optional<UpstreamEntry> hit = upstream(ref);
        if (hit.isPresent()) {
            return hit.get();
        }
        if (upstreams != null && !upstreams.isEmpty()) {
            return upstreams.get(0);
        }
        return UpstreamEntry.single("default", UpstreamSnapshot.defaults(List.of()));
    }
}
