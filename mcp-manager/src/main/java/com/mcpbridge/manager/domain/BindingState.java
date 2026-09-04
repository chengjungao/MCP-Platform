package com.mcpbridge.manager.domain;

/** 发布记录状态（PUB-03 / PUB-04）。 */
public enum BindingState {
    /** 已生成快照但尚未生效（预留审批流，SVR-07）。 */
    DRAFT,
    /** 当前生效版本。 */
    PUBLISHED,
    /** 已下线：Executor 侧对应 PATH 立即 404。 */
    OFFLINE,
    /** 发布失败：权限 / PATH 冲突 / 无可用节点。 */
    FAILED
}