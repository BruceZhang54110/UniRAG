# UniRAG Web（服务首页）

`009-rag-service-home` 特性的前端工程：Vite + React 19 + TypeScript + `@lobehub/ui`。

当前交付**服务首页（提问台 + 知识导航）**，`/chat`、`/documents`、`/jobs` 为先占位路由。
后端保持零改动，本工程通过开发代理访问既有 `/api/v1` 接口。

## 环境要求

| 项 | 要求 |
|---|---|
| Node.js | ≥ 22.22（`@lobehub/ui` 的 `engines` 约束；实测 v24.15.0） |
| 包管理器 | npm（本项目未使用 pnpm） |
| 后端 | Spring Boot 服务运行在 `http://localhost:8080`，且本地 PostgreSQL 可连接 |

## 快速开始

```bash
cd web
npm install

# 配置默认租户（该文件不入版本库）
cp .env.example .env.local
# 编辑 .env.local：VITE_DEFAULT_TENANT=<租户编码>

npm run dev          # http://localhost:5173，/api 自动代理到 :8080
```

## 可用脚本

| 命令 | 作用 |
|---|---|
| `npm run dev` | 开发服务器（含 `/api` 代理） |
| `npm run build` | 类型检查 + 生产构建（产物在 `web/dist`） |
| `npm run preview` | 本地预览构建产物 |
| `npm run typecheck` | `tsc --noEmit` |
| `npm test` | Vitest（单元/组件测试） |
| `npm run lint` | 当前等价于 `typecheck`（未引入 ESLint，避免新增依赖） |

## 环境变量

| 变量 | 说明 |
|---|---|
| `VITE_DEFAULT_TENANT` | **必填**。部署级默认租户编码，由前端在请求头静默注入，界面不展示（见 `src/api/tenant.ts`） |
| `VITE_API_BASE` | 可选，API 前缀，默认 `/api` |

租户解析优先级：URL 查询参数 `?tenant=`（运维用，写入 `sessionStorage`）→ `sessionStorage` → `VITE_DEFAULT_TENANT`。
**界面中不得出现任何租户信息**，新增代码请遵守该约束。

## 目录结构

```
src/
├── main.tsx              # ConfigProvider → ThemeProvider → RouterProvider（顺序不可颠倒）
├── app/                  # 框架、导航、路由、主题
├── api/                  # 请求封装、错误码映射、租户解析、文档接口
├── components/           # 跨页面复用件（状态标签、相对时间、告警条、详情抽屉）
├── features/
│   ├── home/             # 首页：问候、提问框、建议问题、最近更新、空态引导、行动卡、最近对话
│   ├── upload/           # 最小一次性上传（≤10 MB）
│   ├── chat/             # `/chat` 问答上下文占位
│   └── placeholders/     # 文档库 / 任务中心占位页
├── hooks/                # useHomeOverview、useRecentChats、useHotkey
├── content/zh-CN.ts      # 全部中文文案（组件内不得内联中文）
└── styles/global.css     # 响应式与容器规则
```

## 设计约束（改动前请先读）

完整设计见 `../specs/009-rag-service-home/`：`plan.md`、`research.md`（D1–D17 决策）、
`data-model.md`（状态机与存储约定）、`contracts/`（接口与 UI 契约）、`quickstart.md`（验收步骤）。

- **后端零改动**：本工程不得修改 `src/main/**`、`pom.xml`、数据库迁移。
- **无租户 UI**：不出现租户名/编码/切换控件（可用 `npm test` 中的禁止项断言回归）。
- **文案集中**：所有面向用户的文案放在 `src/content/zh-CN.ts`。
- **体积纪律**：首页不要引入 `@lobehub/ui` 的 `Markdown`（shiki/katex/mermaid 会显著增大首屏包）。

## 部署提示

构建产物为 SPA，需 **SPA fallback**（未匹配路径回落到 `index.html`），否则刷新 `/chat` 会 404；
同时把 `/api` 代理到后端（同源可省去 CORS 配置）。
