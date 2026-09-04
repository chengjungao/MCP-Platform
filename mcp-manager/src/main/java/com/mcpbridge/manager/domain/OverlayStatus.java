package com.mcpbridge.manager.domain;

/**
 * Tool 覆盖层状态（BR-2 / REG-03）。
 *
 * <p>{@code SUSPENDED} 表示上游文档升级后锚点失效，覆盖进入挂起区：
 * 不静默丢弃、不自动删除，由用户在 diff 报告中逐条处理。
 */
public enum OverlayStatus {
    NONE, ACTIVE, SUSPENDED
}