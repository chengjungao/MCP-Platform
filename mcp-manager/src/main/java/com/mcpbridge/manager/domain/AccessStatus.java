package com.mcpbridge.manager.domain;

/** 跨部门访问申请状态。 */
public enum AccessStatus {
    /** 待资源方（Server 所属部门）审批。 */
    PENDING,
    /** 已授权（整部门只读）。 */
    APPROVED,
    /** 已驳回（可带新理由重新申请）。 */
    REJECTED,
    /** 资源方回收（仅 APPROVED 可转）。 */
    REVOKED
}
