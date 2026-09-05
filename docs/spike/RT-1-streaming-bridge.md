# RT-1 Spike 报告：流式桥接协议层落地形态

| 项目 | 内容 |
| --- | --- |
| 状态 | Completed |
| 日期 | 2026-09-05 |
| 关联 | PRD BR-5 / EXE-06 / R4 / §11 M0 硬任务 |
| 关联 ADR | ADR-0001（Modern-only）、ADR-0003（待写，流式桥接决策） |

## 0. 结论先行

**MCP 2026-07-28 原生支持「单 POST 回 SSE 流」的传输形态，但规范没有「优雅承载工具调用增量数据」的协议层机制。** 流式透传必须做工程权衡，不存在「照抄某个规范方法」的解法。

三条候选路径评估后，采用 **路径①（Streamable HTTP SSE）+ 路径③（缓冲兜底）的组合策略**：

- **主路径①**：`tools/call` 命中流式 tool 时，Executor 返回 `text/event-stream`，把上游 chunk 映射为 `notifications/progress`（progressToken 绑定请求 id，progress 单调递增，message 塞 chunk 文本），流结束发最终 `CallToolResult`（`resultType: "complete"`）。
- **回退路径③**：客户端 `Accept` 头不含 `text/event-stream` 时，退化为收齐上游后回单 `application/json`。

**路径②（Tasks 扩展）不采用**——语义错位，详见 §3。

## 1. 调研证据

### 1.1 Streamable HTTP 传输层（规范原生支持 SSE 流）

> "The server answers each request with either a single JSON object or a Server-Sent Events (SSE) stream scoped to that request, carrying request-related notifications followed by the final response."
> — [Streamable HTTP transport, 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)

关键约束：

| 约束 | 规范原文（释义） | 影响 |
| --- | --- | --- |
| 响应形态二选一 | 单 `application/json` 或 `text/event-stream`，客户端 **MUST** 都支持 | Executor 可以按 tool 形态自选 |
| 流中允许的 notification | `notifications/progress`、`notifications/message`，**MUST** 关联发起请求 | 只能用这两类承载中间态 |
| 流中禁止的内容 | 独立 JSON-RPC request（sampling/elicitation 已重构为 MRTR 的 `InputRequiredResult`） | 不能借 elicitation 旁路传数据 |
| 终止条件 | 最终 JSON-RPC response **SHOULD** 终止流 | 最终 result 即 EOF |
| 不可恢复 | `Last-Event-ID` 不支持 | 客户端断开 = 数据丢失，不续传 |
| 缓冲控制 | 服务器 **SHOULD** 发 `X-Accel-Buffering: no` 头 | 防 nginx/反代缓冲吞 chunk |
| 取消语义 | 客户端关闭 SSE 流 = 取消信号，服务器 **MUST** 视为取消并停发后续消息 | Executor 需监听流取消并取消上游订阅 |

### 1.2 `notifications/progress`（无 data 字段）

```json
{
  "method": "notifications/progress",
  "params": {
    "progressToken": "abc123",
    "progress": 50,
    "total": 100,
    "message": "Reticulating splines..."
  }
}
```

- 字段：`progressToken`（string/int，客户端在请求 `_meta` 里提供）、`progress`（**MUST** 单调递增）、`total`（可选）、`message`（**SHOULD** 人类可读）。
- **无 `data` 字段。** `message` 是字符串，不是结构化载荷。规范明确「progress value MUST increase with each notification」——拿它当 chunk 序号是合规的，但 message 字段语义是「进度信息」而非「数据内容」。
- 客户端是否会把 `message` 当作工具输出交给 LLM？规范不规定，取决于客户端实现。**这是路径①的固有风险**。

### 1.3 `notifications/message`（已废弃，不采用）

```json
{
  "method": "notifications/message",
  "params": {
    "level": "info",
    "logger": "upstream",
    "data": { "chunk": "..." }  // 任意 JSON-serializable
  }
}
```

- `data` 字段能承载任意 JSON——技术上是最适合塞 chunk 的地方。
- **但规范明确标记 Deprecated（SEP-2577），新实现 SHOULD NOT 采用。** 用它等于把流式透传绑在一个 12 个月后可能移除的特性上。
- 拒绝采用。

### 1.4 `CallToolResult` 是终态单结果

```json
{
  "result": {
    "resultType": "complete",
    "content": [{"type": "text", "text": "..."}],
    "structuredContent": {...},
    "isError": false
  }
}
```

- `resultType` 取值 `"complete"` 或 `"input_required"`——**没有 `"streaming"` / `"partial"` / `"progressive"`**。
- `structuredContent` 是终态字段，不能增量构建。
- `content` 数组是终态交付物。
- **结论：工具的增量数据只能在最终 result 里出现一次，不能在流过程中作为「结果的一部分」增量交付。** 流过程中发的 notification 是「请求相关通知」，语义上不是「工具结果」。

