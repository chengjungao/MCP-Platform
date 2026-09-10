# MCP Bridge Platform

把企业存量 REST API（Swagger / OpenAPI 2.0 与 3.x）批量翻译为**可治理、可托管、可多集群发布**
的 MCP Server 的开源桥接平台。

**注册即解析、配置即发布、调用即透传。**

协议策略：**Modern-only** —— 全平台只实现 MCP **2026-07-28**，对 legacy 客户端显式拒绝
（错误码 `-32022` + 升级引导），不做双栈、不做兼容转换。理由见
[ADR-0001](docs/adr/ADR-0001-modern-only-protocol.md)。

---

## 定位

单文件转换器与单实例代理已经很拥挤。本平台的差异不在「转换能力」，而在**平台化治理**：

- 多租户 RBAC（用户 / 角色 / 部门），资源按部门归属，越权返回 403；
- 注册 → 覆盖精修 → 发布 → 回滚的完整治理链路，每一步都有版本与审计；
- 原始 Swagger **永不被改动**（只读 + sha256 双保险），用户的精修存放在独立的覆盖层，
  文档升级后按锚点对账，锚点消失的覆盖进**挂起区**而不是被静默丢弃；
- 共享 / 私有集群托管 Executor，数据面无状态、可横向扩展、禁止粘性会话。

---

## 架构

```
控制面  mcp-manager   注册解析 / base⊕overlay / 集群 / 发布 / RBAC / 审计
                          │  发布快照（版本化，写入 publish_binding）
                          ▼  Executor 用节点令牌轮询拉取（两段式：revision → snapshot）
数据面  mcp-executor  协议守卫 / Auth-D / 参数映射 / Auth-B / 负载均衡 / 熔断
                          │
                          ▼
                      REST 服务
```

两个平面之间只有一条通道，Executor **不直连控制面数据库**，因此私有集群可以部署在
网络隔离的环境里。Redis（Redisson）只承载三类应用层共享状态（上游令牌缓存、刷新锁、
失效广播），**不承载路由**——所以 Redis 挂掉时端点仍可服务，只是退化为每节点各自换取令牌，
且这个降级是显式暴露的。

详见 [架构说明](docs/ARCHITECTURE.md)。

---

## 模块

| 模块 | 技术栈 | 说明 |
| --- | --- | --- |
| `mcp-common` | Java 23 + Jackson | 协议常量、JSON-RPC 信封与错误码、快照模型、命名规则。Manager 与 Executor 共用同一份快照类型，字段漂移在编译期就暴露 |
| `mcp-manager` | Spring Boot 3.5 / Spring MVC / JPA / PostgreSQL / Flyway / Spring Security 6 / jjwt / swagger-parser | 控制面 |
| `mcp-executor` | Spring Boot 3.5 / WebFlux (Reactor Netty) / Redisson / Micrometer | 数据面 |
| `mcp-manager-ui` | Vue 3 / TypeScript / Vite / Pinia / Element Plus | 管理控制台 |

依赖方向严格单向：`manager → common`、`executor → common`，两个业务模块互不依赖。

---

## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 23 | 编译目标 `release 23` |
| Maven | 3.9+ | 仓库内置 `.mvn/settings.xml`（HTTPS 镜像） |
| Node / npm | 20 / 10 | 仅构建控制台时需要 |
| Docker + Compose | 24+ / v2 | 一体化编排 |

### 一体化编排（推荐）

```powershell
# 1. 产出两个可执行 jar（镜像内不编译）
.\build.cmd -DskipTests clean package          # Linux/macOS: mvn -B -DskipTests clean package

# 2. 产出控制台静态资源
cd mcp-manager-ui
npm ci
npm run build
cd ..

# 3. 起编排：postgres + redis + manager + executor×2 + manager-ui
cd deploy
copy .env.example .env                          # Linux/macOS: cp
docker compose up -d --build
```

起来之后：

| 用途 | 地址 |
| --- | --- |
| 管理控制台 | http://localhost:5173 |
| 控制面 API | http://localhost:8080/api/v1 |
| MCP 端点 | http://localhost:9090/mcp/{PATH 末段} |
| Executor 自检 | http://localhost:9090/executor/status |

