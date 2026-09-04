package com.mcpbridge.manager.domain;

/**
 * MCP Server 生命周期状态（PRD §5.8 发布生命周期）。
 */
public enum ServerStatus {
    /** 注册完成，基座已生成。 */
    DRAFT,
    /** PATH / 覆盖 / Auth-B 完备，可发布。 */
    CONFIGURED,
    /** 已发布到至少一个集群。 */
    PUBLISHED,
    /** 全部集群已下线。 */
    OFFLINE,
    /** 发布失败（权限 / PATH 冲突 / 节点失联），可回到 CONFIGURED。 */
    PUBLISH_FAILED
}