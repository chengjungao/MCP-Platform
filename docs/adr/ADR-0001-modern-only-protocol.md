# ADR-0001：协议策略 Modern-only（仅实现 MCP 2026-07-28）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-03 |
| 决策编号 | PRD 决策 D1 |
| 相关需求 | EXE-08（P0）、R10（主动接受风险） |
| 相关文档 | PRD §0.4、§5.6、附录 A |

## 背景

MCP 2026-07-28 正式版把协议推向「标准 HTTP 基础设施即可托管」的无状态形态，两个 SEP 是这次
断裂的根源：

- **SEP-2575**：移除 `initialize` 握手。能力发现改由 `server/discover` 承担，客户端不再需要先
  协商版本再干活。
- **SEP-2567**：移除 `Mcp-Session-Id`。协议层不再有会话，任何请求可以落在集群任意节点。

配套还有 SEP-2243（请求携带 `Mcp-Method` / `Mcp-Name` 头，网关无需解析请求体即可路由）与
SEP-2549（list 类响应带 `ttlMs` 缓存提示，替代服务端推送）。

平台立项时（2026-09-03）新旧两版协议都还有存量客户端。要不要同时支持 2025-11-25，是必须先定的
架构问题——它决定了 Executor 的入口形态、是否需要共享会话存储、以及网关能不能用普通轮询 LB。

## 决策

**全平台只实现并只应答 MCP 2026-07-28。对 legacy 客户端显式拒绝，不做双栈、不做兼容转换、
不预留 legacy 适配器。**

具体含义：

1. 唯一受支持版本是常量 `McpProtocol.SUPPORTED_VERSION = "2026-07-28"`，写死在
   `mcp-common`，Manager 与 Executor 共用同一份定义。
2. `LEGACY_VERSIONS`（2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25）只用于**识别与埋点**，
   不用于兼容。区分「已知 legacy」与「未知版本」是为了让拒绝原因可读：前者能明确告诉用户
   「这个版本我们停止支持了」，后者只能说「不受支持」。
3. 平台不持有任何会话存储。Redisson 承载的是应用层共享状态（BR-6），不是协议会话。
4. 网关层**禁止粘性会话**，按 `Mcp-Method` / `Mcp-Name` 头路由。

## 落地方式

拒绝逻辑集中在 `mcp-executor` 的 `ProtocolGuard`，必须在任何业务逻辑之前跑完。识别五种 legacy
形态，任一命中即拒绝：

| # | 特征 | 依据 |
| --- | --- | --- |
| 1 | `initialize` / `notifications/initialized` 方法 | SEP-2575 已取消握手 |
| 2 | `Mcp-Session-Id` 头存在 | SEP-2567 已移除会话 |
| 3 | `Mcp-Protocol-Version` 头 ≠ 2026-07-28 | 版本声明 |
| 4 | 请求体 `params.protocolVersion` ≠ 2026-07-28 | 版本声明 |
| 5 | `_meta.sessionId` 存在 | 会话形态残留 |

守卫单独抛出 `LegacyProtocolException`（而不是复用通用的 `McpErrorException`），因为它的响应体
有固定形状，客户端可以据此自动给出「请升级」提示：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32022,
    "message": "Unsupported Protocol Version: 本平台仅支持 MCP 2026-07-28 …",
    "data": {
      "supportedProtocolVersion": "2026-07-28",
      "detectedProtocolVersion": "2025-11-25",
      "reason": "…",
      "upgradeUrl": "https://modelcontextprotocol.io/specification/2026-07-28",
      "legacySupported": false
    }
  }
}
```

`-32022` 是 EXE-08 指定的错误码；`legacySupported: false` 是给客户端的机器可读承诺。
五种形态各自给出**可区分**的 `reason`，否则用户只会看到「连不上」，然后花一天时间抓包。

`Mcp-Method` 头优先于请求体 `method`（`ProtocolGuard.resolveMethod`），两者不一致时以头为准并记
WARN——这种客户端一定有 bug，要让它被发现，而不是被静默容忍。

## 被拒方案

**方案 A：双协议入口（同一端点按版本分流）。**
拒绝。分流点必须解析请求体才能判断版本，这直接抵消了 SEP-2243 头路由的价值——网关又变回
「必须理解 MCP 语义」的设备。更致命的是它会引入两套并行的错误语义与会话语义，测试矩阵翻倍，
而 legacy 侧的每一次协议演进都要跟随维护。

**方案 B：过渡期双栈 + 弃用公告（先兼容 N 个月再摘除）。**
拒绝。这是最容易被接受的方案，也最容易变成永久债务：一旦有生产客户端跑在兼容层上，摘除动作
就有了阻力，而「摘除日」通常不会到来。平台在 2026-09-03 立项时 2026-07-28 已发布正式版，
不存在「用户还没来得及升级」的历史包袱——包袱是我们自己背上去的。

**方案 C：静默降级（不声明版本就当作 2026-07-28 处理）。**
拒绝，但只拒绝一半。**不声明**版本确实按 2026-07-28 处理（见 `ProtocolGuard`：头为空则跳过
版本检查），因为要求每个客户端都主动声明会增加接入摩擦且无安全收益。但**声明了错误版本**必须
拒绝——静默接受会让客户端误以为自己在跟 2025-11-25 服务端对话，然后在会话相关行为上出错，
排查成本远高于一次明确的 -32022。

## 后果

**正面：**

- Executor 无状态可丢，任意节点服务任意请求，可以直接跑在普通轮询 LB 之后，无需粘性会话、
  无需会话亲和、无需会话迁移。
- 不需要协议会话存储。Redisson 的职责收窄为三类应用层状态（上游令牌缓存、刷新锁、失效广播），
  Redis 不可用时数据面**仍能服务**（退化为每节点各自换取令牌），这是可用性上的实质收益。
- 协议层只有一套语义，`tools/list` 的 `ttlMs` 是唯一的新鲜度机制，不存在「推送还是轮询」的分叉。
- 横向扩展是天然能力而非需要额外设计的特性。

**负面（已作为 R10 主动接受）：**

- 尚未升级的 legacy 客户端初期无法接入，且**没有渐进路径**——只能升级客户端。
- 拒绝是硬失败，接入方第一次调用就会撞墙。缓解手段是让失败自解释：`-32022` + `reason` +
  `upgradeUrl`，客户端可以直接把这段话显示给用户。
- 需要持续埋点观测 legacy 请求量（`detectedProtocolVersion` 已在错误 `data` 里，可直接聚合），
  以便判断是否存在某个大客户长期卡在旧版本。

## 何时重开这个决策

只有当埋点显示 legacy 请求占比长期（> 2 个季度）高于可忽略水平，且这些请求集中在少数无法
推动升级的客户端时，才值得重新评估。届时应优先考虑**独立的兼容网关**（单独进程、单独端点、
单独生命周期），而不是把双栈塞回 Executor——本决策要保护的核心资产是数据面的无状态性，
兼容层绝不能污染它。

## 相关代码

- `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpProtocol.java`
- `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpHeaders.java`
- `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpMethods.java`
- `mcp-common/src/main/java/com/mcpbridge/common/jsonrpc/JsonRpcErrorCodes.java`
- `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/ProtocolGuard.java`
- `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/LegacyProtocolException.java`
