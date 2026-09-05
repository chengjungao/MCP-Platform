# ADR-0003：流式桥接采用 Streamable HTTP SSE + 缓冲兜底

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-09-05 |
| 决策编号 | RT-1 Spike 结论 |
| 相关需求 | BR-5（P1）、EXE-06（P1，受 RT-1 约束）、R4 |
| 相关文档 | PRD §6 BR-5、§11 M0/M2、[RT-1 Spike 报告](../spike/RT-1-streaming-bridge.md)、ADR-0001 |

## 背景

PRD BR-5 要求「上游流式 REST → MCP 调用结果同样流式」，但把技术风险 RT-1 标为 M0 硬任务：
2026-07-28 规范移除了持久的服务器→客户端 SSE 通道（server 端请求重构为 MRTR），「REST 流式 →
MCP 增量输出」的协议层落地形态需要 Spike 验证。P0 阶段流式 tool 从 `tools/list` 剔除，
`tools/call` 返回 501 + `-32002`，不做承诺。

Spike 调研（见 [RT-1 报告](../spike/RT-1-streaming-bridge.md)）确认：

1. **规范原生支持单 POST 回 SSE 流**——Streamable HTTP 传输层允许服务器对单个请求返回
   `text/event-stream`，帧结构是「0..N 个 request-scoped notification + 1 个最终 response」。
2. **但规范没有「优雅承载工具增量数据」的原生机制**——`CallToolResult` 是终态单结果对象
   （`resultType: "complete" | "input_required"`，无 `streaming`/`partial`）；流中可发的
   notification 只有 `notifications/progress`（无 `data` 字段，`message` 是人类可读字符串）
   和 `notifications/message`（**已废弃**，SEP-2577，新实现不应采用）。

结论：流式透传不存在「照抄某个规范方法」的解法，必须做工程权衡。

## 决策

**流式 tool 的 `tools/call` 采用 Streamable HTTP SSE 流式响应（路径①），客户端不支持 SSE 时
自动回退为缓冲完整结果的单 JSON 响应（路径③）。不采用 Tasks 扩展（路径②）。**

具体含义：

1. **响应形态判定**：`tool.streaming() == true` 且客户端 `Accept` 头含 `text/event-stream`
   → 走 SSE 流；否则走现有缓冲路径。
2. **chunk 映射**：上游每个数据块映射为一条 `notifications/progress`，`progressToken` 绑定
   请求 id（客户端在 `_meta.progressToken` 提供，未提供时由 Executor 兜底生成），
   `progress` 单调递增（chunk 序号），`message` 塞 chunk 文本（超 4KB 拆多条）。
3. **终态交付**：流结束发最终 `CallToolResult`（`resultType: "complete"`），`content[0].text`
   是完整拼接的所有 chunk——给保守客户端一个完整交付物，流式客户端也可选只取最终 result。
4. **错误终止**：上游 5xx / 超时 / 连接失败 → 流中发一条 JSON-RPC error response 终止流，
   不静默挂起。
5. **取消传播**：客户端关闭 SSE 流 = 取消信号（规范语义），Executor 监听 `Flux.doOnCancel`
   取消上游 `WebClient` 订阅，不继续发 chunk。
6. **缓冲上限**：流式分支不受现有 1MB `maxResponseBytes` 限制，改用流式专用累积上限
   （默认 16MB），超限标记 `isError: true` + 截断提示，不失败。
7. **反代缓冲**：SSE 响应发 `X-Accel-Buffering: no` 头（规范建议），部署文档强调反代配置。

## 被拒方案

**方案 A：Tasks 扩展承载流式（路径②）。**
拒绝。`io.modelcontextprotocol/tasks` 面向「分钟/小时级异步作业 + 轮询」，核心是
`CreateTaskResult` + `tasks/get` 轮询——**没有流式数据通道**，`tasks/get` 返回的是任务状态
快照不是数据流。流式透传场景是「秒级、边收边出」，用 Tasks 是语义错位。Tasks 真正有价值的
场景是上游是「提交作业 → 轮询状态」型异步 API（视频转码、批处理），这是 P2 增量，不在本次
范围。

**方案 B：用已废弃的 `notifications/message` 承载数据。**
拒绝。它的 `data` 字段能承载任意 JSON，技术上最适合塞 chunk，但规范明确标记 Deprecated
（SEP-2577），新实现 **SHOULD NOT** 采用。用它等于把流式透传绑在一个 12 个月后可能移除的
特性上。平台的 Modern-only 承诺（ADR-0001）要求不背技术债，不用废弃特性是同一根红线。

