-- V3: 空 Server 支持修复 —— registration_id 允许为 NULL
-- 根因：V1 里 registration_id 带 FK→api_registration(id)，新建空 Server 时占位 0L
-- 触发外键冲突（api_registration 无 id=0 行），且被全局异常处理器误报为唯一约束冲突。
-- 语义修正：空 Server 还没有挂任何注册，registration_id 应为 NULL；
-- 首次注册（新建或聚合）时回填为「主 registration」。FK 本身保留，NULL 不受 FK 约束。

ALTER TABLE mcp_server ALTER COLUMN registration_id DROP NOT NULL;
