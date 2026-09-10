# API 契约

本文是**实现契约**，与代码同源。凡与 PRD 描述不一致处，以本文为准（差异原因写在
[架构说明 §10](ARCHITECTURE.md#10-已知取舍与缺口)）。

三个入口互相独立，鉴权方式与响应形状都不同：

| 入口 | 前缀 | 鉴权 | 响应形状 |
| --- | --- | --- | --- |
| 控制面公开 API | `/api/v1/**` | `Authorization: Bearer <JWT>` | `ApiResponse<T>` 信封 |
| 控制面内部通道 | `/internal/v1/**` | `X-Executor-Token: <节点令牌>` | **裸对象，无信封** |
| 数据面 MCP 端点 | `/{prefix}/{segment}` | Auth-D（按 Server 配置） | JSON-RPC 2.0 |

---

## 1. 通用约定

### 1.1 响应信封

`/api/v1/**` 一律返回：

```json
{
  "success": true,
  "code": "OK",
  "message": null,
  "data": { },
  "details": null,
  "timestamp": "2026-09-03T12:34:56.789Z"
}
```

| 字段 | 说明 |
| --- | --- |
| `success` | 布尔，成功恒为 true（失败走非 2xx 状态码 + 同形状信封） |
| `code` | 成功为 `"OK"`，失败为 `ErrorCode` 枚举名 |
| `message` | 人类可读说明，可直接展示给用户 |
| `data` | 业务载荷，失败时为 null |
| `details` | 结构化附加信息（字段级校验原因、发布前校验清单、legacy 拒绝原因等） |
| `timestamp` | 服务端时间 |

### 1.2 错误码

| `code` | HTTP | 触发场景 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 请求体或参数校验失败，`details` 含字段名 |
| `UNAUTHENTICATED` | 401 | 未登录或令牌无效 |
| `FORBIDDEN` | 403 | 缺权限点，**或跨部门越权**（MGM-04：越权明确回 403 而非 404） |
| `NOT_FOUND` | 404 | 资源不存在 |
| `CONFLICT` | 409 | 唯一性冲突（如共享集群内 PATH 末段重复，BR-3） |
| `UNSUPPORTED_PROTOCOL_VERSION` | 400 | 协议版本不受支持（决策 D1） |
| `PARSE_FAILED` | 422 | Swagger/OpenAPI 解析失败，`details` 携带结构化诊断 |
| `INVALID_STATE` | 409 | 状态机不允许的操作（如发布前校验未通过） |
| `UPSTREAM_CALL_FAILED` | 502 | 上游 REST 调用失败 |
| `INTERNAL_ERROR` | 500 | 平台内部错误 |

### 1.3 分页

分页接口用 Spring Data 的查询参数：`page`（0 起）、`size`、`sort=field,asc|desc`。
响应 `data` 为：

```json
{ "items": [], "total": 0, "page": 0, "size": 20, "totalPages": 0 }
```

刻意不直接序列化 Spring 的 `Page`——它带大量内部字段，会随 Spring 版本漂移。

### 1.4 两种写入语义（**必须区分**）

| 接口类型 | 语义 | 未传字段的后果 |
| --- | --- | --- |
| **覆盖式**：`PUT /servers/{id}`、`PUT /servers/{id}/tools/{toolId}/overlay` | `null` = 不修改，`""` = **清除覆盖**、回落基座值 | 保持不变 |
| **整体替换**：`PUT /servers/{id}/upstream`、`PUT /servers/{id}/auth-d`、`PUT /clusters/{id}/grants` | 未传字段回落**后端默认值** | 被重置 |

把覆盖式接口的生效值原样回显再提交，会凭空造出一条「与基座完全相同」的覆盖，
让 `overlayStatus` 从 `NONE` 变成 `ACTIVE`。详见 [ADR-0002](adr/ADR-0002-base-overlay-merge.md)。

`PUT /servers/{id}/auth-b` 是第三种：**非密钥字段整体替换，密钥字段留空 = 保持不变**
（因为平台只回掩码，前端不可能回传明文；若把掩码当明文存进去，凭据就被覆盖成 `****xxxx` 了）。
`type=NONE` 时清空全部密钥。

---

## 2. 控制面公开 API（`/api/v1`）

### 2.1 元信息

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/meta` | 已登录 | 协议版本、legacy 版本集合、升级引导 URL、JSON Schema 方言、默认 ttlMs、默认路径前缀、**PATH 末段正则**、**tool 名正则**、内置角色码 |

`pathSegmentPattern` / `toolNamePattern` 序列化为正则**字符串**（Jackson 对 `Pattern` 走
`ToStringSerializer`）。前端应从这里取校验规则，不要自己硬编码一份。

### 2.2 登录与当前用户

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 公开 | `{username, password}` → `{token, tokenType, expiresInSeconds, user}` |
| GET | `/auth/me` | 已登录 | 当前用户视图（含权限点，前端按此渲染菜单与按钮） |
| POST | `/auth/change-password` | 已登录 | `{currentPassword, newPassword}` |
| POST | `/auth/logout` | 已登录 | 服务端主体缓存失效；令牌真正的失效由客户端丢弃完成 |

### 2.3 组织与账号（MGM-01/02/03）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/users` | `user:read` | 分页 |
| GET | `/users/{id}` | `user:read` | |
| POST | `/users` | `user:write` | `{username, password, displayName?, email?, deptId?, roleCodes?}`；`deptId` 空则归属创建者部门 |
| PUT | `/users/{id}` | `user:write` | 所有字段可选，只更新传入的部分；`password` 非空时重置口令 |
| GET | `/departments/tree` | `dept:read` | 树形，节点含 `memberCount` |
| GET | `/departments` | `dept:read` | 平铺列表 |
| POST | `/departments` | `dept:write` | `{name, parentId?, description?, enabled?}` |
| PUT | `/departments/{id}` | `dept:write` | **父级不得指向自身子树**，否则树成环、`/departments/tree` 递归组装会栈溢出 |
| DELETE | `/departments/{id}` | `dept:write` | |
| GET | `/roles` | `role:read` | |
| GET | `/permissions` | 已登录 | 权限点全集（前端角色配置界面用） |
| POST | `/roles` | `role:write` | `{code, name, description?, permissions}` |
| PUT | `/roles/{id}` | `role:write` | 内置角色**权限可改、code 不可改** |
| DELETE | `/roles/{id}` | `role:write` | 内置角色不可删；被用户引用时不可删 |

`UserView` 不含任何口令字段。

### 2.4 注册与解析（REG-01/02/03）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/registrations` | `registration:read` | 分页 |
| GET | `/registrations/{id}` | `registration:read` | 含 `diagnostics`、`operationCount`、`serverId` |
| POST | `/registrations/by-url` | `registration:create` | JSON：`{name, url, deptId?, pathSegment?}`。抓取在事务外进行 |
| POST | `/registrations/upload` | `registration:create` | **multipart**：`name`(必填)、`file`(必填)、`deptId?`、`pathSegment?` |
| POST | `/registrations/by-text` | `registration:create` | **form 参数 + 裸文本体**：`name`(必填)、`deptId?`、`pathSegment?`；`Content-Type` 可用 `text/plain`、`application/json`、`application/octet-stream`、`text/yaml`、`application/yaml` |
| GET | `/registrations/{id}/raw` | `registration:read` | 原始文档纯文本。**返回前重算 sha256 与登记值比对**，不一致直接拒绝（BR-2 双保险） |
| POST | `/registrations/{id}/reimport` | `registration:reimport` | 同 `by-text`，返回 diff 报告（含 `suspended` / `restored` 锚点列表） |
| POST | `/registrations/{id}/reimport-upload` | `registration:reimport` | 同 `upload`，返回 diff 报告 |

`by-text` 与 `upload` 用 form 参数而非 JSON 体，是因为请求体已经被文档本身占用了。

解析出 ERROR 级诊断时 `status=FAILED` 且**不生成 Server**（`serverId` 为 null）。
解析成功时 `status=READY`，按 BR-1 生成 1 个 Server（状态 `DRAFT`）与 N 个 Tool。

`by-url` 的抓取默认禁止内网地址（SSRF 防护）；本地开发可用
`MANAGER_PARSE_ALLOW_PRIVATE_NETWORKS=true` 放开，**生产必须关**。

### 2.5 Server 与 Tool（SVR-01/02/03）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/servers` | `server:read` | 分页 |
| GET | `/servers/{id}` | `server:read` | 生效值 + `overlayVersion` + `endpointPreview` |
| PUT | `/servers/{id}` | `server:write` | **覆盖式**：`{name?, title?, description?, pathSegment?, listTtlMs?}` |
| PUT | `/servers/{id}/upstream` | `server:write` | **整体替换**，见下 |
| GET | `/servers/{id}/auth-b` | `server:read` | 只回掩码（`maskedPreview`），绝不回明文 |
| PUT | `/servers/{id}/auth-b` | `auth:write` | 见 §1.4 第三种语义 |
| GET | `/servers/{id}/auth-d` | `server:read` | 回 `staticTokenCount` 而非令牌本身；`resourceMetadataUrl` 由后端派生 |
| PUT | `/servers/{id}/auth-d` | `auth:write` | **整体替换** |
| GET | `/servers/{id}/tools` | `server:read` | base 值与生效值并列返回，便于 UI 直接展示差异 |
| GET | `/servers/{id}/diff` | `server:read` | 原始 vs 生效差异（BR-2 要求 UI 必须提供）。不含任何凭据字段 |
| GET | `/servers/{id}/effective` | `server:read` | 完整生效模型预览 |
| GET | `/servers/{id}/bindings` | `server:read` | 各集群发布状态 |
| PUT | `/servers/{id}/tools/{toolId}/overlay` | `tool:write` | **覆盖式**：`{name?, description?, inputSchema?, enabled?, streaming?, streamFormat?}` |
| DELETE | `/servers/{id}/tools/{toolId}/overlay` | `tool:write` | 撤销全部覆盖，回到 `NONE` |
| POST | `/servers/{id}/tools/batch-toggle` | `tool:write` | `{toolIds:[], enabled:bool}` |

`PUT /servers/{id}/upstream` 请求体（整体替换，未传字段回落默认值）：

```json
{
  "baseUrls": ["https://api.example.com"],
  "lbStrategy": "ROUND_ROBIN",
  "connectTimeoutMs": 3000,
  "readTimeoutMs": 30000,
  "retries": 1,
  "retryOnStatus": [502, 503, 504],
  "cbFailureThreshold": 5,
  "cbOpenMs": 30000,
  "cbHalfOpenProbes": 2
}
```

默认值即上表所示。**没有 `weights` 字段**——WEIGHTED 当前不退化为加权，而是被 Executor
退回轮询（见架构说明 §10）。

tool 覆盖写入语义（重点，容易踩坑）：

| 传值 | 后果 |
| --- | --- |
| `name: ""` | 移除名称覆盖，回落基座名 |
| `inputSchema: {}` 或 JSON `null` | **移除** inputSchema 覆盖 |
| `inputSchema: <非对象>` | 400 `VALIDATION_FAILED` |
| `streaming: <非 null>` | **总是写覆盖**（即使值与基座相同） |
| `enabled` | **不是覆盖字段**，直接改实体列；值相同时不写 |

无论改了多少字段，只要调用了这个接口，`overlayVersion` **无条件 +1**。
因此空提交应当在客户端拦掉，否则覆盖版本会无意义地增长。

`method` / `path` / `anchor` 不可覆盖（ADR-0002）。

### 2.6 发布（PUB-01/02/04）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/servers/{serverId}/publish` | `publish:execute` | `{clusterId, note?}` |
| POST | `/servers/{serverId}/offline` | `publish:execute` | **query 参数** `clusterId` |
| POST | `/servers/{serverId}/rollback` | `publish:rollback` | **query 参数** `clusterId` + 体 `{version}` |
| GET | `/servers/{serverId}/publish-history` | `server:read` | 历史版本，**永不删除** |

发布前五项校验不通过时返回 409 `INVALID_STATE`，`details` 逐项写明缺什么：
协议版本、至少一个上游地址、至少一个启用 tool、生效名不重复、PATH 末段合法且唯一。

**回滚不是把版本号往回拨**，而是用历史快照的内容创建一个新版本。历史版本一条都不会被删除，
所以回滚本身也可以再回滚。

发布与下线的传播是**异步**的：Executor 在下一次轮询（默认 10s，上界约 30s）内感知。
`offline` 的响应 message 里会写明这一点。

### 2.7 集群与节点（CLUSTER-01/02）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/clusters` | `cluster:read` | 列表（非分页） |
| GET | `/clusters/{id}` | `cluster:read` | |
| POST | `/clusters` | `cluster:write` | `{name, type, entrypoint, pathPrefix?, ownerDeptId?, description?, enabled?, scopes?}`；`type` = `SHARED` \| `PRIVATE` |
| PUT | `/clusters/{id}` | `cluster:write` | 同上 |
| PUT | `/clusters/{id}/grants` | `cluster:grant` | **整体替换**：`{deptIds:[…]}` |
| POST | `/clusters/{id}/rotate-node-token` | `cluster:write` | 返回 `{"token": "…"}`。**明文只返回这一次**，库里只存 sha256；旧令牌立即失效 |
| GET | `/clusters/{id}/nodes` | `cluster:read` | 节点列表 |
| POST | `/clusters/nodes/{nodeId}/offline` | `cluster:write` | 下线节点。若进程还在跑，它会带着旧令牌重新注册回来 |

### 2.8 审计（MGM-05）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/audits` | `audit:read` | 分页，可选 `action` 过滤；默认 `size=50` |
| GET | `/audits/target/{targetType}/{targetId}` | `audit:read` | 按对象查（Server 详情页的「变更历史」用） |

30 种动作码定义在 `AuditAction`。审计记录含 `actorId` / `actorName` / `action` /
`targetType` / `targetId` / `deptId` / 结构化 `detail`（字段级 from/to）/ `traceId`。

### 2.9 权限点与内置角色

20 个权限点（`资源:动作`）：

```
user:read user:write role:read role:write dept:read dept:write
registration:read registration:create registration:reimport
server:read server:write tool:write auth:write
cluster:read cluster:write cluster:grant publish:execute publish:rollback
audit:read metrics:read
```

6 个内置角色：

| 角色码 | 权限范围 |
| --- | --- |
| `PLATFORM_ADMIN` | 全部 20 个 |
| `OPS` | cluster:read/write/grant、metrics:read、server:read、audit:read |
| `AUDITOR` | audit:read、server:read、registration:read、cluster:read、metrics:read |
| `DEPT_ADMIN` | user:read/write、dept:read、registration 全部、server:read/write、tool:write、auth:write、cluster:read、publish 全部 |
| `DEPT_DEVELOPER` | registration 全部、server:read/write、tool:write、auth:write、cluster:read、publish:execute |
| `READONLY` | registration:read、server:read、cluster:read |

内置角色的权限可改、code 不可改、不可删除、被引用时不可删除。
`/actuator/**`（除 health/info）需要 `metrics:read`。

---

## 3. 控制面内部通道（`/internal/v1`）

**鉴权**：`X-Executor-Token` 头，sha256 常量时间比较，明文不落库不落日志。

两级令牌：

- **集群令牌**（`executor_cluster.node_token_hash`）：身份含 `clusterId`，可拉该集群快照；
- **引导令牌**（`mcp.manager.executor.bootstrap-token`）：**仅用于首次注册**。拿它拉快照
  返回 403，`details.hint` 提示先调用 `POST /internal/v1/nodes/register`。

令牌归属集群与请求的 `clusterId` 必须一致，否则 403。

**响应不套 `ApiResponse` 信封**——这条通道的消费者是 Executor，不是人。

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| POST | `/internal/v1/nodes/register` | `{nodeKey, clusterName, host, port, version, protocolVersion}` | `{nodeId, clusterId, clusterName, endpointTemplate, snapshotRevision, heartbeatIntervalSeconds, protocolVersion}` |
| POST | `/internal/v1/nodes/heartbeat` | `{nodeKey, snapshotRevision, loadInfo}` | `{revision, changed}` |
| GET | `/internal/v1/clusters/{clusterId}/revision` | — | `{clusterKey, revision, etag}` |
| GET | `/internal/v1/clusters/{clusterId}/snapshot` | 可带 `If-None-Match` | 200 + `PublishedSnapshot` + `ETag` + `X-Mcp-Revision`；或 **304 无体** |

注册时 `protocolVersion` 必须是 `2026-07-28`，否则直接拒绝（决策 D1）。
节点未注册就发心跳会得到 409 `INVALID_STATE`，message 里给出注册端点。

`heartbeat.changed=true` 表示本节点快照落后，Executor 应**立即**触发一次拉取而不是等下个周期。

心跳与 `/revision` 都是 10s 级高频调用，因此响应**刻意只回标量**：心跳不回已发布端点清单，
`/revision` 不回 serverCount/toolCount，`/revision` 也不读 `publish_binding.snapshot`
（etag 由 `fingerprint` 投影列算出）。任何「顺带的便利字段」在这两个端点上都会变成
每节点每 10s 一次的固定装配成本。

`etag` 只由「集群名 + revision + 各绑定固化的 `pathSegment:bindingVersion:toolCount`」决定，
**不含生成时间**，因此多 Manager 实例与重启后都能给出一致结果，304 语义才成立。

---

## 4. 数据面 MCP 端点

### 4.1 传输形态

```
POST {集群入口}/{保留前缀}/{PATH 末段}
```

例：`http://localhost:9090/mcp/order`

- **只接受 POST**。GET 返回 405 + JSON-RPC 错误（而不是 404），让还在用旧传输的客户端
  拿到明确原因——平台无状态、无服务端推送，没有可打开的流。
- `Content-Type` 不限（`consumes = */*`），因为要容忍各种客户端。
- **无会话**：不签发、不接受、不依赖 `Mcp-Session-Id`。
- 网关**禁止粘性会话**，可按 `Mcp-Method` / `Mcp-Name` 头路由（SEP-2243），无需解析请求体。

### 4.2 请求头

| 头 | 必需 | 说明 |
| --- | --- | --- |
| `Mcp-Method` | 建议 | 方法名。**优先于请求体 `method`**；两者不一致时以头为准并记 WARN |
| `Mcp-Name` | 建议 | 目标 tool 名。优先于 `params.name` |
| `Mcp-Protocol-Version` | 可选 | 不声明则按 2026-07-28 处理；**声明了错误版本则拒绝** |
| `Authorization` | 视 Auth-D | `Bearer <令牌>`；`STATIC_BEARER` 模式下必需 |
| `Mcp-Session-Id` | **禁止** | 出现即判定为 legacy 形态，直接拒绝 |

### 4.3 方法

| 方法 | P0 状态 | 说明 |
| --- | --- | --- |
| `ping` | ✅ | 返回 `{}` |
| `server/discover` | ✅ | 能力发现，取代 legacy 的 `initialize`。返回 `capabilities`（`listChanged` 恒为 false）+ `ttlMs` |
| `tools/list` | ✅ | 返回 `tools[]` + `ttlMs`。**流式 tool 被剔除**；不分页，明确不回 `nextCursor` |
| `tools/call` | ✅ | 返回 MCP tool result |
| `resources/list`、`prompts/list` | ⚠️ 空目录 | P1（BR-7）。返回空数组 + `ttlMs` |
| `resources/read`、`prompts/get` | ⚠️ | P1 |
| `initialize`、`notifications/initialized` | ❌ 拒绝 | legacy 形态，SEP-2575 已移除 |

`tools/call` 示例：

```bash
curl -X POST http://localhost:9090/mcp/order \
     -H 'Content-Type: application/json' \
     -H 'Mcp-Protocol-Version: 2026-07-28' \
     -H 'Mcp-Method: tools/call' \
     -H 'Mcp-Name: get_orders_id' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
          "params":{"name":"get_orders_id","arguments":{"id":"A-1"}}}'
```

### 4.4 JSON-RPC 错误码

| 码 | 常量 | 触发场景 |
| --- | --- | --- |
| -32700 | `PARSE_ERROR` | 请求体不是合法 JSON |
| -32600 | `INVALID_REQUEST` | 信封非法（`jsonrpc` ≠ `"2.0"` 或 `method` 为空） |
| -32601 | `METHOD_NOT_FOUND` | 不支持的方法 |
| -32602 | `INVALID_PARAMS` | 参数缺失或类型错 |
| -32603 | `INTERNAL_ERROR` | 平台内部错误 |
| **-32022** | `UNSUPPORTED_PROTOCOL_VERSION` | **legacy 协议形态（决策 D1 / EXE-08）** |
| -32001 | `SERVER_NOT_FOUND` | PATH 末段未发布或已下线 |
| -32002 | `TOOL_NOT_FOUND` | tool 不存在、已停用，**或被标记为流式**（P1 未实现） |
| -32003 | `UPSTREAM_ERROR` | 上游超时 / 熔断打开 / 上游 5xx |
| -32004 | `UNAUTHORIZED` | 下行鉴权（Auth-D）失败，**或 Server 配了 OAUTH2 模式**（P1 未实现，HTTP 501） |

`-32022` 的 `error.data` 有固定形状，客户端可据此自动给出升级提示：

```json
{
  "supportedProtocolVersion": "2026-07-28",
  "detectedProtocolVersion": "2025-11-25",
  "reason": "…",
  "upgradeUrl": "https://modelcontextprotocol.io/specification/2026-07-28",
  "legacySupported": false
}
```

五种被识别的 legacy 形态见 [ADR-0001](adr/ADR-0001-modern-only-protocol.md#落地方式)。

Auth-D 失败一律回同一句「令牌无效」，不区分「没带」「格式错」「不在白名单」——
区分这些等于给攻击者提供枚举 oracle。

### 4.5 运维端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/executor/status` | 全量诊断：节点身份、快照 revision/etag/就绪状态、共享状态模式、熔断概况。排查「为什么这个端点不通」看这一个就够 |
| GET | `/healthz` | LB 摘除判定。快照未就绪时报 not ready |
| GET | `/actuator/prometheus` | Prometheus 指标 |

---

## 5. 版本策略

- 控制面公开 API 版本在路径里：`/api/v1`。
- 内部通道同样带版本：`/internal/v1`。它的契约由字段名固定，Executor 侧刻意**重新声明**
  DTO 而不共用 Manager 的类——两个模块不该互相依赖实现细节，字段漂移会在联调时立刻暴露。
- MCP 协议版本只有一个：`2026-07-28`，不做协商（ADR-0001）。