### 1.5 Tasks 扩展（不适用）

- `io.modelcontextprotocol/tasks` 面向「分钟/小时级异步作业 + 轮询」，核心是 `CreateTaskResult`（`resultType: "task"`）+ `tasks/get` 轮询 + 可选 `notifications/tasks`。
- **没有流式数据通道**——`tasks/get` 返回的是任务状态快照（`working`/`completed`/...），不是数据流。
- 流式透传场景是「秒级、边收边出」，用 Tasks 是「拿大锤敲螺丝」且语义错位。
- 拒绝采用 Tasks 承载流式透传。但 Tasks 在另一个场景有价值：上游 REST 是「提交作业 → 轮询状态」型异步 API（如视频转码、批处理），这时映射为 Task 是语义契合的。**这是 P2 增量，不在本次 Spike 范围**。

## 2. 三条候选路径评估

| 路径 | 协议契合度 | 流式体验 | 复杂度 | 客户端兼容性 | 决策 |
| --- | --- | --- | --- | --- | --- |
| ① Streamable HTTP SSE | 中（规范原生支持 SSE 流，但 progress 无 data 字段是硬约束） | 边收边出 | 高（Executor 透传 + Controller 流式响应 + 取消传播） | 需客户端声明 `Accept: text/event-stream` | **采用** |
| ② Tasks 扩展 | 低（语义错位，无流式数据通道） | 无（轮询） | 中（需实现 tasks/get、状态持久化、TTL） | 需客户端声明 tasks 扩展能力 | **不采用** |
| ③ 缓冲完整结果 | 高（标准单 JSON 响应） | 无 | 低（现状） | 全兼容 | **作为①的回退** |

## 3. 选定方案的形态

### 3.1 响应形态判定

```
tools/call POST 进来
  │
  ├─ tool.streaming() == false → 现有路径（单 JSON 响应）
  │
  └─ tool.streaming() == true
        │
        ├─ Accept 头含 text/event-stream → 路径①（SSE 流）
        └─ Accept 头不含 → 路径③（缓冲后单 JSON，附 deprecation 提示）
```

**客户端兼容性**：MCP 规范要求客户端 **MUST** 同时支持 `application/json` 和 `text/event-stream`，所以路径③只在「非规范客户端」或「保守客户端」时触发。但平台不假设客户端合规——Accept 头是唯一判定依据。

### 3.2 路径①的 SSE 帧结构

上游 SSE/NDJSON/chunked 流的每个数据块，映射为一条 `notifications/progress`：

```
event: message
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"<req-id>","progress":<n>,"message":"<chunk-text>"}}

event: message
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"<req-id>","progress":<n+1>","message":"<chunk-text>"}}

...

event: message
data: {"jsonrpc":"2.0","id":<req-id>,"result":{"resultType":"complete","content":[{"type":"text","text":"<完整拼接>"}],"isError":false}}
```

**设计要点：**

| 项 | 决策 | 理由 |
| --- | --- | --- |
| progressToken 来源 | 客户端在请求 `_meta.progressToken` 提供；未提供时由 Executor 用请求 id 生成 | 规范要求 token 由客户端提供且全局唯一；兜底生成保证非规范客户端也能用 |
| progress 值 | chunk 序号，从 1 单调递增 | 规范要求 MUST increase；不假设 total（流式总量未知） |
| message 字段 | chunk 文本（UTF-8 截断到合理长度，如 4KB） | 唯一能塞数据的地方；超长 chunk 拆多条 progress |
| 最终 result.content | 完整拼接的所有 chunk | 给保守客户端一个完整交付物；流式客户端也可选只取最终 result |
| structuredContent | 不在流式场景使用 | 它是终态字段，增量构建无意义 |
| 错误处理 | 上游错误 → 流中发一条 JSON-RPC error response 终止流；不发 progress | 保持「最终 response 终止流」的语义 |
| 取消传播 | 监听 SSE 流关闭（`Flux.doOnCancel`）→ 取消上游 `WebClient` 订阅 | 规范要求服务器 MUST 停发消息 |
| 背压 | 不主动背压，靠 chunk 拆分 + progress 频率限制（默认每 chunk 一条，可配） | 防止上游高频 chunk 打爆 SSE 流 |

### 3.3 上游 chunk 文本截断与拼接

上游三种流格式（`streamFormat` 字段）的处理差异：

