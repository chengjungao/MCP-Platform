# 部署

一体化编排，用于本地验证与单机试运行。**不是**生产部署方案——生产需要的多可用区、
外部负载均衡、密钥托管、备份策略都不在这里。

## 拓扑

```
MCP 客户端 ──► http://localhost:9090/mcp/{末段}   ← 集群入口（EXECUTOR_CLUSTER_ENTRYPOINT）
                      │
        ┌─────────────┴─────────────┐
   executor-1                   executor-2      数据面：无状态、无粘性会话
        │  ▲                        │  ▲
        │  └──── 轮询快照 ───────────┘  │
        ▼                              ▼
     manager ──► postgres           redis
   控制面：注册/覆盖/发布        共享状态：令牌缓存 + 失效广播 + 分布式锁
```

两个 Executor 各自持有完整本地快照，彼此不通信；Redis 只承载**应用层共享状态**
（BR-6），不承载路由信息。因此 Redis 挂掉时端点仍可服务，只是退化为
「每个节点各自向上游换取一次令牌」，这个降级会显式记在启动日志与 `/executor/status` 里。

## 前置

镜像只负责运行时，不在容器里编译。后端先产出两个可执行 jar：

```powershell
# Windows（build.cmd 必须带目标，无参时 Maven 会报 "No goals have been specified"）
.\build.cmd -DskipTests clean package
```

```bash
# Linux / macOS
mvn -B -DskipTests clean package
```

去掉 `-DskipTests` 会跑全量单元测试（包含覆盖合并、PATH 校验、Schema 转换、协议守卫）。

产物：`mcp-manager/target/mcp-manager.jar`、`mcp-executor/target/mcp-executor.jar`。

控制台同理：`manager-ui` 镜像只把静态产物拷进 nginx（见 `docker/mcp-manager-ui.Dockerfile`），
所以要先在宿主机构建：

```bash
cd mcp-manager-ui
npm ci
npm run build        # 产物 mcp-manager-ui/dist
```

少了这一步，`docker compose build manager-ui` 会在 `COPY mcp-manager-ui/dist` 处失败，
而报错只说找不到源目录，不会提示「你忘了构建前端」。只想跑后端时把 UI 排除在外：
`docker compose up -d --build postgres redis manager executor-1 executor-2`。

## 起停

```bash
cd deploy
cp .env.example .env          # Windows: copy .env.example .env
docker compose up -d --build
docker compose ps
docker compose logs -f manager executor-1
```

停止并保留数据：`docker compose down`。
连数据一起清掉（会丢失全部注册与发布记录）：`docker compose down -v`。

## 起来之后

| 用途 | 地址 |
| --- | --- |
| 管理控制台 | http://localhost:5173 |
| 控制面 API | http://localhost:8080/api/v1 |
| 控制面健康 | http://localhost:8080/actuator/health |
| MCP 端点 | http://localhost:9090/mcp/{PATH 末段} |
| Executor 自检 | http://localhost:9090/executor/status |
| Executor 健康 | http://localhost:9090/healthz |
| Prometheus 指标 | http://localhost:9090/actuator/prometheus |

首次启动 Manager 会幂等写入内置角色、根部门与管理员账号（`admin` / `MANAGER_ADMIN_PASSWORD`），
并创建名为 `default` 的共享集群，节点接入令牌即 `EXECUTOR_BOOTSTRAP_TOKEN`。

## 最小闭环自检

下面用到 `curl` 与 `jq`。没有 `jq` 就直接开控制台（http://localhost:5173）点一遍——
顺带把前端也验证了。

```bash
# 0. 登录取令牌。密码是 .env 里的 MANAGER_ADMIN_PASSWORD；
#    .env 不会自动进当前 shell，先 `set -a; source .env; set +a`，或把占位符换成明文
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d "{\"username\":\"admin\",\"password\":\"$MANAGER_ADMIN_PASSWORD\"}" | jq -r .data.token)

# 1. 注册一份 Swagger 文档（name 必填；原始文档只读留存 + sha256 双保险）
SERVER_ID=$(curl -s -X POST http://localhost:8080/api/v1/registrations/upload \
     -H "Authorization: Bearer $TOKEN" \
     -F "name=order-service" -F "file=@my-openapi.json" | jq -r .data.serverId)

# 2. PATH 末段未显式指定时由服务标题派生，不要凭直觉拼 URL，读回来
SEGMENT=$(curl -s "http://localhost:8080/api/v1/servers/$SERVER_ID" \
     -H "Authorization: Bearer $TOKEN" | jq -r .data.pathSegment)

# 3. 发布到 default 集群（传播上界约 30s，即 Executor 的快照轮询间隔）
CLUSTER_ID=$(curl -s http://localhost:8080/api/v1/clusters \
     -H "Authorization: Bearer $TOKEN" | jq -r '.data[0].id')
curl -s -X POST "http://localhost:8080/api/v1/servers/$SERVER_ID/publish" \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d "{\"clusterId\":$CLUSTER_ID,\"note\":\"首次自检\"}" | jq .

# 4. 用 MCP 2026-07-28 客户端列出工具
curl -X POST "http://localhost:9090/mcp/$SEGMENT" \
     -H 'Content-Type: application/json' \
     -H 'Mcp-Protocol-Version: 2026-07-28' \
     -H 'Mcp-Method: tools/list' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 5. 验证 Modern-only：带 legacy 会话头必须被显式拒绝（-32022 + 升级引导）
curl -X POST "http://localhost:9090/mcp/$SEGMENT" \
     -H 'Content-Type: application/json' \
     -H 'Mcp-Session-Id: whatever' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

第 5 步返回 `error.code = -32022`，`error.data` 里带 `upgradeUrl` 与 `legacySupported: false`。
这是平台对外的硬承诺，任何改动都不该让它退化。

第 3 步若报「发布前校验未通过」，`details` 会直接写明缺什么（通常是还没配上游地址）。
这不是编排故障：注册出来的 Server 是 DRAFT，去控制台补完上游再发布即可。

## 上生产前必须改的

1. **三个密钥**：`MANAGER_JWT_SECRET`、`MANAGER_CRYPTO_KEY`、`EXECUTOR_BOOTSTRAP_TOKEN`
   换成高熵随机串，由密钥管理系统注入，不进 `.env`、不进仓库、不进镜像。
   `MANAGER_CRYPTO_KEY` 一旦上线不可再换——换了已存的 Auth-B 密文就解不开了。
2. **`MANAGER_PARSE_ALLOW_PRIVATE_NETWORKS=false`**：本地默认放开了内网文档拉取，
   生产不关就等于给控制面开了一个 SSRF 入口。
3. **`MANAGER_ADMIN_PASSWORD`**：首启后立即改，或直接关掉 bootstrap 用初始化流程建号。
4. **postgres 端口映射**：默认只绑 `127.0.0.1`，别为了图方便改成 `0.0.0.0`。
5. **Redis 加鉴权**：这里靠「不发布端口」隔离，跨主机部署必须上 `requirepass` 或 TLS。
6. **集群入口**：`EXECUTOR_CLUSTER_ENTRYPOINT` 填负载均衡器地址，并确保 LB
   **不开粘性会话**——数据面是无状态的，粘性只会让节点摘除时把流量一起带走。