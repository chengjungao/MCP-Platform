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
| **整体替换**：`PUT /servers/{id}/upstreams/{serviceId}`、`PUT /servers/{id}/auth-d`、`PUT /clusters/{id}/grants` | 未传字段回落**后端默认值** | 被重置 |

把覆盖式接口的生效值原样回显再提交，会凭空造出一条「与基座完全相同」的覆盖，
让 `overlayStatus` 从 `NONE` 变成 `ACTIVE`。详见 [ADR-0002](adr/ADR-0002-base-overlay-merge.md)。

`PUT /servers/{id}/auth-b`、`PUT /servers/{id}/upstreams/{serviceId}` 的 `authB`、以及
`PUT /servers/{id}/tools/{toolId}/overlay` 的 `authB` 是第三种：**非密钥字段整体替换，
密钥字段留空 = 保持不变**（因为平台只回掩码，前端不可能回传明文；若把掩码当明文存进去，
凭据就被覆盖成 `****xxxx` 了）。`type=NONE` 时清空全部密钥，并意味着「撤销这一层的覆盖、
回落到上一层」。

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
| PUT | `/servers/{id}/upstreams/{serviceId}` | `server:write` | **按 `serviceId` upsert**（多 REST 服务模型），见下 |
| DELETE | `/servers/{id}/upstreams/{serviceId}` | `server:write` | 删除该 REST 服务配置（连同其专属 Auth-B 一起删） |
| GET | `/servers/{id}/auth-b` | `server:read` | 只回掩码（`maskedPreview`），绝不回明文 |
| PUT | `/servers/{id}/auth-b` | `auth:write` | 见 §1.4 第三种语义 |
| GET | `/servers/{id}/auth-d` | `server:read` | 回 `staticTokenCount` 而非令牌本身；`resourceMetadataUrl` 由后端派生 |
| PUT | `/servers/{id}/auth-d` | `auth:write` | **整体替换** |
| GET | `/servers/{id}/tools` | `server:read` | base 值与生效值并列返回，便于 UI 直接展示差异 |
| GET | `/servers/{id}/diff` | `server:read` | 原始 vs 生效差异（BR-2 要求 UI 必须提供）。不含任何凭据字段 |
| GET | `/servers/{id}/effective` | `server:read` | 完整生效模型预览 |
| GET | `/servers/{id}/bindings` | `server:read` | 各集群发布状态 |
| PUT | `/servers/{id}/tools/{toolId}/overlay` | `tool:write` | **覆盖式**：`{name?, description?, inputSchema?, enabled?, streaming?, streamFormat?, authB?}` |
| DELETE | `/servers/{id}/tools/{toolId}/overlay` | `tool:write` | 撤销全部覆盖（含 Tool 级 Auth-B），回到 `NONE` |
| POST | `/servers/{id}/tools/batch-toggle` | `tool:write` | `{toolIds:[], enabled:bool}` |

**Auth-B 三级回落**（BR-4）：配置存在三个互斥维度，运行时取第一个非 `NONE` 的。

| 层级 | 存储维度 | 配置入口 |
| --- | --- | --- |
| Tool 级覆盖 | `auth_config.tool_id != 0` | `PUT /servers/{id}/tools/{toolId}/overlay` 的 `authB` |
| REST 服务级（主用） | `auth_config.tool_id = 0` 且 `upstream_service_id` 非空 | `PUT /servers/{id}/upstreams/{serviceId}` 的 `authB` |
| Server 级（历史形态） | `auth_config.tool_id = 0` 且 `upstream_service_id` 为空 | `PUT /servers/{id}/auth-b` |

`GET /servers/{id}/tools` 返回的每个 tool 带 `authB`（只回掩码），`type=NONE` 表示该 tool
未做覆盖、继承所属 REST 服务的凭据。

`PUT /servers/{id}/upstreams/{serviceId}` 请求体（**按 serviceId upsert**，不是整体替换：
路径里的 `serviceId` 为准；已存在的服务被本请求覆盖其连接参数）：

