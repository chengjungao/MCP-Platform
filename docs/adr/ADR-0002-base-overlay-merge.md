# ADR-0002：base ⊕ overlay 生效模型与锚点挂起区

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-03 |
| 相关需求 | BR-2（覆盖机制，核心卖点）、REG-03（重新解析）、SVR-01/02 |
| 相关文档 | PRD §5.3 |

## 背景

平台的核心卖点不是「能把 Swagger 转成 MCP」——单文件转换器已经很拥挤——而是**治理链路**：
原始文档只读留存，用户在其上做覆盖精修，文档升级后覆盖不能丢，且任何时候都能回答
「现在生效的这个 tool 定义，哪些来自文档、哪些是人改的」。

这四条要求互相拉扯：

1. **原始 Swagger 永不改动**（需求硬约束，代码层做只读 + sha256 双保险）。
2. 用户改过的字段要**持久生效**，且不能被下一次文档升级冲掉。
3. 文档升级后，用户改的那个接口**可能已经不存在了**——此时不能静默丢弃用户的配置。
4. UI 必须能展示「原始 vs 生效」差异（BR-2 明确要求）。

如果只存「最终生效的那一份」，第 2 与第 4 条都无法满足：升级文档时无从区分某个值是文档带来的
还是人改的，于是只能在「覆盖用户编辑」与「拒绝文档更新」之间二选一，两个都不可接受。

## 决策

**采用三层模型 + 稀疏覆盖层：`原始文档 → base → overlay → effective`，overlay 只记录被改过的键。**

```
原始文档（不可变，sha256 校验）
      │ 解析
      ▼
   base      ← 每次重新解析整体重建
      │
      ⊕ overlay  ← 稀疏：只含用户改过的键，未出现的键一律回落 base
      ▼
  effective  ← 生效模型，进入发布快照
```

### 存储形态

| 层 | Server | Tool |
| --- | --- | --- |
| 原始文档 | `api_registration.raw_doc` + `raw_doc_sha256` | 同左（1 注册 = 1 Server，BR-1） |
| base | `mcp_server.base_model`（jsonb） | `mcp_tool.base_name` / `base_summary` / `base_description` / `base_input_schema` |
| overlay | `mcp_server.overlay`（jsonb） | `mcp_tool.overlay`（jsonb） |
| effective | **写回实体列**（`name` / `title` / `description` / `path_segment` / `list_ttl_ms`） | **读时计算**（`OverlayService.toSnapshot` / `toToolView`） |

Server 的生效值落列，是因为 `path_segment` 需要数据库唯一约束、需要参与端点拼接与发布前校验；
Tool 的生效值读时计算，是因为它是快照的一部分，不需要被 SQL 直接过滤。这个不对称是有意的。

### 锚点与挂起区

覆盖的定位锚点是 `METHOD path`（`ToolNames.anchor`），**锚点本身不可覆盖**——它是 base 与
overlay 之间唯一的对应关系，一旦可改，重新解析时就无法判断该把覆盖挂到哪个 tool 上。

文档升级后按锚点对账（`OverlayService.reconcileAnchors`）：

| 情况 | 结果 |
| --- | --- |
| 覆盖为空 | `OverlayStatus.NONE` |
| 锚点仍存在于新文档 | `OverlayStatus.ACTIVE`；若之前是挂起态则计入 `restored` |
| 锚点在新文档中消失 | `OverlayStatus.SUSPENDED`，计入 `suspended` |

挂起的覆盖**不进生效模型、不进发布快照**，但一条都不删。重新解析的响应里返回
`suspended` / `restored` 两个锚点列表（REG-03），UI 用红色提示要求用户处理。
接口被改名或删除是常事，把用户的精修配置静默蒸发掉，比让他在界面上看到一条待处理项糟糕得多。

### 可覆盖字段集合受限（防越界）

| 层 | 可覆盖 | 不可覆盖 |
| --- | --- | --- |
| Server | `name` / `title` / `description` / `pathSegment` / `listTtlMs` | `version`、`baseUrls`（走上游配置）、`protocolVersion` |
| Tool | `name` / `description` / `inputSchema` / `streaming` / `streamFormat` | `method` / `path` / `anchor`（锚点稳定性）、`requestBodyRequired`、`idempotent` |

`enabled`（tool 启停）**不是覆盖字段，而是实体列**。它表达的是运维意图而非「对文档的修正」，
因此不参与 base 重建，也不受锚点对账影响——文档升级不会把一个被人为停用的 tool 悄悄打开。

### 写入语义：`null` 与空串不同

