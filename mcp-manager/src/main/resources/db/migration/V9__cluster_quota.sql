-- V9: 集群发布配额（把 executor_cluster.scopes 从死字段变成可读可用的配额）
--
-- 背景：V1 建表时留了 `scopes jsonb`（PRD v0.2 注释写"可发布范围/配额"），但全仓只有写入方、
-- 没有任何读取方——存进去的值从不参与任何校验。这是典型的半成品：字段在、注释在、语义不在。
--
-- 本迁移把它正名为 quota，语义收敛成一组可执行的容量上限：
--     {"maxServers": n, "maxToolsPerServer": n, "maxCatalogItemsPerServer": n}
-- 三个键都可缺省，缺省 = 该维度不限；整列为 null = 整体不限（默认）。
--
-- 为什么改名而不是只加注释：同一套 DTO 里已经有一个 scopes（Auth-C/Auth-D 的 OAuth 2.1
-- scope 列表，见 AuthDSnapshot.scopes）。两个 scopes 含义完全不同却同名，必然被误读。
-- 该列此前零读取方，改名没有运行时风险。

ALTER TABLE executor_cluster RENAME COLUMN scopes TO quota;

-- 形状约束：要么 null，要么必须是 JSON 对象。
-- 不校验具体键（键集合还会演进），但挡住"写进一个数组/标量"这种必炸的形态。
ALTER TABLE executor_cluster
    ADD CONSTRAINT ck_cluster_quota_is_object
        CHECK (quota IS NULL OR jsonb_typeof(quota) = 'object');

COMMENT ON COLUMN executor_cluster.quota IS
    '发布配额：{"maxServers":n,"maxToolsPerServer":n,"maxCatalogItemsPerServer":n}；缺省键=该维度不限，null=整体不限。发布前由 PublishService 校验。';
