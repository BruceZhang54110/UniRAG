# Implementation Plan: RAG 服务首页（混合式提问台与知识导航）

**Branch**: `009-rag-service-home` | **Date**: 2026-10-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-rag-service-home/spec.md`

## Summary

在现有 Spring Boot 多租户 RAG 后端仓库中新增独立前端工程 `web/`（Vite + React 19 + TypeScript + `@lobehub/ui`），实现"提问台为主体 + 一层知识导航"的混合式服务首页：用户打开即见提问输入框，提交后进入问答上下文；下方展示最近更新文档与上传引导；全程不出现任何租户信息，不需要登录。

技术要点来自三轮只读调研：

- **租户隐式注入**：后端 `TenantContextInterceptor` 强制 `/api/**` 携带 `X-Tenant-Code`，而产品要求用户无感。前端由部署级配置（`VITE_DEFAULT_TENANT`）静默注入，管理员可用 `?tenant=` 覆盖，界面永不展示。
- **现有接口足以支撑首页**：文档列表/详情/下载与一次性上传均以实现在案，首页只读复用，不新增后端数据来源。
- **问答页为占位**：spec 006 的检索问答接口尚未实现，本期 `/chat` 只承接问题并如实标注能力未就绪，不伪造答案。
- **开发期零后端改动**：Vite `server.proxy` 规避后端无 CORS 的问题。

## Technical Context

**Language/Version**: TypeScript 5.x、React 19、Node.js v24.15.0（本机实测）

**Primary Dependencies**: Vite 7（Node 24 满足要求）、`@lobehub/ui@^5.57`（ESM only，`engines.node >=22.22`）、其硬性 peer 依赖 `antd@^6.1.1`、`motion@^12`、`@lobehub/icons@^5`、`@lobehub/fluent-emoji@^4`、`react-router@^7`、`lucide-react`。**不引入** TanStack Query（首页仅 2 个只读请求，无缓存与轮询需求）。

**Storage**: 浏览器 `localStorage`（主题模式、最近对话）与 `sessionStorage`（租户编码覆盖值）；服务端仍为既有 PostgreSQL，本期不改动任何表结构

**Testing**: Vitest + `@testing-library/react` + jsdom（前端单元/组件测试，`npm test`）；类型检查 `tsc --noEmit`；后端既有 JUnit 5 + MockMvc 测试不受影响

**Target Platform**: 现代桌面浏览器（Chromium / Firefox / Safari 最近两个版本）与移动浏览器；开发机为 macOS

**Project Type**: Web application —— 在既有后端仓库中新增前端模块（`web/`），后端代码不做修改

**Performance Goals**: 首屏 2 秒内可见可用提问输入框（SC-002）；首屏 JS（gzip）预算 ≤ 400 KB；提问到提交 ≤ 3 次交互、≤ 15 秒（SC-001）

**Constraints**:
- 后端**无 CORS 配置**，开发期必须走 Vite 代理
- 所有 `/api/**` 请求必须携带 `X-Tenant-Code`，但界面不得出现租户信息
- 一次性上传仅接受 ≤ 10 MiB、`.txt`/`.md`/`.markdown`；更大文件需分块接口（本期不实现）
- 检索问答接口（spec 006）不存在，`/chat` 只能占位
- 本机未安装 pnpm（可用 `corepack` 启用），默认使用 npm
- `@lobehub/ui` core 组的 `Button`/`Tag`/`Text`/`ActionIcon` 已废弃，新代码统一使用 `@lobehub/ui/base-ui` 的同名组件

**Scale/Scope**: 1 个首页 + 1 个问答占位页 + 3 个占位路由 + 1 个最小上传弹层 + 1 个文档详情抽屉；预计 ≤ 25 个源文件、≈ 10 个自研组件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 目前是**未填充的模板**（全部为 `[PRINCIPLE_N]` 占位符），未经过批准（无 Ratified 日期、无版本）。因此不存在可违反的项目级原则，**门禁判定：PASS（无约束可检）**。

沿用周边既有约定作为事实标准：

| 检查项 | 结论 |
|---|---|
| 后端代码是否必须改动 | 否——纯新增前端模块，符合最小侵入 |
| 是否引入与既有栈冲突的技术 | 否——前端独立于 Java/Spring 栈 |
| 是否违反仓库既有构建约定 | 否——`pom.xml` 与 Maven 流程不变，前端另建 npm 工程 |
| 是否需要新增被忽略的构建产物规则 | **是**——`.gitignore` 未忽略 `node_modules/`，且 `dist` 仅根锚定忽略，需补规则 |
| 是否有未解决的 NEEDS CLARIFICATION | 否——所有技术未知项已在 Phase 0 解决 |

**重检（Phase 1 设计后）**：PASS，结论不变。设计未引入额外依赖或后端改动。

## Project Structure

### Documentation (this feature)

```text
specs/009-rag-service-home/
├── plan.md              # 本文件
├── research.md          # Phase 0：决策与依据
├── data-model.md        # Phase 1：前端数据模型与状态机
├── quickstart.md        # Phase 1：端到端验证指南
├── contracts/
│   ├── backend-api-consumption.md   # 后端接口消费契约（含字段陷阱）
│   └── ui-contract.md               # 路由/状态/交互契约（含"禁止出现租户"检查项）
├── checklists/
│   └── requirements.md  # spec 质量清单（已通过）
├── spec.md
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
web/                                    # 新增前端工程（本期唯一新增目录）
├── index.html
├── package.json
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts                      # dev proxy /api → http://localhost:8080
├── vitest.config.ts
├── .env.example                         # VITE_DEFAULT_TENANT / VITE_API_BASE
├── .env.development                     # 本机默认租户（git 忽略可覆盖项）
└── src/
    ├── main.tsx                         # 挂载：ConfigProvider > ThemeProvider > RouterProvider
    ├── app/
    │   ├── router.tsx                   # 路由表（/、/chat、占位路由）
    │   ├── Frame.tsx                    # ConsoleShell 框架：品牌区 + 侧栏 + 顶栏工具
    │   ├── nav.ts                       # 侧栏导航配置
    │   └── theme.tsx                    # 明暗模式状态 + ThemeSwitch 接线
    ├── api/
    │   ├── client.ts                    # fetch 封装：baseURL、X-Tenant-Code 注入、{data} 解包、错误归一
    │   ├── errors.ts                    # 后端错误码 → 中文文案映射
    │   ├── documents.ts                 # 列表/详情/下载/一次性上传
    │   └── tenant.ts                    # 租户解析（构建配置 + URL 覆盖），不对外暴露
    ├── features/
    │   ├── home/
    │   │   ├── HomePage.tsx             # 页面装配与视图状态调度
    │   │   ├── Greeting.tsx             # 时段问候 + 动态副标题
    │   │   ├── AskBox.tsx               # 提问框（输入、快捷键、禁用态、提交）
    │   │   ├── SuggestionChips.tsx      # 建议问题
    │   │   ├── RecentDocuments.tsx      # 最近更新列表 + 浏览全部入口
    │   │   ├── EmptyOnboarding.tsx      # 空库三步引导
    │   │   ├── ActionCard.tsx           # 上传入口卡片
    │   │   ├── RecentChats.tsx          # 最近对话（无数据不渲染）
    │   │   └── state.ts                 # 知识库视图状态推导（纯函数）
    │   ├── upload/
    │   │   ├── UploadDialog.tsx         # 最小一次性上传（≤10 MiB）
    │   │   └── progress.ts              # XHR 上传进度封装
    │   ├── chat/
    │   │   └── ChatPlaceholderPage.tsx  # /chat 占位：承接问题 + 能力未就绪说明
    │   └── placeholders/
    │       ├── DocumentsPlaceholder.tsx # /documents 占位
    │       └── JobsPlaceholder.tsx      # /jobs 占位
    ├── components/
    │   ├── DocumentDrawer.tsx           # 文档详情抽屉（首页内查看）
    │   ├── DocumentStatusTag.tsx        # 状态 → 颜色/文案映射
    │   ├── ErrorBanner.tsx              # 服务不可用告警条 + 重试
    │   └── RelativeTime.tsx             # epoch 毫秒 → 相对时间
    ├── hooks/
    │   ├── useHomeOverview.ts           # 文档列表请求 + 状态推导
    │   ├── useRecentChats.ts            # localStorage 会话摘要读写
    │   └── useHotkey.ts                 # 全局聚焦输入框快捷键
    ├── content/
    │   └── zh-CN.ts                     # 全部中文文案集中定义
    └── styles/
        └── global.css

# 后端：不改动（src/main/**、pom.xml 均无变化）
# 仓库根：仅更新 .gitignore（新增 node_modules/、web/dist/、*.local 等规则）
```

**Structure Decision**: 采用"后端仓库内新增独立前端模块"结构（`web/`），而非把前端产物塞进 `src/main/resources/static`。理由：① 后端当前无静态资源目录与 CORS 配置，塞进去需要改后端；② 独立工程可用 Vite 代理规避跨域，开发体验最好；③ 部署形态（nginx 静态托管或后续由 Spring 托管）留待部署决定，不污染本期实现。后端保持零改动是本期硬约束。

## Complexity Tracking

> 无 Constitution Check 违规项，本节不适用。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
