package com.mcpbridge.manager.domain;

/** 注册与解析状态（REG-01：解析异步化 + 状态可见）。 */
public enum RegistrationStatus {
    /** 已收到文档，解析任务排队中。 */
    PARSING,
    /** 解析成功，基座模型已生成。 */
    READY,
    /** 解析失败，diagnostics 中含结构化诊断，可重试。 */
    FAILED
}