package com.mcpbridge.manager.web.dto;

import com.mcpbridge.manager.domain.BindingState;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 发布 DTO（PUB-03/04/05）。
 */
public final class PublishDtos {

    private PublishDtos() {
    }

    public record PublishRequest(
            @NotNull Long clusterId,
            @Size(max = 255) String note) {
    }

    public record BindingView(
            Long id,
            Long serverId,
            String serverName,
            String pathSegment,
            Long clusterId,
            String clusterName,
            String clusterType,
            long version,
            BindingState state,
            boolean current,
            /** 完整对外端点：{集群入口}/{保留前缀}/{末段}。 */
            String endpoint,
            Long publishedBy,
            Instant publishedAt,
            Instant offlinedAt,
            String failureReason,
            int toolCount) {
    }

    /**
     * @param endpoint 发布成功后的可调用地址
     * @param revision 集群快照新版本（Executor 轮询到此版本即生效）
     */
    public record PublishResult(
            Long bindingId,
            long version,
            String endpoint,
            String clusterName,
            BindingState state,
            long revision,
            String message) {
    }

    /** 回滚到历史版本（PUB-04：≤30s 内集群生效）。 */
    public record RollbackRequest(@NotNull Long version) {
    }
}