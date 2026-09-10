-- V8: 审计 append-only 加固（MGM-05 / SEC-02）
--
-- 背景：在此之前，"append-only" 只由两件事保证——仓储接口没有暴露 deleteBy* 派生方法、
-- 控制台没有删除入口。这属于应用层自觉：任何能连上库的人（psql、备份还原脚本、误执行的
-- 一次性 SQL、被攻陷的应用账号）都能 UPDATE 掉一条记录，审计随即失去证据价值。
--
-- 两层防护，缺一不可：
--   1) REVOKE UPDATE/DELETE/TRUNCATE —— 挡住非属主角色。
--      之所以不够：表属主天然拥有全部权限，而应用通常就是用属主账号连库。
--   2) BEFORE UPDATE/DELETE 触发器 —— 对属主同样生效，这才是真正的兜底。
--
-- 刻意不做的：
--   * 不为 TRUNCATE 建触发器。TRUNCATE 是整表清空、需要独立的 TRUNCATE 权限，
--     属于"删库"而非"改历史"；本迁移的目标是不让单条历史被静默篡改。
--   * 不建分区与归档表。那是容量与保留期问题，与本条无关。
--
-- 需要合法清理时（例如合规要求删除某人的历史记录），必须走显式 DDL：
--     ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update;
--     <执行清理>
--     ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update;
-- 这样必然留下 DDL 级痕迹（PG 日志 + 变更单），而不是一条没人看得见的 DELETE。

CREATE OR REPLACE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log 是 append-only 表，禁止 %（记录 id=%）',
        TG_OP, COALESCE(OLD.id::text, '?')
        USING ERRCODE = 'restrict_violation',
              HINT = '审计记录不可改删；如需合规清理，请走 DDL 显式停用触发器并留变更单';
END;
$$;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION audit_log_append_only();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION audit_log_append_only();

-- 非属主角色（应用改用独立角色连库时即生效）。属主的隐式权限不受影响，
-- 这也是为什么不能只靠这一条。
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;
