-- =============================================================================
--  EXE-01 轮询优化：把「快照指纹」从 jsonb 里搬到独立列
--
--  背景：Executor 每 10s 打一次 GET /internal/v1/clusters/{id}/revision，只为拿
--  revision + etag。改造前这个端点走的是集群全量装配——把该集群所有 current 绑定的
--  jsonb snapshot 全部 detoast + 反序列化 + 排序，再算 etag。300 接口的集群下，
--  每个节点每 10s 就要白付一次几 MB 的 TOAST 读取。
--
--  改造后 etag 只由 publish_binding.fingerprint 投影列算出（pathSegment:version:toolCount），
--  与 snapshot 的 detoast 彻底解耦。指纹在发布/回滚写入时固化，语义与原来一致：
--    etag = sha256(集群名 | revision | 各 current 绑定指纹按字典序 join)
--
--  回填算法必须与 SnapshotAssembler.fingerprint(...) 逐字一致，否则历史行与新行的
--  etag 口径会对不上（同一份快照算出两个 etag）。
-- =============================================================================

ALTER TABLE publish_binding
    ADD COLUMN fingerprint VARCHAR(320);

-- pathSegment 理论上不可能为空（BR-3 唯一性校验在前），COALESCE 只是与 Java 侧
-- node.path("pathSegment").asText("") 的兜底语义对齐；tools 缺失时按 0 处理。
UPDATE publish_binding
SET fingerprint = COALESCE(snapshot ->> 'pathSegment', '')
    || ':' || version::text
    || ':' || COALESCE(jsonb_array_length(snapshot -> 'tools')::text, '0')
WHERE snapshot IS NOT NULL;

COMMENT ON COLUMN publish_binding.fingerprint IS
    'EXE-01：快照指纹 pathSegment:bindingVersion:toolCount，供 /revision 免 detoast 计算 etag';
