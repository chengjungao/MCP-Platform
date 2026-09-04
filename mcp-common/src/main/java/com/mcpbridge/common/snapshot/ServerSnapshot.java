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
 * @param authB           上行跳鉴权（Server 级默认）
 * @param upstream        上游调用策略
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
        UpstreamSnapshot upstream,
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
}