| streamFormat | 上游帧解析 | chunk 文本提取 |
| --- | --- | --- |
| `SSE` | 按 `data:` 行解析，每个 event 的 data 拼成一条 chunk | event data 的 JSON/text 内容 |
| `NDJSON` | 按换行切分，每行一条 JSON | 整行作为 chunk |
| `CHUNKED` | HTTP chunked 透传，按 chunk boundary 切 | chunk 原始字节转 UTF-8 |

**拼接策略**：Executor 在内存维护一个 `StringBuilder` 累积所有 chunk 文本，流结束时作为最终 result 的 `content[0].text`。**不做磁盘落盘**——流式 tool 的语义是「实时输出」，不假设客户端会重放。如果上游总量超过配置阈值（如 16MB），在最终 result 里标记 `isError: true` + 截断提示，而不是失败。

## 4. 代码改动影响面

基于对现有代码的探查（见附录 A 关键文件清单），改动分四层：

### 4.1 控制面（mcp-manager）

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 流式 tool 不再从 `tools/list` 剔除 | Manager 侧发布逻辑无需改（剔除逻辑在 Executor 的 `McpDispatcher`） | — |
| `server/discover` 声明 streaming 能力 | 新增 capabilities 字段 | 让客户端知道该 Server 有流式 tool |
| 快照 `ToolSnapshot` 字段无变化 | `streaming` / `streamFormat` 已存在 | 无需改数据模型 |

### 4.2 数据面（mcp-executor）—— 改动核心

| 改动点 | 文件 | 改动内容 |
| --- | --- | --- |
| ① `tools/list` 不再剔除流式 tool | `McpDispatcher.listTools` 第 108-113 行 | 删掉 `if (tool.streaming()) continue`，改为正常列出（可选：在 tool entry 标注 `streaming: true` 提示） |
| ② `tools/call` 流式分支 | `McpDispatcher.callTool` 第 146-152 行 | 删掉 501 拒绝，改为：`if (tool.streaming() && acceptSse) → 流式分支` |
| ③ `UpstreamInvoker` 暴露流式接口 | `UpstreamInvoker.java` | 新增 `invokeStream(server, tool, request, credentials) → Flux<UpstreamChunk>`，不复用现有 `readBody` 的 `ByteArrayOutputStream` 缓冲逻辑 |
| ④ `ToolCallService` 流式装配 | `ToolCallService.java` | 新增 `callStream(server, tool, arguments) → Flux<JsonRpcMessage>`，把 `Flux<UpstreamChunk>` 映射为 progress notification + 最终 result |
| ⑤ `McpEndpointController` 流式响应 | `McpEndpointController.java` 第 96-170 行 | `post()` 返回类型从 `Mono<ResponseEntity<String>>` 扩展为支持 `text/event-stream`；流式分支用 `Flux<ServerSentEvent>` 或手动拼 SSE 帧 |
| ⑥ 取消传播 | 新增 | 监听响应流取消 → 取消上游 WebClient 订阅 |
| ⑦ 缓冲上限放宽 | `WebClientConfig` 第 41-51 行、`ExecutorProperties` `maxResponseBytes` | 流式分支不受 1MB 限制，改用流式专用阈值（如 16MB 累积上限） |

### 4.3 协议层（mcp-common）

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 新增 `notifications/progress` 方法常量 | `McpMethods.java` | 当前未声明此方法（P0 不发 progress） |
| 新增 SSE 帧工具类 | 新文件 `SseFrame.java` | 封装 `event: message\ndata: <json>\n\n` 格式 |
| `CallToolResult` 无需改 | — | `resultType` 已支持 `complete`，流式终态用它 |

### 4.4 不受影响的部分

- `ProtocolGuard`（协议守卫）：流式请求同样是 2026-07-28，守卫逻辑不变。
- `RestRequestBuilder` / `RestRequest`：请求构造与流式无关。
- `CircuitBreakerRegistry`（熔断）：流式期间 5xx 计失败，逻辑复用；但「流中途断开」是否计失败需单独判定（见 §5 风险）。
- 负载均衡 `pickBaseUrl`：流式分支复用同一选址逻辑。
- `UpstreamSnapshot` / `weights`：与流式无关。