首次启动 Manager 会幂等写入内置角色、根部门与管理员账号（`admin` / `MANAGER_ADMIN_PASSWORD`），
并创建名为 `default` 的共享集群，节点接入令牌即 `EXECUTOR_BOOTSTRAP_TOKEN`。

**端到端自检脚本与上生产前必须改的 6 项，见 [deploy/README.md](deploy/README.md)。**

### 本地直接跑（不用 Docker）

需要自备 PostgreSQL 与 Redis。Flyway 会在 Manager 首次启动时建表。

```powershell
.\build.ps1 -pl mcp-manager -am spring-boot:run
.\build.ps1 -pl mcp-executor -am spring-boot:run
cd mcp-manager-ui; npm run dev          # Vite 开发服务器，已配同源反代
```

`build.cmd` / `build.ps1` 强制使用项目内 `.mvn/settings.xml`，避免机器全局 settings 里的
HTTP 镜像被 Maven 3.9 的 `maven-default-http-blocker` 拦截。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、数据模型、关键流程、双跳鉴权、状态边界、失败语义总表、已知缺口 |
| [docs/API.md](docs/API.md) | 控制面 REST 契约、内部通道契约、MCP 端点契约、错误码表、权限点与内置角色 |
| [docs/adr/ADR-0001](docs/adr/ADR-0001-modern-only-protocol.md) | 协议策略 Modern-only：决策、被拒方案、后果 |
| [docs/adr/ADR-0002](docs/adr/ADR-0002-base-overlay-merge.md) | base ⊕ overlay 生效模型与锚点挂起区 |
| [docs/adr/ADR-0003](docs/adr/ADR-0003-streaming-bridge.md) | 流式桥接采用 Streamable HTTP SSE + 缓冲兜底 |
| [docs/spike/RT-1-streaming-bridge.md](docs/spike/RT-1-streaming-bridge.md) | RT-1 Spike 报告：流式桥接协议层落地形态调研 |
| [docs/spike/Multi-Upstream-Server-Design.md](docs/spike/Multi-Upstream-Server-Design.md) | 单 MCP Server 支持多 REST 服务数据模型变更设计 |
| [docs/PRD/](docs/PRD/) | 产品规格文档 v0.2（需求来源） |
| [docs/使用手册-REST发布与Codex接入.md](docs/使用手册-REST发布与Codex接入.md) | 使用手册：REST API 发布 MCP Server + Codex 集成（配操作截图） |
| [deploy/README.md](deploy/README.md) | 编排拓扑、自检脚本、生产前必改项 |
| [mcp-manager-ui/README.md](mcp-manager-ui/README.md) | 控制台工程说明 |

---

## 当前范围

本仓库是 **P0 可运行骨架**：可编译、可启动、可测试，治理链路端到端打通。

已实现：注册解析（上传 / URL / 粘贴 / 重新解析 + diff）、base ⊕ overlay 覆盖与挂起区、
Server/Tool 精修、上游策略与负载均衡、Auth-B 五种鉴权（按 REST 服务独立配置，凭据 AES-256-GCM 加密托管）、
Auth-D 的 NONE 与 STATIC_BEARER、集群与节点管理、发布 / 下线 / 回滚、快照两段式同步、
MCP 2026-07-28 端点（`server/discover` / `tools/list` / `tools/call` / `ping`）、
Modern-only 协议守卫、Redisson 共享状态与降级、RBAC 与部门隔离、审计、Vue3 控制台。