**方案 C：自定义 notification 方法（如 `notifications/x-chunk`）。**
拒绝。规范没有自定义 notification 的官方机制，且即使技术上能发，客户端也不会识别——这等于
造私有协议，违背 MCP 互操作性目标。平台的定位是「桥接」，不是「定义新协议」。

**方案 D：只做缓冲兜底（路径③），不做流式。**
拒绝作为唯一方案，但保留为回退。纯缓冲兼容性最广，但流式体验归零——PRD US-09 明确要求
「边收边出」，不做流式等于放弃 BR-5 的核心价值主张。保留为「客户端不支持 SSE 时的自动回退」
而非主路径。

## 后果

**正面：**

- 流式 tool 从 `tools/list` 恢复可见，从「列出来却调不通」变成「列出来且能流式调用」。
- 协议合规：SSE 流严格遵循 2026-07-28 Streamable HTTP 规范，不依赖任何废弃特性。
- 客户端兼容：规范要求客户端 **MUST** 同时支持 `application/json` 和 `text/event-stream`，
  主路径覆盖合规客户端；非规范客户端自动回退，功能不丢。
- 终态交付完整：最终 result 含完整拼接内容，保守客户端也能拿到全量数据。

**负面（已作为风险接受）：**

- **R1：`notifications/progress` 的 `message` 字段语义是「进度信息」而非「数据内容」。**
  客户端是否把 `message` 当工具输出交给 LLM，规范不规定，取决于客户端实现。缓解：在
  `tools/list` 的 tool description 里声明「此 tool 为流式，progress message 即为增量输出」，
  文档明确告知消费方。
- **R2：客户端断开后 chunk 丢失，不续传。** 规范明确不支持 `Last-Event-ID`。流式语义本就是
  「实时消费」，不做断点续传。文档告知消费方。
- **R3：上游 chunk 频率可能打爆 SSE 流。** 缓解：progress 频率限制（可配，默认每 chunk 一条），
  chunk 文本超 4KB 拆多条。
- **R4：熔断判定与流式语义冲突。** 流式长连接期间，「失败」判定需区分：连接建立失败计失败；
  流途中 5xx 计失败；客户端主动取消**不计**失败（否则会被客户端行为污染熔断统计）。
- **R5：反代缓冲吞 chunk。** `X-Accel-Buffering: no` 头 + 部署文档强调反代配置。
- **R6：最终 result 累积可能很大。** 16MB 上限 + 截断标记，不失败。
- **R7：规范未来可能引入原生流式 result。** 当前方案是工程权衡，映射层设计为可替换——
  未来规范演进时只改映射不改传输。

## 不变量（改动后必须守住）

本决策的代码改动不得退化以下三条（ADR-0001 的硬承诺）：

1. `-32022` 拒绝行为不退化——流式请求同样是 2026-07-28，守卫逻辑不变。
2. 原始文档只读 + sha256 校验不退化——流式只改运行时透传，不改注册解析。
3. Executor 无状态、不引入任何依赖会话的路由——SSE 流是 request-scoped，不引入会话存储。

## 何时重开这个决策

- 当 MCP 规范引入原生流式 result 类型（如 `resultType: "streaming"` 或类似机制）时，重新
  评估是否替换 `notifications/progress` 映射层。
- 当埋点显示主流客户端开始把 `notifications/progress` 的 `message` 当工具输出交给 LLM 时，
  R1 风险下降，可考虑移除 tool description 里的显式声明。
- 当 Tasks 扩展在客户端生态普及且出现「提交作业 → 轮询状态」型上游 API 桥接需求时，重启
  路径②评估（与流式透传是不同场景，不互斥）。

## 相关代码

- `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/McpDispatcher.java`（拒绝点 → 流式分支）
- `mcp-executor/src/main/java/com/mcpbridge/executor/web/McpEndpointController.java`（响应形态判定）
- `mcp-executor/src/main/java/com/mcpbridge/executor/mcp/ToolCallService.java`（流式装配）
- `mcp-executor/src/main/java/com/mcpbridge/executor/upstream/UpstreamInvoker.java`（流式接口）
- `mcp-common/src/main/java/com/mcpbridge/common/protocol/McpMethods.java`（新增 progress 常量）
- `mcp-common/src/main/java/com/mcpbridge/common/snapshot/ToolSnapshot.java`（streaming/streamFormat 字段）