## 5. 风险与开放问题

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | `notifications/progress` 的 `message` 字段语义是「进度信息」而非「数据内容」，客户端可能不把它当工具输出交给 LLM | 流式体验打折——客户端可能只在 UI 显示 message，不喂给模型 | 在 `tools/list` 的 tool description 里声明「此 tool 为流式，progress message 即为增量输出」；文档明确告知消费方 |
| R2 | 客户端断开（SSE 流关闭）后，上游 chunk 丢失，不续传 | 数据不完整 | 规范明确不支持 `Last-Event-ID`；流式语义本就是「实时消费」，不做断点续传。文档告知消费方 |
| R3 | 上游 chunk 频率过高打爆 SSE 流 | 网络负载、客户端处理不过来 | progress 频率限制（可配，默认每 chunk 一条）；chunk 文本超 4KB 拆多条 |
| R4 | 熔断判定与流式语义冲突——流式长连接期间，什么算「失败」？ | 熔断误打开或误关闭 | 流式分支：连接建立失败计失败；流途中 5xx 计失败；客户端主动取消不计失败 |
| R5 | 反向代理缓冲吞 chunk（nginx 默认 `proxy_buffering on`） | 客户端收不到实时 chunk | 发 `X-Accel-Buffering: no` 头（规范建议）；部署文档强调反代配置 |
| R6 | 最终 result 的完整拼接可能很大（上游总量不可控） | 内存压力 | 累积上限阈值（默认 16MB），超限标记 `isError` + 截断，不失败 |
| R7 | 规范未来版本可能引入原生流式 result（如 `resultType: "streaming"`） | 当前方案是工程权衡，非规范原生 | 关注 spec 演进；方案设计为可替换的映射层，未来规范演进时只改映射不改传输 |

## 6. 验收标准（M2 GA 候选）

Spike 完成后，P1 实现需通过以下验收：

1. **协议合规**：流式响应严格遵循 2026-07-28 Streamable HTTP 规范（`text/event-stream` + 0..N progress + 1 final response）。
2. **端到端**：注册一个 SSE 流式上游 Swagger（如模拟 LLM 流式输出端点）→ 发布 → MCP 客户端 `tools/call` 收到 SSE 流 + 最终 result。
3. **取消传播**：客户端中途关闭流，Executor 取消上游订阅，不继续发 chunk。
4. **回退**：客户端 `Accept` 不含 `text/event-stream` 时，退化为单 JSON 响应，功能不丢。
5. **错误**：上游 5xx / 超时 / 连接失败，流中发 JSON-RPC error 终止流，不静默挂起。
6. **不变量**：Modern-only 守卫不退化；原始 Swagger 只读 + sha256 不退化；Executor 无状态不引入会话依赖。

## 7. 下一步

本报告为 PRD §11 M0 硬任务「RT-1 Spike」的交付物。结论定后，进入 ADR-0003 撰写（决策记录）与 P1 实现规划。

---

## 附录 A：关键文件清单（探查结果）

| 文件 | 绝对路径 | 关键行 |
| --- | --- | --- |
| 流式拒绝点（tools/list 剔除） | `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/McpDispatcher.java` | 108-113 |
| 流式拒绝点（tools/call 501） | 同上 | 146-152 |
| HTTP 入口 + 响应装配 | `mcp-executor/src/main/java/com/mcpbridge/executor/web/McpEndpointController.java` | 96-170, 210 |
| 结果装配 | `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/ToolCallService.java` | 57-99 |
| 协议守卫 | `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/ProtocolGuard.java` | 40-87 |
| 上游调用（缓冲式） | `mcp-executor/src/main/java/com/mcpbridge/executor/upstream/UpstreamInvoker.java` | 89, 158-189, 261-277 |
| 熔断 | `mcp-executor/src/main/java/com/mcpbridge/executor/upstream/CircuitBreakerRegistry.java` | 15-19, 65-125 |
| 配置（1MB 上限） | `mcp-executor/src/main/java/com/mcpbridge/executor/config/ExecutorProperties.java` | 85 |
| WebClient 配置 | `mcp-executor/src/main/java/com/mcpbridge/executor/config/WebClientConfig.java` | 41-51 |
| Tool 快照模型 | `mcp-common/src/main/java/com/mcpbridge/common/snapshot/ToolSnapshot.java` | 28-42 |
| Server 快照模型 | `mcp-common/src/main/java/com/mcpbridge/common/snapshot/ServerSnapshot.java` | 35-62 |
| Upstream 快照 | `mcp-common/src/main/java/com/mcpbridge/common/snapshot/UpstreamSnapshot.java` | 20-40 |
| 协议常量 | `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpProtocol.java` | 15-32 |
| 方法名 | `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpMethods.java` | — |
| 错误码 | `mcp-common/src/main/java/com/mcpbridge/common/jsonrpc/JsonRpcErrorCodes.java` | 11-30 |

## 附录 B：规范引用

| 章节 | URL |
| --- | --- |
| Streamable HTTP transport | https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http |
| Transports overview | https://modelcontextprotocol.io/specification/2026-07-28/basic/transports |
| Patterns overview | https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns |
| Progress notifications | https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/progress |
| Logging (deprecated) | https://modelcontextprotocol.io/specification/2026-07-28/server/utilities/logging |
| Tools | https://modelcontextprotocol.io/specification/2026-07-28/server/tools |
| Tasks extension | https://modelcontextprotocol.io/extensions/tasks/overview |