**明确未实现**（都在 [架构说明 §10](docs/ARCHITECTURE.md#10-已知取舍与缺口) 里写明现状与影响，
不做委婉表述）：

- **流式 tool（EXE-06 / BR-5）**：Spike（RT-1）已完成，方案见 [ADR-0003](docs/adr/ADR-0003-streaming-bridge.md)
  （Streamable HTTP SSE + 缓冲兜底），状态 **Proposed、待评审落地**（2026-09-05 起挂起）。
  当前仍维持 P0 行为（从 `tools/list` 剔除、调用返回 501）。
- **Auth-D OAuth 2.1（EXE-07）**：元数据会存、`resourceMetadataUrl` 会派生，但授权码 + PKCE + DCR
  未实现。配置成 OAUTH2 的 Server 端点**显式拒绝**（501 + `-32004`）——半实现的鉴权比没有
  鉴权更危险，因为它会让运维误以为端点已受保护。
- **resources / prompts 管理（SVR-05/06）**：executor 运行时已就绪（list/read/get + discover 门控 +
  快照），manager 缺管理模型 / UI / 组装 → 对外恒返回空目录 + `ttlMs`（P1，Swagger 推导不出这两类对象）。
- **PATH 变更 301 迁移提示（P2）**：路径末段变更后旧地址的迁移提示未实现。
- **WEIGHTED 权重编辑器（P2）**：权重已可落库（`server_upstream.weights` JSONB）且 Executor 按权分发，
  权重与地址数量不匹配时退回轮询并告警（EXE-04 主体已提前完成）；剩余缺口是控制台权重编辑器。

---

## 待办事项（Backlog）

> 盘点日期：2026-09-07；基准：[PRD v0.2 §5.5](docs/PRD/MCP平台_产品规格文档_v0.2.md) 需求功能清单。
> 结论：**P0 / MVP 已全部落地**；下表均为 P1 / P2 后续项。编号即 PRD 功能编号。

### 已就绪待评审（唯一有完整方案）

| 编号 | 待办 | 状态与下一步 |
| --- | --- | --- |
| EXE-06 | 流式 tool 桥接（BR-5） | [ADR-0003](docs/adr/ADR-0003-streaming-bridge.md) 已 Proposed（2026-09-05 挂起）：Streamable HTTP SSE + 缓冲兜底；评审通过后落地 executor 流式分支 |

### P1 未开始（GA 必达候选）

| 编号 | 待办 | 现状 / 缺口 |
| --- | --- | --- |
| EXE-07 | Auth-D OAuth 2.1 | 元数据、`resourceMetadataUrl` 派生已存；授权码 + PKCE + DCR 未实现，OAUTH2 配置现显式拒绝（501 + `-32004`） |
| SVR-05/06 | Resource / Prompt 管理 | executor 运行时已就绪，manager 无管理模型 / UI / 组装 → 对外恒空清单 |
| SVR-07 | 变更审核 | 覆盖 / 发布进待审批未做；现有「审核」仅覆盖跨部门访问申请（AccessService） |
| PUB-05 | 发布可观测看板 | 健康 / 调用量看板，需与 Executor 指标打通 |
| OPS-02 | W3C Trace Context | 当前仅 `X-Trace-Id` 响应回传，注入上游未做 |
| SEC-03 | PIPL / GDPR 合规标注 | 数据分类分级标注未做 |

### 部分完成（收尾项）

| 编号 | 待办 | 已完成 | 待收尾 |
| --- | --- | --- | --- |
| REG-03 | 注册解析增强 | overlay 挂起区（suspendedOverlays diff） | re-import diff 报告；文档多版本历史链（现单份原文 sha256） |
| EXE-04 | 健康剔除 | 轮询 / 加权 + 熔断 HALF_OPEN 探测 | 主动健康探测摘除 |
| OPS-01 | Prometheus 指标 | executor pom 依赖就位 | `/metrics` 端点未见实现痕迹（待核实） |
| MGM-05 | 审计 | 页面 + 动作已落地 | append-only 严格不可篡改未评估 |

### 提前完成（勿重复排期）

| 编号 | 说明 |
| --- | --- |
| PUB-04 | 发布回滚 |
| EXE-04（主体） | 多实例轮询 · 加权 · 熔断 |
| SVR-04 | 流式声明（SSE 声明 / 能力门控） |
| 其他 | tool 级 Auth-B 覆盖（authBOverride）、审计页面与动作（MGM-05 主体） |

---

## 开发

```powershell
.\build.ps1 clean package                     # 全量构建 + 单元测试
.\build.ps1 -pl mcp-manager test              # 只跑控制面测试
.\build.ps1 -pl mcp-executor test             # 只跑数据面测试

cd mcp-manager-ui
npm run type-check                            # vue-tsc，strict + noUnusedLocals
npm run build
```

单元测试覆盖的高风险路径：覆盖合并与锚点对账、PATH 末段校验、JSON Schema 转换、
协议守卫的五种 legacy 形态、上游调用的权重退化路径。

改动后请守住三条不变量：

1. `-32022` 拒绝行为不退化（平台对外的硬承诺）；
2. 原始文档只读 + sha256 校验不退化；
3. Executor 无状态、不引入任何依赖会话的路由。

---

## License

MIT License —— 见 [LICENSE](LICENSE)。
