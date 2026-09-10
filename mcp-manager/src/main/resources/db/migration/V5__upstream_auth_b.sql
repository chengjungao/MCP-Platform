-- V5: Auth-B 下沉到 REST 服务维度 —— 每个 REST 服务独立上行鉴权（SVR-03 / BR-4）
-- 背景：原先 Auth-B 只有 Server 级一份，一个 Server 挂多个 REST 服务时只能共用同一套凭据。
-- 平台未上线，不做兼容迁移，破坏性变更。
--
-- 维度约定（server_id, tool_id, upstream_service_id）：
--   Server 级    tool_id = 0,        upstream_service_id IS NULL
--   Tool 级      tool_id = <toolId>, upstream_service_id IS NULL
--   REST 服务级  tool_id = 0,        upstream_service_id = '<serviceId>'

ALTER TABLE auth_config ADD COLUMN upstream_service_id VARCHAR(64);

ALTER TABLE auth_config DROP CONSTRAINT IF EXISTS uk_auth_config_server_tool;

-- 用 COALESCE 让 NULL 参与唯一性比较：PostgreSQL 默认视多个 (server_id, 0, NULL) 互不冲突，
-- 那样 Server 级配置会被重复插入。
CREATE UNIQUE INDEX uk_auth_config_scope
    ON auth_config (server_id, tool_id, COALESCE(upstream_service_id, ''));

-- server_upstream.auth_b 是上一版预留但从未写入过的列（无任何代码写它）。
-- Auth-B 现由 auth_config 的 REST 服务维度承载，删除该列以免留下语义不明的死字段。
ALTER TABLE server_upstream DROP COLUMN IF EXISTS auth_b;