```json
{
  "serviceId": "order-service",
  "name": "订单服务",
  "baseUrls": ["https://api-a.example.com", "https://api-b.example.com"],
  "lbStrategy": "WEIGHTED",
  "weights": [7, 3],
  "connectTimeoutMs": 3000,
  "readTimeoutMs": 30000,
  "retries": 1,
  "retryOnStatus": [502, 503, 504],
  "cbFailureThreshold": 5,
  "cbOpenMs": 30000,
  "cbHalfOpenProbes": 2,
  "authB": { "type": "HTTP", "scheme": "bearer", "secret": "…" }
}
```

- `weights`：与 `baseUrls` **等长**，按顺序一一对应。
  - `lbStrategy=WEIGHTED` 时<strong>必填</strong>——缺失、长度不符、含负数、总和为 0 都会返回 400，
    错误里指出具体字段。控制面刻意不做「静默退回轮询」：那样运维会以为自己配的是 7:3 分流。
  - `0` 是合法权重，语义是「不要把流量打到这个实例」。
  - 非 WEIGHTED 时允许预置权重，切策略时不丢配置。
- `authB`：传了才写，不传表示「本次不修改该 REST 服务的上行鉴权」。
- 其余字段未传则回落默认值（即上例所示）。

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

#### 2.5.1 Resource 与 Prompt（SVR-05/06）

