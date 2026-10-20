---
description: "Task list for RAG 服务首页（混合式提问台与知识导航）"
---

# Tasks: RAG 服务首页（混合式提问台与知识导航）

**Input**: Design documents from `/specs/009-rag-service-home/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/、quickstart.md

**Tests**: 本清单包含**少量定向测试**（非 TDD）。依据：`plan.md` D15 明确规定测试策略，`contracts/ui-contract.md` §6 要求把"禁止出现租户元素"（SC-003）落为可自动断言项。未在 spec 中要求的完整测试套件不在此列。

**Organization**: 按用户故事分阶段，每个故事可独立实现与验收。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1–US4）
- 所有路径相对于仓库根目录

## Path Conventions

前端模块：`web/`（后端 `src/main/**` 与 `pom.xml` **不得改动**）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 建立前端工程骨架与工具链

- [x] T001 创建 `web/` Vite + React 19 + TypeScript 工程骨架，按 plan.md 的目录结构建立 `web/package.json`（依赖：`@lobehub/ui@^5.57`、`react@^19`、`react-dom@^19`、`antd@^6.1.1`、`motion@^12`、`@lobehub/icons@^5`、`@lobehub/fluent-emoji@^4`、`react-router@^7`、`lucide-react`）、`web/tsconfig.json`、`web/tsconfig.node.json`、`web/vite.config.ts`（含 `server.proxy` 将 `/api` 转发至 `http://localhost:8080`）、`web/index.html`（`lang="zh-CN"`、中文标题与 description）、`web/.env.example`（`VITE_DEFAULT_TENANT`、`VITE_API_BASE`）、`web/src/main.tsx`
- [x] T002 [P] 更新仓库根 `.gitignore`，新增 `node_modules/`、`web/dist/`、`*.local`、`.env.local`（plan D17；当前 `node_modules/` 未被忽略，`web/dist/` 不会命中根锚定的 `/dist/`）
- [x] T003 在 `web/package.json` 登记脚本 `dev`/`build`/`preview`/`typecheck`/`test`/`lint`，并新增 `web/vitest.config.ts` 与 `web/src/test/setup.ts`（jsdom + `@testing-library/react`）
- [x] T004 [P] 建立文案集中文件 `web/src/content/zh-CN.ts`（问候语、副标题模板、建议问题预置、空态三步引导、错误提示、上传结果文案）

**Checkpoint**: `cd web && npm install && npm run typecheck` 通过

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事共用的框架、API 层与共享组件

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [x] T005 实现主题与 Provider 装配：`web/src/app/theme.tsx`（`themeMode` 状态 + `localStorage` 持久化 + `auto` 经 `matchMedia` 解析 + `ThemeSwitch` 受控接线），并在 `web/src/main.tsx` 按 `ConfigProvider(motion)` → `ThemeProvider(customTheme: geekblue/grey)` → `RouterProvider` 顺序装配（plan D2/D12）
- [x] T006 [P] 实现租户解析 `web/src/api/tenant.ts`：三层优先级 `?tenant=` → `sessionStorage` → `VITE_DEFAULT_TENANT`，仅导出取值函数，**不导出任何可渲染内容**（plan D4）
- [x] T007 [P] 实现错误码映射 `web/src/api/errors.ts`：按 `contracts/backend-api-consumption.md` §5 全表映射为中文文案，`TENANT_*` 统一为"服务暂时不可用"且不提"租户"
- [x] T008 实现请求封装 `web/src/api/client.ts`：baseURL（`VITE_API_BASE`，开发期 `/api`）、注入 `X-Tenant-Code`、解包 `{data}`、将 `{error:{code,message}}` 归一为 `{code,message,status}`（依赖 T006、T007）
- [x] T009 实现文档接口层 `web/src/api/documents.ts`：列表（`page=1&size=6`）、详情、下载（`fetch`+`Blob`，文件名按 RFC 5987 解析并兜底）、一次性上传（multipart，进度走 XHR）；统一把 `createdAt`/`updatedAt` 归一为 epoch 毫秒数字；对 202 响应走显式降级分支（提示"已接收，正在后台处理"，不轮询）（依赖 T008；契约见 `contracts/backend-api-consumption.md`）
- [x] T010 [P] 实现 `web/src/components/DocumentStatusTag.tsx`：按 data-model §2.1 映射 `INGESTED`/`INGESTING`/`PARTIAL`/`FAILED` 的颜色与文案（使用 `@lobehub/ui/base-ui` 的 `Tag`）
- [x] T011 [P] 实现 `web/src/components/RelativeTime.tsx`：epoch 毫秒 → 相对时间（<30 天）/ 绝对日期，异常值显示"—"
- [x] T012 [P] 实现 `web/src/components/ErrorBanner.tsx`：`Alert`（error 语义）+ 重试按钮
- [x] T013 实现 `web/src/components/DocumentDrawer.tsx`：文档详情抽屉（含 `source` 原值展示，不宣称真实来源）（依赖 T009、T010、T011）
- [x] T014 实现框架 `web/src/app/Frame.tsx` 与导航配置 `web/src/app/nav.ts`：`ConsoleShell` + `ConsoleNav`，侧栏含首页/问答/文档库/任务中心，顶栏右侧仅主题切换（**不得出现租户相关元素**）
- [x] T015 实现路由 `web/src/app/router.tsx`：`/`、`/chat`、`/documents`、`/jobs`，未知路径回落首页，`/chat` 与占位页路由级懒加载（依赖 T014）
- [x] T016 [P] 实现占位页 `web/src/features/placeholders/DocumentsPlaceholder.tsx` 与 `web/src/features/placeholders/JobsPlaceholder.tsx`（"即将上线"语义，非空白页）
- [x] T017 实现首页状态推导纯函数 `web/src/features/home/state.ts`：视图状态（`loading|error|empty|processing|ready`，优先级 `error > empty > processing > ready`）与建议问题生成（2 条文档标题 + 2 条预置，共 4 条）
- [x] T018 实现数据 Hook `web/src/hooks/useHomeOverview.ts`：拉取列表并派生 `KnowledgeOverview`（`total`/`retrievable`/`processing`/`hasDocuments`），暴露 `{ state, overview, items, reload }`（依赖 T008、T017）
- [x] T019 [P] 实现快捷键 Hook `web/src/hooks/useHotkey.ts`：`/` 与 `Ctrl/Cmd+K` 聚焦提问框，输入状态下不抢占按键

**Checkpoint**: 框架可启动，路由可达，API 层可请求到后端（`GET /api/v1/documents` 返回 200）

---

## Phase 3: User Story 1 - 无前置步骤直接提问 (Priority: P1) 🎯 MVP

**Goal**: 打开首页即见提问框，输入并提交后进入问答上下文；全程无登录、无租户元素

**Independent Test**: 在已配置默认租户的环境中打开 `/`，可直接输入并提交问题并被带入 `/chat?q=...`；空内容不提交；DOM 中不含"租户"/租户编码

### Implementation for User Story 1

- [x] T020 [P] [US1] 实现 `web/src/features/home/Greeting.tsx`：时段问候（早/下午/晚）+ 动态副标题（文档数来自 overview，未登录用中性"你好"）
- [x] T021 [US1] 实现 `web/src/features/home/AskBox.tsx`：`ChatInputArea.Inner` + `ChatSendButton`（**不使用 `ChatInputArea` 外层**，其 `expand` 默认 `true`）；Enter 提交、Shift+Enter 换行、空值不提交且保持焦点、提交跳转 `/chat?q=<encodeURIComponent>`、禁用态给出原因文案、接线 T019 快捷键
- [x] T022 [US1] 实现 `web/src/features/chat/ChatPlaceholderPage.tsx`：读取 `?q=` 渲染用户消息（`ChatItem placement="right"`），呈现"检索问答能力尚未上线"说明，**不得生成任何模拟答案或引用**；`?q=` 缺失时引导返回首页
- [x] T023 [US1] 实现 `web/src/features/home/HomePage.tsx` 首屏装配：问候区 + 提问框 + 提交路径（依赖 T020、T021、T022、T018）

### Tests for User Story 1

- [x] T024 [P] [US1] 编写 `web/src/features/home/__tests__/AskBox.test.tsx`：空输入不提交、Enter 提交、Shift+Enter 换行、`empty`/`error` 状态下禁用
- [x] T025 [P] [US1] 编写 `web/src/features/home/__tests__/no-tenant-ui.test.tsx`：渲染首页各状态后断言 `document.body.textContent` 不匹配 `/租户|tenant/i` 且不含测试租户编码（SC-003）

**Checkpoint**: US1 可独立验收 —— 打开首页即可提问并被带入问答上下文

---

## Phase 4: User Story 2 - 浏览知识库里有什么 (Priority: P2)

**Goal**: 探索型用户能看到最近更新的文档并可查看详情，也能进入完整文档列表

**Independent Test**: 有文档时首页展示 ≤6 条最近更新（含图标、标题、相对时间），点击打开详情抽屉且 URL 不变，点击"浏览全部文档"进入 `/documents` 占位页

### Implementation for User Story 2

- [x] T026 [US2] 实现 `web/src/features/home/RecentDocuments.tsx`：最多 6 条，`FileTypeIcon` + 标题（>24 字符单行截断 + tooltip 全文）+ `RelativeTime` + `DocumentStatusTag`；点击打开 `DocumentDrawer`；底部"浏览全部文档 →"指向 `/documents`（依赖 T010、T011、T013）
- [x] T027 [US2] 在 `web/src/features/home/HomePage.tsx` 接入知识导航层：`loading` 用 `Skeleton`（**不阻塞提问框**）、`error` 用 `ErrorBanner`、`empty`/`processing` 分支按 T030/T031 处理
- [x] T028 [US2] 实现响应式规则（`web/src/features/home/HomePage.tsx` + `web/src/styles/global.css`）：≥1280 侧栏常驻、1024–1280 可折叠、768–1024 导航层上下堆叠、<768 最近更新显示 4 条；任一断点无横向滚动

### Tests for User Story 2

- [x] T029 [P] [US2] 编写 `web/src/features/home/__tests__/RecentDocuments.test.tsx`：条目数量上限、超长标题截断、点击打开抽屉、加载态渲染 Skeleton

**Checkpoint**: US1 与 US2 均可独立工作

---

## Phase 5: User Story 3 - 空知识库时的启动引导 (Priority: P2)

**Goal**: 空知识库下用户被明确引导上传，上传成功后回到首页即可提问（闭环）

**Independent Test**: 在空知识库租户下打开首页，提问被阻止且有三步引导与上传入口；上传一份 ≤10 MB 的 `.md` 后，最近更新出现该文档且提问恢复可用

### Implementation for User Story 3

- [x] T030 [US3] 实现 `web/src/features/home/EmptyOnboarding.tsx`：三步引导（上传 → 自动处理 → 提问）用 base-ui `Steps`，含上传入口按钮
- [x] T031 [US3] 实现 `web/src/features/home/ActionCard.tsx`：有文档时的行动卡（"上传新文档，让知识库更聪明" + 上传按钮）
- [x] T032 [US3] 实现 `web/src/features/upload/progress.ts`：XHR 上传封装，暴露进度回调与取消能力（`fetch` 不支持上传进度，plan D9）—— **实现位置调整**：该能力已由 T009 的 `api/documents.ts#uploadDocument` 提供（XHR + `onProgress` + `AbortSignal` 取消），不再另建 `progress.ts`，避免重复实现（详见交付说明）
- [x] T033 [US3] 实现 `web/src/features/upload/UploadDialog.tsx`：文件选择（`base-ui` `Upload` 拖拽）+ `docName`（默认文件名去扩展名，可编辑）+ 可选 `title`；类型/大小/名称长度前置校验（不合规就地提示、**不发起请求**）；进度显示；结果按 `INGESTED`/`NOOP`/`PARTIAL`/`FAILED` 给出对应文案与后续动作（依赖 T032、T009）
- [x] T034 [US3] 在 `web/src/features/home/HomePage.tsx` 完成空态联动：`empty` 时禁用提问并说明原因、替换导航层为引导、隐藏最近对话；上传成功后 `reload()` 使新文档可见且提问恢复（依赖 T030、T031、T033、T027）

### Tests for User Story 3

- [x] T035 [P] [US3] 编写 `web/src/features/upload/__tests__/UploadDialog.test.tsx`：非法类型/超大文件不发起请求、四种上传结果文案映射、202 降级提示不判为失败

**Checkpoint**: 空态引导闭环可验收（上传 → 可检索 → 提问恢复）

---

## Phase 6: User Story 4 - 建议问题与回访继续 (Priority: P3)

**Goal**: 降低冷启动门槛，并让回访用户一键继续

**Independent Test**: 有可检索文档时展示 4 条建议问题且点击即提交；有历史会话时展示最近对话并可恢复；无历史时该区块完全不渲染

### Implementation for User Story 4

- [x] T036 [US4] 实现 `web/src/features/home/SuggestionChips.tsx`：4 条建议问题（2 条来自可检索文档标题 + 2 条预置），点击与手动提交等价；不可提问时全部置灰并提示原因，**不提交**（依赖 T017、T021）
- [x] T037 [US4] 实现 `web/src/hooks/useRecentChats.ts`：`localStorage` 读写 `unirag.recent-chats`，最多 6 条、问题摘要截断 80 字符、脏数据解析失败时清空重置（依赖 T005 的存储约定）
- [x] T038 [US4] 实现 `web/src/features/home/RecentChats.tsx`：最多 6 条（摘要 + 相对时间），点击恢复会话（跳转 `/chat` 并带出原问题）；**无数据时整块不渲染**（依赖 T037）
- [x] T039 [US4] 在提交路径写入会话摘要（`web/src/features/home/HomePage.tsx` 接线 `useRecentChats.record`）（依赖 T037、T023）

### Tests for User Story 4

- [x] T040 [P] [US4] 编写 `web/src/features/home/__tests__/suggestions-and-chats.test.tsx`：建议问题生成规则与禁用规则、无历史时不渲染容器、localStorage 脏数据被清理

**Checkpoint**: 四个用户故事均可独立工作

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 边界状态、性能、无障碍与端到端验证

- [x] T041 完善边界状态：`processing` 提示"知识库正在处理中，部分问题可能没有结果"、`error` 禁用提问并区分于 `empty`（`web/src/features/home/HomePage.tsx`）
- [x] T042 [P] 分包与体积治理：路由级懒加载 + `web/vite.config.ts` 的 `manualChunks`（react / antd+lobehub），核对首屏 JS gzip ≤ 400 KB 且产物不含 `shiki`/`katex`/`mermaid` —— **偏差**：实测手动分包反而把整个 `@lobehub/ui` 拉进首屏（gzip 1463 KB 且预载 10 MB shiki 块），故**不启用 manualChunks**；实测首屏 gzip **566 KB，超出 400 KB 预算**（原因与后续选项见交付说明）
- [x] T043 [P] 无障碍核对（`web/src/features/home/AskBox.tsx`、`web/src/features/home/RecentDocuments.tsx`、`web/src/app/Frame.tsx`）：键盘可达、焦点可见（不关闭 `globalFocusRing`）、状态不只靠颜色、列表语义正确（另修复 `ThemeSwitch` 无无障碍名称：用 `role="group"` + `aria-label` 容器包裹）
- [ ] T044 按 `quickstart.md` §4 执行端到端验收（US1–US4 + 边界状态），逐项记录结果 —— **未完成**：DOM 层与接口层已逐项核对通过，但浏览器面板的视觉捕获显示页面仅有输入框可见、其余内容存在 DOM 却不绘制，无法据此判定视觉验收通过；需在真实浏览器中复核（详见交付说明）
- [x] T045 确认后端零回归（`pom.xml`、`src/main/java/**`、`src/main/resources/**`）：先 `git status` 确认上述路径无改动；在环境允许时运行 `./mvnw test`，若因本地 PostgreSQL 未就绪失败需如实标注（实测 `BUILD SUCCESS`，57 个测试全通过）
- [x] T046 [P] 在 `web/README.md` 记录启动与构建方式（`npm install`/`npm run dev`/`npm run build`）及 `VITE_DEFAULT_TENANT` 配置说明（不写入任何真实租户编码）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup —— **阻塞全部用户故事**
- **User Stories (Phase 3–6)**: 均依赖 Foundational
  - US1 (P1) → 独立可交付（MVP）
  - US2 (P2)、US3 (P2) → 依赖 Foundational；二者互相独立
  - US4 (P3) → 依赖 Foundational；其"点击即提交"复用 US1 的 `AskBox` 提交路径
- **Polish (Phase 7)**: 依赖目标用户故事完成

### User Story Dependencies

| 故事 | 依赖 | 说明 |
|---|---|---|
| US1 | 无（仅 Foundational） | MVP，可单独演示 |
| US2 | 无（仅 Foundational） | 与 US1 并行；接入点在 `HomePage.tsx` |
| US3 | 无（仅 Foundational） | 上传为独立模块；接线点在 `HomePage.tsx` |
| US4 | US1（提交路径） | 建议问题与最近对话复用提交动作 |

**文件冲突提示**：`web/src/features/home/HomePage.tsx` 被 T023/T027/T034/T039/T041 多次修改，**这些任务不可并行**。

### Within Each User Story

- 共享组件与 API 层先于页面装配
- 纯函数（`state.ts`）先于 Hook 与组件
- 实现先于测试落点（测试可直接针对已实现的纯函数与组件行为断言）

### Parallel Opportunities

- Phase 1：T002、T004 可并行
- Phase 2：T006、T007、T010、T011、T012、T016、T019 可并行（不同文件）
- Phase 3–6：各故事的测试任务（T024/T025、T029、T035、T040）互相并行
- Phase 7：T042、T043、T046 可并行

---

## Parallel Example: Foundational 阶段

```bash
# 可同时启动（不同文件、无相互依赖）：
Task: "实现 web/src/api/tenant.ts（D4 三层优先级，仅导出取值函数）"
Task: "实现 web/src/api/errors.ts（错误码→中文文案全表）"
Task: "实现 web/src/components/DocumentStatusTag.tsx（状态映射）"
Task: "实现 web/src/components/RelativeTime.tsx（epoch 毫秒→相对时间）"
Task: "实现 web/src/components/ErrorBanner.tsx（Alert + 重试）"
Task: "实现 web/src/features/placeholders/*.tsx（两个占位页）"
Task: "实现 web/src/hooks/useHotkey.ts（/ 与 Ctrl/Cmd+K）"
```

## Parallel Example: User Story 1 测试

```bash
Task: "web/src/features/home/__tests__/AskBox.test.tsx"
Task: "web/src/features/home/__tests__/no-tenant-ui.test.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup → Phase 2 Foundational（**阻塞项，必须完成**）
2. Phase 3 US1
3. **STOP & VALIDATE**：按 `quickstart.md` §4 的 US1 表格逐项验收（含 T025 的禁止项断言）
4. 此时已可演示"打开即提问"

### Incremental Delivery

1. Setup + Foundational → 框架就绪
2. + US1 → 演示（MVP）
3. + US2 → 演示（有内容可浏览）
4. + US3 → 演示（空库可自救，闭环）
5. + US4 → 演示（冷启动与回访）
6. Phase 7 → 边界状态、性能与端到端验收

### 交付前必须为绿

- `cd web && npm run typecheck` 零错误
- `cd web && npm test` 通过（含禁止项断言）
- `cd web && npm run build` 成功，首屏 JS gzip ≤ 400 KB
- `quickstart.md` §4 全部场景按预期通过（含 `error` 与 `empty` 的区分）

---

## Notes

- [P] = 不同文件、无未完成依赖
- 后端零改动是硬约束：任何任务都不得修改 `src/main/**`、`pom.xml`、数据库迁移
- 文案一律取自 `web/src/content/zh-CN.ts`，不得散落在组件内
- 每个任务完成后可提交；`web/src/features/home/HomePage.tsx` 相关任务需串行
- 避免：在组件内直接写租户取值逻辑（必须经 `api/tenant.ts`）、在首页引入 `Markdown`（见 plan D16）
