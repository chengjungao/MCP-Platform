# mcp-manager-ui

控制面控制台：Vue 3 + TypeScript + Vite + Pinia + Element Plus。

## 本地开发

```bash
npm install
npm run dev          # http://localhost:5173
```

开发服务器把 `/api` 代理到 `http://localhost:8080`。要指向别的 Manager：

```powershell
$env:VITE_PROXY_TARGET = 'http://10.0.0.8:8080'; npm run dev
```

默认账号 `admin` / `admin123`（由 Manager 首启 bootstrap 写入，见 `MANAGER_ADMIN_PASSWORD`）。

## 构建与检查

```bash
npm run build        # 产出 dist/
npm run type-check   # vue-tsc 全量类型检查（不产出文件）
```

`build` 刻意**不**串 `type-check`：类型报错不该阻断一次可运行的产物，
但提交前两个都要过。

## 页面

| 路径 | 页面 | 对应需求 |
| --- | --- | --- |
| `/login` | 登录 | MGM-01 |
| `/` | 概览 | — |
| `/registrations` | 注册与解析（URL / 上传 / 粘贴、诊断列表、原始文档查看） | REG-01、REG-03 |
| `/servers` | Server 列表 | SVR-01 |
| `/servers/:id` | Server 详情：基本信息、上游策略、Auth-B / Auth-D、Tool 列表与覆盖编辑、原始 vs 生效差异、发布 | SVR-01~07、BR-2、BR-4、EXE-07 |
| `/clusters` | 集群与节点 | CLU-01、PUB-02 |
| `/departments` | 部门 | MGM-04 |
| `/roles` | 角色与权限点 | MGM-02 |
| `/users` | 账号 | MGM-03 |
| `/audits` | 审计日志 | SEC-03 |

菜单与按钮按 `GET /api/v1/auth/me` 返回的权限点渲染，权限点清单见后端 `PermissionCatalog`。
**前端隐藏只是体验，不是安全边界**——真正的鉴权在 Manager 的 `@PreAuthorize` 与
`DepartmentScope` 上，越权请求一律 403。

## 约定

- API 一律走 `src/api/http.ts`：自动带 `Authorization`，自动拆 `ApiResponse` 信封，
  401 统一清凭据跳登录。业务代码里不要直接 `import axios`。
- 后端返回的 `details` 是结构化失败原因（如解析诊断里的字段与缺失项），
  报错时优先展示 `details`，而不是只显示一句 `message`。
- Auth-B 的密钥**永远不回显**：后端只给 `maskedPreview`，表单留空表示「不修改」。
  不要为了「方便」把掩码填回输入框。