Swagger 里没有 Resource / Prompt 的概念，所以这两类对象**只能手动声明**。
它们与 tool 一样属于发布快照的一部分——写入后必须重新发布，Executor 才会加载。
权限不新增权限点：读沿用 `server:read`，写沿用 `tool:write`。

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/servers/{id}/resources` | `server:read` | 按 `sortOrder` 稳定排序 |
| POST | `/servers/{id}/resources` | `tool:write` | 新增，URI 在 Server 内唯一 |
| PUT | `/servers/{id}/resources/{resourceId}` | `tool:write` | **整份替换**（不是覆盖层语义） |
| DELETE | `/servers/{id}/resources/{resourceId}` | `tool:write` | |
| GET | `/servers/{id}/prompts` | `server:read` | 按 `sortOrder` 稳定排序 |
| POST | `/servers/{id}/prompts` | `tool:write` | 新增，prompt 名在 Server 内唯一 |
| PUT | `/servers/{id}/prompts/{promptId}` | `tool:write` | **整份替换** |
| DELETE | `/servers/{id}/prompts/{promptId}` | `tool:write` | |

**Resource**：`content`（静态内容）与 `toolId`（映射一个 tool）**必须二选一**，两个都不给或都给 → 400。

```json
{
  "uri": "mcp://crm-order/schema",
  "name": "订单契约",
  "description": "下单接口的字段说明",
  "mimeType": "application/json",
  "content": "{\"orderId\":\"string\"}",
  "ttlMs": 60000
}
```

- `uri` 必须带 scheme（`^[a-zA-Z][a-zA-Z0-9+.\-]*:[^\s]*$`），否则 400。规则由 `GET /meta` 的
  `resourceUriPattern` 下发，前端不抄一份。
- 映射形态把 `content` 换成 `toolId`。`resources/read` 时执行
  `toolCallService.call(server, tool, null)` —— **以无参数方式调用**，所以映射带路径参数或需要
  请求体的 tool 必然在读取时失败（控制台的下拉框会把这些 tool 标成不可选）。
- `content` 上限 64KB。这个限制不是为了省库，而是因为内容会固化进**每个节点**拉取的快照。
- 映射的 tool 若被删除或停用，该 Resource 在**组装快照时被跳过**（不阻断发布），
  客户端会看不到它。控制台在 Resource 列表与生效模型预览里都会显式标出这种情况。

**Prompt**：`template` 里的占位符与 `arguments` 必须**双向一致**。

```json
{
  "name": "order.review",
  "title": "订单风险复盘",
  "description": "把订单详情交给模型做一次风险判断",
  "template": "订单号：{{orderId}}\n金额：{{amount}}",
  "arguments": [
    { "name": "orderId", "description": "订单号", "required": true },
    { "name": "amount", "description": "金额（元）", "required": true }
  ],
  "ttlMs": 300000
}
```

- 占位符写作 `{{ argName }}`，允许字母、数字、下划线、点、连字符（允许内部空白）。
  校验规则与运行时渲染共用 `com.mcpbridge.common.util.PromptTemplate`，两边不会跑偏。
- **未声明就写进模板的占位符会被拒绝**（400）。运行时的语义是「未提供的替换成空串」，
  拼错一个字母就等于在线上提示词里留了个沉默的空洞——这条校验把它挪到保存时。
- **声明了却没在模板里用到的参数同样被拒绝**（400）：客户端会提示用户填一个对结果毫无影响的值。
- `arguments` 名最长 64、不能重复；`ttlMs` 为空时用 Server 的 `listTtlMs`。

`GET /servers/{id}/effective` 返回的生效模型现在除 `tools` 外还含 `resources` 与 `prompts`
（`ResourceSnapshot` / `PromptSnapshot`）。快照里存的是 Prompt **模板原文**，参数由客户端在
`prompts/get` 时传入、Executor 侧渲染。

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
| POST | `/clusters` | `cluster:write` | `{name, type, entrypoint, pathPrefix?, ownerDeptId?, description?, enabled?, quota?}`；`type` = `SHARED` \| `PRIVATE` |
| PUT | `/clusters/{id}` | `cluster:write` | 同上 |
| PUT | `/clusters/{id}/grants` | `cluster:grant` | **整体替换**：`{deptIds:[…]}` |
| POST | `/clusters/{id}/rotate-node-token` | `cluster:write` | 返回 `{"token": "…"}`。**明文只返回这一次**，库里只存 sha256；旧令牌立即失效 |
| GET | `/clusters/{id}/nodes` | `cluster:read` | 节点列表 |
| POST | `/clusters/nodes/{nodeId}/offline` | `cluster:write` | 下线节点。若进程还在跑，它会带着旧令牌重新注册回来 |

**发布配额 `quota`**（PUB-01，V9 起由 `scopes` 改名；`scopes` 仍作为 `@JsonAlias` 接受）：

```json
{"maxServers": 50, "maxToolsPerServer": 200, "maxCatalogItemsPerServer": 100}
```

| 维度 | 含义 | 校验时机 |
| --- | --- | --- |
| `maxServers` | 本集群最多同时发布多少个 Server | 发布时 |
| `maxToolsPerServer` | 单个 Server 最多多少个**启用**的 tool | 发布时 |
| `maxCatalogItemsPerServer` | 单个 Server 最多多少个 Resource + Prompt | 发布时 |

- 省略某个键 = 该维度不限；`{}` 或 `null` = 整体不限（响应里回 `null`）。
- **`0` 是合法值**，表示该维度一个都不允许（可用于临时冻结集群）；负数、小数、非数字回 `400 VALIDATION_FAILED`
  且 `details.field` 指出具体维度。
- 超出配额时发布返回 `409 INVALID_STATE`，`details` 逐项给出 `quota.<维度>: "配额上限 N，当前 M（…）"`。
- 已发布在本集群的 Server **重新发布不占新名额**。
- **改配额不推进 `cluster.revision`**（配额不进快照，Executor 无需重载）。

### 2.8 审计（MGM-05）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/audits` | `audit:read` | 分页，可选 `action` 过滤；默认 `size=50` |
| GET | `/audits/target/{targetType}/{targetId}` | `audit:read` | 按对象查（Server 详情页的「变更历史」用） |
| GET | `/audits/export` | `audit:read` | 导出 CSV，可选 `action` 过滤；**不走信封**，直接回 `text/csv;charset=UTF-8` |

38 种动作码定义在 `AuditAction`。审计记录含 `actorId` / `actorName` / `action` /
`targetType` / `targetId` / `deptId` / 结构化 `detail`（字段级 from/to）/ `traceId`。