这是本决策最容易踩坑的部分，必须在 API 契约里写明：

| 传值 | 含义 |
| --- | --- |
| 字段缺失 / `null` | **不修改**（保持现有覆盖或无覆盖状态） |
| `""`（空串） | **清除该字段的覆盖**，回落 base |
| `inputSchema` 为 JSON `null` 或 `{}` | 清除 inputSchema 覆盖 |
| `inputSchema` 为非对象（数组、标量） | 400 `VALIDATION_FAILED` |

覆盖层清空后回到 `OverlayStatus.NONE`，`overlay` 列置 null——「用户没有任何修改意图」是一个
需要能被表达的状态，否则差异视图上永远挂着一堆与 base 完全相同的伪改动。

## 被拒方案

**方案 A：只存生效模型（写入时扁平化）。**
拒绝。理由见「背景」：无法区分值的来源，文档升级与用户编辑必然冲突，且 BR-2 要求的差异视图
无从生成（只能靠与原始文档重新解析一遍再比对，成本高且随解析规则演进而漂移）。

**方案 B：JSON Patch（RFC 6902）操作序列。**
PRD 原文写的是「结构化 JSON Patch / 字段级差异」，本决策选了后者。拒绝 Patch 的理由是它
**顺序相关且对 base 结构敏感**：`add /tools/3/x` 这类基于数组下标的 op，在文档升级导致数组
元素增删后会落到错误位置；两个 patch 的合并也不满足幂等。稀疏键值覆盖天然幂等，同一份覆盖
无论应用多少次结果都一样。

**方案 C：对嵌套对象（`inputSchema`）做深度合并。**
拒绝。部分合并 JSON Schema 会产生无法解释的结果——用户改了一个 `properties.foo.type`，
深度合并后 `required` 数组来自 base、`properties` 来自两边拼接，这个 schema 到底在描述什么，
没有人说得清。`inputSchema` 采用**整体替换**：要么完全用用户的，要么完全用文档的。
这也解释了为什么 `{}` 被定义为「撤销覆盖」而不是「一个空 schema」——后者没有实际意义
（没有任何参数的 tool 极少，且 base 里已有 `emptyObjectSchema()` 兜底）。

**方案 D：锚点消失时静默丢弃覆盖。**
拒绝。这是用户不可见的数据丢失，最坏的一类 bug。挂起区的成本是 UI 上多一个状态和一条红色
提示，收益是「平台从不偷偷扔掉你的配置」这个可被信任的承诺。

## 后果

**正面：**

- 重新解析是安全操作：base 整体重建，overlay 按锚点保留，用户编辑与文档更新互不干扰。
- 差异视图是 overlay 的直接投影（`OverlayService.diff`），不需要重算，也不需要额外存储。
- 审计可以记录字段级 `from` / `to`，而不是「有人改了这个 Server」。
- `OverlayStatus` 让「无覆盖 / 有覆盖 / 覆盖待处理」三态在列表页一眼可见。

**负面：**

- `null` 与 `""` 的语义差异对 API 调用方不直观。**原样回显生效值再提交，会凭空造出一条与 base
  完全相同的覆盖**，把 `overlayStatus` 从 NONE 变成 ACTIVE。前端因此只提交用户真正改过的字段
  （`mcp-manager-ui/src/utils/form.ts` 的 `changed()`）。
- Tool 的生效名不是数据库列，无法加唯一约束，只能在发布前校验（`PublishService.requirePublishable`
  检查生效名重复）。这意味着冲突发现得比较晚——编辑时不报错，发布时才报错。
- 挂起的覆盖会累积。没有自动清理策略（有意为之），依赖 UI 提示与运维处理。
- `inputSchema` 整体替换意味着「只想改一个字段类型」的用户必须粘贴完整 schema。这是可解释性
  与便利性之间的取舍，本决策选了可解释性。

## 相关代码

- `mcp-manager/src/main/java/com/mcpbridge/manager/service/OverlayService.java`
- `mcp-manager/src/main/java/com/mcpbridge/manager/service/RegistrationService.java`（重新解析 + 对账）
- `mcp-manager/src/main/java/com/mcpbridge/manager/service/ServerService.java`（写入语义）
- `mcp-manager/src/main/java/com/mcpbridge/manager/service/PublishService.java`（发布前校验）
- `mcp-manager/src/main/java/com/mcpbridge/manager/domain/OverlayStatus.java`
- `mcp-common/src/main/java/com/mcpbridge/common/util/ToolNames.java`（锚点生成）
- `mcp-manager-ui/src/utils/form.ts`（前端提交裁剪）
