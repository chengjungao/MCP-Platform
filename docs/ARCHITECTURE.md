# 架构说明

本文描述 **实际实现** 的架构，不是目标愿景。凡属 P1/P2 尚未落地的部分，都在
[§10 已知取舍与缺口](#10-已知取舍与缺口) 里明确列出，不在正文里含糊带过。

需求出处以 PRD 编号标注（`docs/PRD/`）；架构决策的理由见 `docs/adr/`。

---

## 1. 一句话架构

**控制面（mcp-manager）把 Swagger 文档编译成版本化的发布快照；数据面（mcp-executor）
把快照作为唯一配置来源，无状态地对外提供 MCP 2026-07-28 端点并透传到REST 服务。**

两个平面之间只有一条通道：Executor 用节点令牌轮询 Manager 的内部 API 拉快照。
Executor **不直连控制面数据库**（PRD §5.4 明确要求），因此私有集群可以部署在与控制面
网络隔离的环境里，只要它能出站访问 Manager API。

```
                    ┌──────────────── 控制面 ────────────────┐
  Vue3 控制台 ──►   │  mcp-manager (Spring MVC, 无状态 JWT)  │
                    │  注册解析 / base⊕overlay / 集群 / 发布  │
                    └───────┬───────────────────┬────────────┘
                            │ JPA               │ /internal/v1/**（节点令牌）
                        PostgreSQL          快照拉取 + 心跳
                            │                   │
                            │            ┌──────┴────────────────────────┐
                            │            │  mcp-executor × N (WebFlux)   │
   MCP Client ──────────────┼──────────► │  协议守卫 / Auth-D / 参数映射  │
   2026-07-28               │            │  Auth-B / 负载均衡 / 熔断      │
                            │            └──────┬──────────────┬─────────┘
                            │                   │              │
                        ┌───┴────┐          REST 服务    Redis (Redisson)
                        │ 审计/  │                          令牌缓存 + 刷新锁
                        │ 快照库 │                          + 失效广播
                        └────────┘
```

**数据流向是单向的**：配置从 Manager 流向 Executor，调用流量从 Client 经 Executor 流向
REST 服务。Executor 从不回写业务配置，只上报节点注册与心跳。

---

## 2. 模块与依赖

| 模块 | 技术栈 | 职责 |
| --- | --- | --- |
| `mcp-common` | 纯 Java + Jackson | 协议常量、JSON-RPC 信封与错误码、快照模型、错误码枚举、PATH/tool 命名规则、哈希与日志脱敏 |
| `mcp-manager` | Spring MVC + JPA/Hibernate 6 + PostgreSQL + Flyway + Spring Security 6 + jjwt + swagger-parser | 控制面全部能力 |
| `mcp-executor` | Spring WebFlux (Reactor Netty) + Redisson + Micrometer | 数据面全部能力 |
| `mcp-manager-ui` | Vue 3 + TS + Vite + Pinia + Element Plus | 管理控制台 |

依赖方向严格单向：`manager → common`、`executor → common`，两个业务模块**互不依赖**。

把协议常量与快照模型放进 `mcp-common` 而不是各自定义一份，是为了让「Manager 写出的快照」与
「Executor 读入的快照」在编译期就是同一个类型。如果两边各写一份 DTO，字段漂移只会在运行时
以反序列化失败的形式暴露——而快照格式漂移的后果是**已发布端点集体不可用**。

`mcp-manager-ui` 不参与 Maven 构建（不是 Maven 模块），产物 `dist/` 由 nginx 镜像发布。

---

## 3. 核心数据模型

领域模型的完整 DDL 在 `mcp-manager/src/main/resources/db/migration/V1__init_schema.sql`。
关键的几张表与它们承载的约束：

| 表 | 承载的关键约束 |
| --- | --- |
| `api_registration` | 原始文档 `raw_doc` + `raw_doc_sha256`。读取原文时**重算哈希并比对**，不一致直接拒绝（BR-2 双保险：既没有写路径，也不信任存储未被绕过应用改过） |
| `mcp_server` | `base_model`(jsonb) / `overlay`(jsonb) / 生效列；`path_segment` 唯一约束（BR-3） |
| `mcp_tool` | `anchor` = `METHOD path`；`base_*` 列 + `overlay`(jsonb) + `overlay_status` |
| `mcp_resource` | SVR-05。`uri` 在 Server 内唯一；`static_content` 与 `tool_id` 二选一（`tool_id` 外键 `ON DELETE SET NULL`） |
| `mcp_prompt` | SVR-06。`name` 在 Server 内唯一；`arguments`(jsonb) 与 `template` 占位符必须双向一致 |
| `auth_config` | Auth-B 凭据密文（AES-256-GCM）+ Auth-D 令牌 sha256 集合。三维度由表达式唯一索引 `uk_auth_config_scope (server_id, tool_id, COALESCE(upstream_service_id,''))` 约束 |
| `executor_cluster` | `type`(SHARED/PRIVATE)、`entrypoint`、`path_prefix`、`node_token_hash`、`revision`、授权部门集合、`quota`(jsonb) |
| `executor_node` | 节点注册信息、`status`、最后心跳 |
| `publish_binding` | `(server, cluster)` 上的 `binding_version` + `snapshot`(jsonb) + `fingerprint` + `state` + `current` 标记；历史版本永不删除 |
| `audit_log` | 38 种动作码 + 目标类型/ID + 结构化 detail + traceId。**DB 级 append-only**：`BEFORE UPDATE/DELETE` 触发器 + `REVOKE UPDATE, DELETE, TRUNCATE`（V8） |

**`publish_binding` 是控制面与数据面之间唯一的契约载体。** 发布不是「把 Server 标记为已发布」，
而是「把此刻的生效模型序列化进 binding 的 snapshot 列，并把 cluster.revision 加一」。
因此发布之后无论 base 还是 overlay 怎么改，已发布的端点行为都不变，直到下一次显式发布。
这也是回滚能成立的前提：回滚 = 用历史 binding 的快照内容创建一个新版本（历史链条完整可审计）。

---

## 4. 关键流程

### 4.1 注册与解析

```
上传/URL/粘贴 → 原始文档留存（sha256）→ swagger-parser 解析
             → ParsedApi（operations + diagnostics）
             → 1 注册 = 1 Server（BR-1），status=READY 才建 Server
             → 每个 operation = 1 Tool，anchor = METHOD path
             → inputSchema 由 parameters + requestBody 推导（JSON Schema 2020-12）
             → PATH 末段：显式指定则校验唯一性，否则由标题派生
             → 新 Server 状态 DRAFT
```

解析出 ERROR 级诊断时**不生成 Server**，只留注册记录与诊断列表——一个不完整的 Server
比一个明确的失败更难处理。

文档抓取（`by-url`）在**事务外**进行，避免慢站点长期占用数据库连接。同一理由，
`RegistrationService` 注入了自身的懒加载代理：重新解析需要「事务外抓取 → 事务内比对入库」，
同类内部直调会绕过 Spring 代理导致 `@Transactional` 失效。

### 4.2 发布

`PublishService.requirePublishable` 做五项前置校验，任一不通过即 409 `INVALID_STATE`，
`details` 里逐项写明缺什么：

1. 协议版本必须是 2026-07-28；
2. 至少一个服务地址；
3. 至少一个启用的 tool；
4. 生效 tool 名不重复（生效名不是数据库列，只能在这里校验，见 ADR-0002 后果）；
5. PATH 末段合法（`^[a-z0-9]([a-z0-9-_]{0,62}[a-z0-9])?$`）且未被多个 Server 占用。

通过后：组装 `ServerSnapshot` → 写入新的 `publish_binding`（version+1，旧 binding 的
`current` 置 false 但**不删除**，同时固化 `fingerprint = pathSegment:bindingVersion:toolCount`）
→ `cluster.revision++` → 记审计。

`offline` 只把 binding 状态改掉，Executor 在下一次轮询（≤ 轮询间隔）内移除该端点。
没有主动推送——数据面无会话、无长连接，推送会引入一条必须保活的状态通道，与无状态目标冲突。

### 4.3 快照同步（两段式轮询）

```
每 10s：GET /internal/v1/clusters/{id}/revision     ← 三个标量，不读 jsonb
   revision/etag 变了？
      是 → GET /internal/v1/clusters/{id}/snapshot   ← 带 If-None-Match
              200 → 原子替换本地快照
              304 → 什么都不做
      否 → 什么都不做
```

**这两个端点都是「每节点每 10s」的频次，所以它们只回标量。** 心跳只回 `{revision, changed}`，
`/revision` 只回 `{clusterKey, revision, etag}`。任何「顺带的便利字段」（已发布端点清单、
serverCount/toolCount）在这里都不是免费的：它们要么触发一次 jsonb 全量装配，要么
在响应体里增加一份无人消费的负载，最终变成整个集群的固定背景成本税。

etag 由「集群名 + revision + 各绑定固化的 `pathSegment:bindingVersion:toolCount`」计算，
**不含生成时间**。这一点是 304 语义成立的前提：如果 etag 里掺了时间戳，多 Manager 实例
或 Manager 重启后每次都会 miss，两段式轮询就退化成每 10 秒拉一次全量——一个 300 接口的
集群就是每节点每分钟几十 MB 的无谓流量。

指纹之所以单独成列（`publish_binding.fingerprint`，V6）而不是从 `snapshot` 现算，是为了让
`/revision` 完全不碰 jsonb：读 `snapshot` 会让 PostgreSQL 把整份快照从 TOAST 表里 detoast
出来，代价与「只想要一个短字符串」不成比例。指纹在发布/回滚写入时固化，
`SnapshotAssembler.fingerprint` 与 V6 的回填 SQL 必须逐字一致——
两边一旦分叉，同一份快照会算出两个 etag，304 永远不命中，且**没有任何报错**。
这条契约由 `SnapshotAssemblerTest` 覆盖。

**失败语义：Manager 不可达时只记 WARN 并保留上一份快照，绝不清空。**
已发布的端点必须继续可用，最坏情况是新发布的内容延迟生效。启动时若一次都没拉到过快照，
`/healthz` 报 not ready，`/executor/status` 给出原因。

### 4.4 一次 tools/call 的完整路径

```
POST /mcp/{segment}
  │
  ├─ 1. 协议守卫（ProtocolGuard.requireModern）        → legacy 形态 -32022
  ├─ 2. JSON-RPC 信封校验                              → -32700 / -32600
  ├─ 3. 按 PATH 末段查本地快照                          → -32001 SERVER_NOT_FOUND
  ├─ 4. 下行鉴权 Auth-D（DownstreamAuthenticator）      → -32004 UNAUTHORIZED
  ├─ 5. 方法分派（Mcp-Method 头优先于请求体 method）     → -32601 METHOD_NOT_FOUND
  ├─ 6. 按 Mcp-Name / params.name 定位 tool            → -32002 TOOL_NOT_FOUND
  ├─ 7. 参数映射：JSON Schema 入参 → query/path/header/body（按 parameterIn）
  ├─ 8. 上行凭据注入 Auth-B（UpstreamCredentialProvider）
  ├─ 9. 负载均衡选址 + 熔断检查 + 重试（UpstreamInvoker）→ -32003 UPSTREAM_ERROR
  └─ 10. 结果封装为 MCP tool result（isError + content）
```

第 1 步必须在任何业务逻辑之前跑完——这是 ADR-0001 的工程后果。
第 7 步的映射依据是解析阶段记录的 `parameterIn`（每个参数来自 query / path / header / body），
它随快照下发，Executor 不需要重新读文档。

### 4.5 Resource 与 Prompt 的装配（SVR-05/06）

Swagger 里没有这两个概念，所以它们是**纯手工声明的对外能力**，但走的是与 tool 完全相同的
「控制面写入 → 发布快照 → 节点加载」链路。两个只能在装配期做、运行时做不了的决定：

1. **Resource 的 tool 映射要在装配时解析成「生效名」。** `mcp_resource.tool_id` 是外键，
   而 tool 的名字可以被覆盖改掉（BR-2）。快照里存的必须是**生效名**，Executor 才知道该调谁。
   同一原因，映射的 tool 若已被删除或被停用，这条 Resource 在装配时**被跳过**而不是抛错：
   一份坏配置不该让整个发布失败，但也不能静默——控制台列表与生效模型预览都会标出它。
   另外，`resources/read` 走的是 `toolCallService.call(server, tool, null)`，
   **不带任何参数**，所以「映射的 tool 必须有路径参数」这种配置在读取时才炸，控制面在选型时就把它排除掉。

2. **Prompt 的模板与参数声明必须在保存期就对齐。** 占位符 `{{argName}}` 的规则由
   `com.mcpbridge.common.util.PromptTemplate` 承载，放在 `mcp-common` 而不是各写一份：
   **控制面用它做配置期校验，Executor 用它做运行时渲染**。两处若各写一套正则，
   迟早出现「控制面认为合法、数据面按另一套规则渲染」的错位。
   校验是双向的——未声明的占位符和未被使用的参数都拒绝。运行时的语义是「未提供的占位符替换成空串」，
   那是兜底，不该是常态：拼错一个字母会变成线上提示词里一个沉默的空洞，比一次 400 难查得多。

`ResourcePromptService` 需要权限校验，而 `ServerService` 需要把 Resource/Prompt 装配进目录——
两者直接互相依赖会成环。解法是把 `requireManage` / `requireRead` 下沉到 `ServerAccessGuard`
（只依赖仓储与部门树，没有反向依赖），而不是用 `@Lazy` 把环盖住：掩盖依赖环等于把启动顺序
变成一个隐式契约。

### 4.6 发布配额（PUB-01）

`executor_cluster.quota`（jsonb，V9 由 `scopes` 改名）存三个容量上限：

```json
{"maxServers": 50, "maxToolsPerServer": 200, "maxCatalogItemsPerServer": 100}
```

缺省的键表示该维度不限；整列为 null 是默认状态（整体不限）。**配额在发布时校验，只在发布时校验**：

- 校验点是 `PublishService.requirePublishable` 的最后一站，与协议版本、baseUrls、tool 冲突等检查合并成
  一次 `409 INVALID_STATE`，`details` 里逐项给出 `quota.<维度>` 的"上限 N，当前 M"。
- 判定规则本身是 `ClusterQuota.violations(...)`——**纯函数，四个数字进、问题清单出**。
  这类"等于上限到底算不算超"的逻辑埋进 Service 就只能靠集成测试碰运气，抽出来才能穷举单测。
- **已发布在本集群的 Server 重新发布不占用新名额**，否则配额用满时连自己都发布不了。
- **配额不进 `ServerSnapshot`**，因此改配额不推进 `cluster.revision`：Executor 没必要为此重载快照。
  只有入口地址、PATH 前缀、名称这类会改变已发布端点形状的字段才推进版本号
  （`ClusterService.update` 里把"审计口径的 changes"与"revision 口径的 endpointAffecting"分开，
  配额与描述只留痕不推进）。

**为什么是配额而不是限流**：两者管的事情不同，不该混在一个字段里。

| | 发布配额（已实现） | 运行时限流（未实现） |
| --- | --- | --- |
| 管什么 | 集群能装多少东西（容量） | 每秒能打多少请求（速率） |
| 何时校验 | 发布时，低频、可回滚 | 每次请求，热路径 |
| 拒绝代价 | 一次 409，改完配置再发 | 一次线上调用失败，客户端/模型要处理 |
| 需要什么 | 一段纯校验逻辑 | 令牌桶（本地 or 分布式）+ 快照新字段 + 协议级拒绝语义 + 指标 |

后者的"协议级拒绝语义"是关键难点：MCP 侧要决定是回 JSON-RPC error 还是 `isError` 工具结果，
两者对模型行为的影响不同；还要决定限流计数放在节点本地（集群内不共享、总量约为 N×limit）还是
放共享状态（引入一次 redis 往返，热路径上不可忽略）。这是一个需要独立设计的 P1 项，
不是"给字段加个读取方"能顺手带出来的。当前平台明确把边界划在"鉴权注入 + 超时/重试/熔断 + 容量配额"。

---

## 5. 双跳鉴权（BR-4）

两跳的密钥、流程、生命周期完全隔离，**不能混为一谈**。

| 跳 | 方向 | 模式 | P0 状态 |
| --- | --- | --- | --- |
| **Auth-D** | MCP Client → Executor | `NONE` / `STATIC_BEARER` / `OAUTH2` | 前两种已实现；OAUTH2 **显式拒绝**（501 + `-32004` + 提示改用 STATIC_BEARER） |
| **Auth-B** | Executor → REST 服务 | `NONE` / `API_KEY`(header/query) / `HTTP`(bearer/basic) / `OAUTH2_CLIENT_CREDENTIALS` / `CUSTOM_HEADER` | 五种全部实现，**三级回落**见下 |

### 5.1 Auth-B 的三级回落（BR-4）

一份 REST 服务里往往只有个别接口用不同的凭据（例如大部分走网关令牌、少数走专属 API Key）。
为此 Auth-B 分三个维度存储，运行时按优先级取第一个非 `NONE` 的：

```
Tool.authBOverride  >  UpstreamEntry.authB  >  Server.authB
（auth_config.tool_id≠0）（tool_id=0, serviceId≠空）（tool_id=0, serviceId=空）
```

三个维度由 `(tool_id, upstream_service_id)` **互斥且穷尽**地划分——这条不变量是硬约束，
不能只判 `tool_id`：REST 服务级也是 `tool_id = 0`，混判会让「同一 Server 下 ≥2 个 REST 服务
各配了 Auth-B」按 `(serverId, 0)` 查出多行，把发布链路打挂（详见 `AuthConfigScopeTest`）。

运行时的回落逻辑在 Executor 的 `UpstreamCredentialProvider.effectiveAuthB`，
解密与掩码逻辑在控制面三处共用同一段代码（`AuthConfigService.persistAuthB`），
差别只在配置行归属哪个维度。

三条不可让步的安全约束：

1. **Auth-B 密钥永不下发给 MCP Client。** 解密后的凭据只出现在「Executor 以节点令牌拉快照」
   的内部通道上，绝不进入下行响应、日志或 tool 调用结果。
2. **Auth-B 明文不回显。** 控制面 API 只返回掩码（`maskedPreview`），UI 上「已保存」与
   「保存了什么」是两个独立事实。因此保存 Auth-B 时空密钥 = 保持不变，而不是清空。
3. **Auth-D 令牌只存 sha256，比对用常量时间**，防止按字节短路造成的时序侧信道。
   鉴权失败一律回同一句「令牌无效」，不区分「没带」「格式错」「不在白名单」——
   区分这些等于给攻击者提供枚举 oracle。

OAUTH2 模式的 Auth-D 选择「显式拒绝」而非「假装支持」：半实现的鉴权比没有鉴权更危险，
因为它会让运维误以为端点已经受保护。

Auth-B 凭据用 AES-256-GCM 字段级加密（随机 12 字节 IV，输出 `base64(iv || ciphertext || tag)`），
主密钥由配置串经 SHA-256 派生。**`MANAGER_CRYPTO_KEY` 一旦上线不可再换**——换了已存密文就解不开了。

---

## 6. 状态边界：什么共享，什么绝不共享

Executor 是无状态可丢的（任意节点服务任意请求，网关禁止粘性会话）。但有三类状态**必须**
跨节点一致，否则会出现「同一个客户端在不同节点上表现不同」这种最难排查的问题：

| 状态 | 为什么必须共享 | Redisson 承载形式 |
| --- | --- | --- |
| 上游 OAuth2 令牌缓存 | 每节点各换一次会打爆上游令牌端点，也会让令牌提前失效 | 带 TTL 的 RBucket |
| 令牌刷新锁 | 过期瞬间 N 个节点同时收到请求，只应有 1 个去刷新（缓存击穿） | RLock，等不到锁走 fallback 而不是抛异常 |
| 令牌失效广播 | 上游返回 401 时所有节点必须立即丢弃缓存，否则会有节点继续用废令牌重试、把失败放大 | RTopic 发布/订阅 |

**绝不共享的两类：**

- **路由信息 / 快照。** 每个节点各自持有完整本地快照，彼此不通信。Redis 不承载路由，
  所以 **Redis 挂掉时端点仍可服务**，只是退化为「每节点各自换取令牌」。这个降级是显式的：
  `SharedState.isShared()` 返回 false，`mode()` 写进 `/healthz`、`/executor/status` 与启动日志。
  生产实现是 `RedissonSharedState`，退化实现是 `InMemorySharedState`。
- **熔断状态。** 熔断度量的是「**本节点**到上游」这条链路的健康度。把它共享出去，会让一个
  节点的网络抖动变成整个集群拒绝调用——本地网络问题被放大成全局故障。因此
  `CircuitBreakerRegistry` 是节点本地的。

**Redis 接入形态可切换。** `mcp.executor.redis.mode` 支持 `single`（单实例 / 主从 / 代理）
与 `cluster`（Redis Cluster）：前者用 `address`，后者用 `nodes` 列表并由 Redisson 自动发现拓扑
（`scanInterval=1000ms`）。切换不需要动任何业务代码——共享状态只用 `RBucket` / `RLock` / `RTopic`
三个**单键**原语，没有跨槽（CROSSSLOT）操作。`database` 只在 single 模式下生效，
cluster 模式配了非 0 会 WARN 后忽略。

失败语义在这里分了两类，值得单独记住：**「连不上」退化为内存模式，「配错了」直接启动失败。**
前者是运行时状况（Redis 抖动不该拖死数据面），后者是配置错误——如果 cluster 模式漏配 `nodes` 也
悄悄降级，运维会以为自己配的是三节点集群，实际每个节点各跑各的内存态，且只在日志里闪一行 WARN。

`withLock` 的 `fallback` 参数**不允许抛异常**：一次上游抖动不该让所有等待者一起失败。

---

## 7. 多租户与权限

两层机制，职责不重叠：

- **权限点（RBAC）**：20 个，命名「资源:动作」，控制器用 `@PreAuthorize("hasAuthority(...)")`
  声明。6 个内置角色（`PLATFORM_ADMIN` / `OPS` / `AUDITOR` / `DEPT_ADMIN` /
  `DEPT_DEVELOPER` / `READONLY`）。内置角色的**权限可改、code 不可改、不可删除、被引用时不可删除**。
- **部门数据隔离**：不靠权限点，靠服务层的 `DepartmentScope` 行级过滤。跨部门访问返回
  **403**（不是 404）——PRD MGM-04 明确要求，因为「资源存在但你无权」与「资源不存在」
  在治理语境下是两种需要区分的事实。

集群授权也是部门维度：共享集群可授权给多个部门，私有集群只对归属部门开放。
授权集合是**整体替换**语义（`PUT /clusters/{id}/grants`）。

控制面自身用无状态 JWT。「登出」在服务端只做主体缓存失效——真正的失效由前端丢弃令牌完成；
这样即便令牌被复制走，也会在 TTL 到期前因为缓存里查不到而被拒绝（禁用立即生效）。

内部通道（`/internal/v1/**`）走独立的 `X-Executor-Token`，两级令牌：

- **集群令牌**：命中 `executor_cluster.node_token_hash`，身份含 clusterId，可拉该集群快照；
- **引导令牌**：`mcp.manager.executor.bootstrap-token`，**仅用于首次注册**，拿它拉快照会被
  403 并提示先注册。

令牌按 sha256 **常量时间比较**，明文不落库、不落日志。轮换后旧令牌立即失效，明文只返回一次。

---

## 8. 对外契约的稳定性

**PATH 模板 `{集群入口}/{平台保留前缀}/{自定义末段}`，用户只能自定义末段（BR-3）。**
末段规则 `^[a-z0-9]([a-z0-9-_]{0,62}[a-z0-9])?$`，共享集群内全局唯一，在注册、覆盖改名、
发布三处都校验（发布时再校验一次，防止并发注册产生同末段）。

改名等于让已配置的 MCP Client 断链，因此这是一次显式的、被单独审计的动作
（`SERVER_PATH_CHANGE`），而不是普通的 Server 更新。

**tool 名规则** `^[a-zA-Z0-9_-]{1,64}$`。覆盖 tool 名时仍要满足这个约束，否则 Executor
下发的 `tools/list` 会被客户端拒绝——问题会在离原因很远的地方爆发。

**新鲜度靠 `ttlMs` 而非推送。** 所有 list 类响应（`tools/list`、`resources/list`、
`prompts/list`、`server/discover`）都带 `ttlMs`（默认 30s，可按 Server 覆盖），
`capabilities.*.listChanged` 一律为 `false`——平台无会话、无服务端推送（SEP-2567）。

---

## 9. 失败语义总表

| 故障 | 数据面行为 | 控制面行为 |
| --- | --- | --- |
| Manager 不可达 | 保留上一份快照继续服务，记 WARN；新发布延迟生效 | — |
| Redis 不可达 | 退化为节点内存模式，端点照常服务；`isShared()=false` 显式暴露 | — |
| 上游 5xx / 超时 | 按 `retryOnStatus` 重试（默认 502/503/504，重试 1 次，**仅幂等方法**）；仍失败回 `-32003` | — |
| 上游连续失败 | 本地熔断打开（默认连续 5 次失败、保持 30s、半开放行 2 个探测） | — |
| 上游 401 | 丢弃令牌缓存并广播失效 | — |
| 快照反序列化失败 | **跳过该 Server** 并记 ERROR，其余 Server 照常服务 | — |
| 节点被下线 | 若进程还在跑，它会带着旧令牌重新注册回来（UI 上明确警告） | binding 保留，节点记录标记下线 |
| 请求打到未发布的末段 | `-32001 SERVER_NOT_FOUND` | — |
| 库里原始文档哈希不匹配 | — | 拒绝返回原文，要求运维介入（说明有人绕过应用改了库） |

「快照反序列化失败只跳过该 Server」是有意的：一个 Server 的格式问题不该让整个集群的
所有端点一起消失。

---

## 10. 已知取舍与缺口

以下都是**当前版本的真实状态**，不是待办清单的委婉说法。

| 项 | 现状 | 影响 |
| --- | --- | --- |
| **流式 tool（BR-5）** | 标记为 streaming 的 tool **从 `tools/list` 中剔除**；直接 `tools/call` 返回 501 + `-32002` + 原因 | 「列出来却调不通」比「不列」更糟——客户端会把它交给模型，然后模型每次都失败。RT-1 的 Spike 未做，不做 P0 承诺 |
| **Auth-D OAuth 2.1（EXE-07）** | 元数据字段会存、`resourceMetadataUrl` 会派生，但授权码 + PKCE + DCR 未实现；配置成 OAUTH2 的 Server 端点直接 501 拒绝 | P1 必达项。GA 前不可用于生产对外端点 |
| **resources / prompts（BR-7）** | `resources/list`、`prompts/list`、`resources/read`、`prompts/get` 已实现；两类对象在控制面**手动声明**（Swagger 推导不出来），随发布快照下发。`capabilities` 里只在非空时才声明 | 装配期细节见 §4.5。映射到已删除/停用 tool 的 Resource 会被静默跳过（列表与生效预览都会标出） |
| **tools/list 分页** | 不分页。传了 cursor 也**明确不回 `nextCursor`**（表示清单已完整） | 单集群单 Server 的 tool 数上限是几百，一次返回比分页游标更简单可靠 |
| **PATH 变更 301 迁移提示** | 未实现（PRD 标为 P2） | 改末段就是断链，只能提前通知使用方 |
| **运行时限流** | **未实现，且有意不做**（见下方评估）。集群已有**发布配额**（`executor_cluster.quota`：`maxServers` / `maxToolsPerServer` / `maxCatalogItemsPerServer`），在发布时校验 | 配额管的是"集群能装多少东西"（容量），限流管的是"每秒能打多少请求"（速率）。前者在发布这个低频可回滚的动作上拦最经济；后者需要令牌桶 + 新的拒绝语义 + 快照字段，是独立的一件事 |
| **tool 级 Auth-B 覆盖** | 已实现（BR-4）：`auth_config.tool_id != 0` 维度，三层回落 `Tool > REST 服务级 > Server`。控制面写入走 `PUT /servers/{id}/tools/{toolId}/overlay` 的 `authB` 字段 | 与 REST 服务级共用一套加解密与掩码逻辑，差别只在归属维度 |
| **Element Plus 全量引入** | UI 包 element-plus chunk 约 817 kB（gzip 前） | 已用 `manualChunks` 把 UI 库与业务代码拆开，避免每次发版让 1MB 的库缓存跟着失效。内部控制台首屏不如可维护性重要 |

---

## 11. 可观测性

| 手段 | 位置 |
| --- | --- |
| 审计日志 | 38 种动作码（`AuditAction`），结构化 detail 记录字段级 from/to 与 traceId。**append-only 由 DB 强制**（V8 触发器），并提供 CSV 导出（`GET /audits/export`，含公式注入防护与行数上限） |
| Prometheus 指标 | Executor `/actuator/prometheus` —— **无应用层鉴权**：数据面不引 Spring Security（`mcp-executor/pom.xml` 无该依赖），`/actuator/**`、`/executor/status`、`/healthz` 全部裸暴露，靠网络隔离兜底。文档此前写的「需 `metrics:read`」指的是**控制面**的 `/actuator/**`（`SecurityConfig` 的规则），两者不要混为一谈 |
| Executor 全量自检 | `/executor/status`：节点身份、快照 revision/etag/就绪状态、共享状态模式、熔断概况 |
| Executor 健康 | `/healthz`：供 LB 摘除判定 |
| Manager 健康 | `/actuator/health` |
| 协议元信息 | `GET /api/v1/meta`：受支持版本、legacy 版本集合、升级引导 URL、JSON Schema 方言、默认 ttl、PATH/tool 命名正则、内置角色 |

`/api/v1/meta` 是 UI 的「规则来源」：前端的 PATH 末段校验正则直接取自这里，而不是在前端
硬编码一份。规则改了，UI 跟着改，不会出现两边不一致。

---

## 12. 相关文档

- [ADR-0001：协议策略 Modern-only](adr/ADR-0001-modern-only-protocol.md)
- [ADR-0002：base ⊕ overlay 生效模型与锚点挂起区](adr/ADR-0002-base-overlay-merge.md)
- [API 契约](API.md)
- [部署与自检](../deploy/README.md)
- [产品规格文档 v0.2](PRD/MCP平台_产品规格文档_v0.2.md)