**append-only（V8 起为 DB 级强制）**：`audit_log` 上装了 `BEFORE UPDATE/DELETE` 触发器并
`REVOKE UPDATE, DELETE, TRUNCATE`。应用层的"仓储不暴露 deleteBy*"只是自觉，触发器才是兜底——
属主账号绕过权限，但绕不过触发器。合法清理必须走 `ALTER TABLE ... DISABLE TRIGGER` 这类显式 DDL。

**导出契约**（`GET /audits/export`）：

| 项 | 约定 |
| --- | --- |
| 内容 | RFC 4180 CSV，UTF-8 **带 BOM**（不加 BOM 时 Excel 在中文 Windows 上按 GBK 解码，全乱码） |
| 列 | `id, createdAt, actorId, actorName, action, targetType, targetId, deptId, clientIp, traceId, detail` |
| 时间格式 | ISO-8601 **UTC**（审计文件常被跨系统比对，本地时间字符串无法可靠解析） |
| 注入防护 | 以 `= + - @` 或制表符/回车开头的单元格加 `'` 前缀（Excel 公式注入），公式前缀对使用者不可见 |
| 隔离 | 与列表页共用同一段部门隔离逻辑；`action` 之外的筛选（部门）不接受前端传参 |
| 行数上限 | `mcp.manager.audit.export-max-rows`（默认 50000）。超出返回 `409 INVALID_STATE` + `details.hint`，**不静默截断**——被截断的审计文件看起来是完整的 |
| 留痕 | 导出本身写一条 `audit.export`（detail 含 `action` / `rows` / `filename`）。先取内容再记录，因此本次导出不含自己这一条 |
| 响应头 | `Content-Disposition: attachment` + `Cache-Control: no-store` |

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
| `resources/list` | ✅ | 返回 `resources[]` + `ttlMs`。空清单时 `server/discover` 不声明该能力 |
| `resources/read` | ✅ | 静态内容直接返回；映射 tool 的按 **无参数** 调用上游后取首个 text 作为内容 |
| `prompts/list` | ✅ | 返回 `prompts[]`（含参数声明）+ `ttlMs`。空清单时 `server/discover` 不声明该能力 |
| `prompts/get` | ✅ | 按 `params.name` 取模板，用 `params.arguments` 渲染；返回 `messages[]`（role=user）。**必填参数缺失 → 400 `INVALID_PARAMS`**，未提供的非必填占位符替换成空串 |
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
| GET | `/actuator/prometheus` | Prometheus 指标（**无应用层鉴权**，数据面不引 Spring Security，靠网络隔离兜底） |

#### 业务指标清单（OPS-01）

自动的 JVM / HTTP 指标之外，`ExecutorMetrics` 额外暴露以下指标。**指标名不带 `_total` / `_seconds`
后缀**——Prometheus 客户端会按类型自动补全，手写后缀会变成 `…_total_total`。

| 指标名 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `mcp_executor_tool_calls` | Counter | `path_segment`, `tool`, `outcome` | tool 调用次数。`outcome` ∈ `success` / `upstream_error`（上游回错，`isError=true`）/ `platform_error`（平台侧异常） |
| `mcp_executor_tool_call_duration` | Timer | `path_segment`, `tool`, `outcome` | 端到端耗时（含凭据解析、重试、响应装配） |
| `mcp_executor_tool_response_truncated` | Counter | `path_segment`, `tool` | 响应体超限被截断的次数。持续增长说明有 tool 的返回体需要收窄 |
| `mcp_executor_upstream_requests` | Counter | `service`, `status_class` | 上游 HTTP 尝试次数。`status_class` ∈ `2xx` / `4xx` / `5xx` / `none`（传输层失败） |
| `mcp_executor_upstream_duration` | Timer | `service` | 上游调用耗时（含重试） |
| `mcp_executor_upstream_failures` | Counter | `service`, `kind` | 上游失败。`kind` ∈ `server_error`（5xx）/ `client_error`（4xx）/ `transport`（连不上、超时、读中断） |
| `mcp_executor_upstream_retries` | Counter | `service` | 重试次数。与 `upstream_requests` 的比值就是重试率 |
| `mcp_executor_lb_weighted_fallback` | Counter | `path_segment` | WEIGHTED 因权重与 baseUrls 数量不匹配而退回轮询的次数。**非零即配置有问题**，应告警 |
| `mcp_executor_circuit_state` | Gauge（MultiGauge） | `service` | 熔断状态。`0`=CLOSED / `1`=OPEN / `2`=HALF_OPEN。用显式映射而不是 `ordinal()`，避免枚举顺序变化静默改语义。用 `overwrite=true` 注册，已下线 Server 的 key 会被移除 |
| `mcp_executor_circuit_open` | Counter | `service` | 熔断**进入 OPEN** 的次数。只在「非 OPEN → OPEN」这一跳计数，持续故障不会把告警淹没 |
| `mcp_executor_circuit_rejections` | Counter | `service` | 因熔断打开而直接拒绝的调用数 |
| `mcp_executor_snapshot_revision` | Gauge | 无 | 本节点当前加载的快照 revision；0 = 还没同步过 |
| `mcp_executor_snapshot_ready` | Gauge | 无 | 快照是否就绪（1/0）。与 `/healthz` 同一判据（未就绪时端点返回 503） |
| `mcp_executor_snapshot_servers` / `_tools` | Gauge | 无 | 快照内的 Server 数 / tool 总数 |
| `mcp_executor_snapshot_applied_revisions` | Gauge | 无 | 本进程启动以来实际应用的快照次数（用于发现「轮询在跑但 revision 一直没动」） |

空标签值统一写成 `-`（`safe()`）：空串在 Prometheus 里合法，但排查时分不清「真的是空」与「忘了打标签」。

两个实现约定值得记下来：

- **状态类指标用 `@Scheduled` 轮询刷新**（`mcp.executor.metrics-refresh-interval-ms`，默认 15s），
  热路径只用 Counter / Timer。原因是 Gauge 读的是可变状态，而 `MultiGauge` 的动态 key 必须整体重新注册
  才能移除消失的 key。
- **刻意不打请求参数标签**（如 `orderId`、`trace_id`）。它们的势（cardinality）是无界的，
  一个标签就能把 Prometheus 打爆。要看单次调用请用 `traceId` 去审计/日志里查，不要指望指标。

#### 出站 Trace Context（OPS-02）

平台**出站**调用 REST 上游时注入 W3C `traceparent`：

```
traceparent: 00-<32hex trace-id>-<16hex span-id>-<2hex flags>
```

| 规则 | 说明 |
| --- | --- |
| 入站解析 | 优先取请求头 `traceparent`，其次取 JSON-RPC `params._meta.traceparent` |
| 兜底 | 合法 traceparent → 延续其 trace-id；裸 32 位 hex → 当 trace-id 沿用；其它 → 新建。**入站永不失败**（不能因为调用方给了个烂头就拒绝整个请求） |
| span 派生 | **trace-id 跨跳稳定，span-id 每跳换新**。派生发生在 `ToolCallService`，`UpstreamInvoker` 只负责原样注入 |
| 出站覆盖 | 注入前先 `remove` 再 `set`，防止调用方的参数映射或自定义头把平台生成的 `traceparent` 顶掉 |
| 响应回写 | 无条件回写 `X-Trace-Id`，值是**实际下发给上游的那个 trace-id**，而不是调用方传进来的原值 |
| 非法输入 | 非 `00` 版本（如 `01-…`）、全零、长度错、非 hex 一律不沿用；平台自己产出的头绝不会非法（非法头会被上游规范实现整条丢弃，表现为"传了但没收到"，没有任何报错） |

---

## 5. 版本策略

- 控制面公开 API 版本在路径里：`/api/v1`。
- 内部通道同样带版本：`/internal/v1`。它的契约由字段名固定，Executor 侧刻意**重新声明**
  DTO 而不共用 Manager 的类——两个模块不该互相依赖实现细节，字段漂移会在联调时立刻暴露。
- MCP 协议版本只有一个：`2026-07-28`，不做协商（ADR-0001）。
