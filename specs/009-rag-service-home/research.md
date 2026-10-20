# Phase 0 Research: RAG 服务首页

**Feature**: `009-rag-service-home` | **Date**: 2026-10-11

本文记录实现计划中每个"需要先定下来"的技术决策、依据与备选方案。所有后端事实来自对 `src/main/**` 的只读核对，所有 UI 组件事实来自 `@lobehub/ui@5.57.x` 的官方组件文档与 npm 包元数据，工具链事实来自本机实测。

---

## D1. 前端工程形态：仓库内独立 SPA（`web/`），不用 Next.js，不塞进 Spring 静态目录

**Decision**: 在仓库根新增 `web/` 目录，作为 Vite + React 19 + TypeScript 的独立 SPA 工程；后端零改动。

**Rationale**:
- 后端无静态资源目录（`src/main/resources/` 下只有 `application.yml`、`db/`、`mapper/`），也无 CORS 配置；把前端塞进去需要改后端，违反本期"零后端改动"约束。
- 产品是内部管理台 + 对话应用，无 SEO/SSR 需求；Next.js 的收益为零，成本是 RSC 心智负担、`'use client'` 边界、部署需 Node 进程。
- 独立工程可用 Vite `server.proxy` 规避跨域，开发体验最佳；生产部署形态（nginx 静态托管 vs 后续由 Spring 托管）留待部署决策。

**Alternatives considered**:
- *Next.js*：SSR/文件路由对内部服务无价值，反而增加部署与心智成本 → 拒绝。
- *前端产物放进 `src/main/resources/static`*：需改后端 + 每次构建产物入 Java 构建，且 SPA 刷新需后端加 forward 规则 → 拒绝。
- *Thymeleaf 服务端渲染*：与既有纯 REST 风格冲突，交互能力弱 → 拒绝。

---

## D2. UI 组件库版本组合与 provider 顺序

**Decision**: `@lobehub/ui@^5.57` + 硬性 peer 依赖 `react@^19`、`react-dom@^19`、`antd@^6.1.1`、`motion@^12`、`@lobehub/icons@^5`、`@lobehub/fluent-emoji@^4`；挂载顺序固定为 `ConfigProvider`（必传 `motion`）→ `ThemeProvider` → 路由。

```tsx
<ConfigProvider motion={motion}>
  <ThemeProvider themeMode={mode} onThemeModeChange={setMode}
                 customTheme={{ primaryColor: 'geekblue', neutralColor: 'grey' }}>
    <RouterProvider router={router} />
  </ThemeProvider>
</ConfigProvider>
```

**Rationale**:
- `ThemeProvider` 渲染 antd `App`（承载静态 notification/modal）并读取 CDN 配置加载字体，二者都依赖上方 `ConfigProvider` 的 context，**顺序不可颠倒**。
- 包为 ESM only、`engines.node >= 22.22`，本机 Node v24.15.0 满足。
- 无需手动 import 全局样式（antd-style/emotion 运行时注入）；`static-css` 入口是可选的构建期优化，本期不用。
- 明暗模式：`themeMode` 默认 `light` 且不自动跟随系统，需显式实现 `auto` 语义；`ThemeSwitch` **完全受控**（`themeMode` 与 `onThemeSwitch` 必填，无内部 state），状态由应用持有。

**Alternatives considered**:
- *antd 原生*：通用件齐全但缺 AIGC 场景件，问答页要全部自研 → 拒绝（产品已确认走 Lobe UI 路线）。
- *shadcn/ui*：工程性更好但需自研聊天与流式 Markdown，核心场景成本高 → 用户已否决。

---

## D3. 禁用 core 组的废弃组件

**Decision**: 统一从 `@lobehub/ui/base-ui` 导入 `Button`、`Tag`、`Text`、`ActionIcon`；core 组同名组件视为废弃，不新用。

**Rationale**: 官方文档页已把 core 版标注为 deprecated，且两套是**各自独立的实现**、props 不兼容（例如 core `Tag` 有 `color/variant/shape`，base-ui `Tag` 是新一代实现）。混用会导致样式与 API 不一致。

**Alternatives considered**: 混用 core 版（历史代码常见）→ 随库升级会集中爆炸，拒绝。

---

## D4. 租户隐式注入：用户无感，界面零租户元素

**Decision**: 租户编码来源三层优先级——
1. URL 查询参数 `?tenant=xxx`（管理员通道）→ 写入 `sessionStorage`；
2. `sessionStorage` 中的既有值；
3. 构建/运行时配置 `VITE_DEFAULT_TENANT`（部署级默认值）。

最终值仅由 `api/client.ts` 在请求时注入 `X-Tenant-Code` 头，**不进入任何 UI 渲染路径**。

**Rationale**:
- 后端 `TenantContextInterceptor` 对所有 `/api/**` 强制要求该头，缺失即 `400 TENANT_CODE_REQUIRED`，因此前端必须能提供，但产品要求用户不感知。
- 三层优先级同时满足"普通用户零配置"和"运维可切换"，且不引入登录与后端改动。
- 收到 `TENANT_NOT_FOUND`(404) / `TENANT_CODE_REQUIRED`(400) 时，界面只呈现通用错误提示（例如"服务暂时不可用"），不暴露租户概念。

**Alternatives considered**:
- *让用户在首页输入租户编码*：用户已明确否决（"租户入口不想展示"）。
- *不做租户注入，等后端按会话解析*：需要后端改造（网关/会话解析），超出本期范围 → 记为未来演进（见 spec Assumptions）。
- *把默认租户硬编码进源码*：无法按环境切换 → 拒绝。

---

## D5. 开发期跨域：Vite 代理，不改后端

**Decision**: `vite.config.ts` 配置 `server.proxy`，把 `/api` 转发到 `http://localhost:8080`；生产环境由 nginx 或同源托管解决。

**Rationale**: 全仓库 grep `cors`、`addCorsMappings`、`@CrossOrigin` **零命中**，从 `localhost:5173` 直连 `8080` 会被浏览器拦截。代理方案零后端改动，且开发期同源，Cookie/头部行为与生产更接近。

**Alternatives considered**:
- *给后端加 CORS 配置*：能解决，但违反本期零后端改动，且需要处理 `X-Tenant-Code` 自定义头的预检 → 推迟。
- *用浏览器插件临时关闭跨域*：不可复现、不可自动化 → 拒绝。

---

## D6. 路由方案与占位边界

**Decision**: `react-router@^7`（Data Router，History 路由）。路由表：

| 路径 | 内容 | 本期状态 |
|---|---|---|
| `/` | 服务首页（提问台 + 知识导航） | **实现** |
| `/chat?q=<问题>` | 问答上下文占位页 | 实现（占位语义） |
| `/documents` | 文档库 | 占位页（"即将上线"） |
| `/jobs` | 任务中心 | 占位页（"即将上线"） |
| 文档详情 | 首页内 Drawer，不占路由 | 实现 |

**Rationale**:
- 首页导航必须能指向文档库/任务中心（FR-009 与导航完整性），但业务页不在本期范围 → 用占位页承接，避免死链或 404。
- 文档详情用 Drawer（US2 明确要求"不离开首页上下文"），不新增路由。
- 占位路由让导航结构在实现期就固定下来，后续 feature 只需替换页面组件。

**Alternatives considered**:
- *不引入路由，用组件状态切换视图*：URL 无法表达"问答上下文"，刷新即丢失，FR-006 的独立测试不可靠 → 拒绝。
- *HashRouter*：能免服务器 SPA fallback 配置，但 `#/chat?q=` 形式的链接体验与后续业务页扩展都更差 → 拒绝（改为在部署文档中说明 fallback 要求）。

---

## D7. 问答上下文：如实占位，不伪造答案

**Decision**: `/chat` 接收 `?q=` 展示用户的问题，并明确呈现"检索问答能力尚未上线"的状态说明；**不生成任何模拟答案或引用片段**。

**Rationale**:
- spec 006（RAG 检索问答）的接口在代码中**不存在**（无对应 Controller），任何"答案"都只能是编造内容。
- 对内部服务而言，展示假答案会破坏可信度，且会在真实接口接入时误导验收。
- FR-006 要求的是"携带问题进入问答上下文"，占位页满足该行为契约，同时如实标注能力边界。

**Alternatives considered**:
- *用 mock 数据演示完整问答与引用*：可用于对外营销演示，但对内会造成"功能已完成"的误判 → 拒绝（可作为后续演示分支单独做）。
- *提交后仅 Toast 提示、不跳转*：无法形成"进入问答上下文"的契约 → 拒绝。

---

## D8. 空态引导闭环所需的最小上传范围

**Decision**: 本期实现**最小可用的一次性上传**（`POST /api/v1/documents`，multipart：`file` + `docName` + `title?`），限制与后端一致：≤ 10 MiB、`.txt`/`.md`/`.markdown`、UTF-8 无 NUL。**不实现**分块上传（`/api/v1/uploads` 三阶段）与异步任务轮询（`jobId`/`statusUrl`）。

**Rationale**:
- US3 的验收场景要求"从空状态上传文档后返回首页，提问恢复可用"，若上传只给一个禁用按钮，该故事无法独立验收，空态引导也不闭环。
- 同步上传（`unirag.ingestion.async.enabled=false` 为当前默认）在响应里已返回最终摄取结果（含 `NOOP` 去重语义），前端无需轮询即可给出准确结论。
- 分块与异步属于文档库/任务中心 feature 的职责，强行纳入会让本期范围失控（对应 spec "明确不做"清单）。

**Alternatives considered**:
- *上传入口仅指向后续 feature（禁用按钮 + 文案）*：US3 不可独立测试 → 拒绝。
- *连分块上传一起做*：范围膨胀，且没有对应的列表页来展示进度 → 拒绝。

---

## D9. 数据获取：轻量 fetch 封装 + 自定义 hook，上传用 XHR

**Decision**: 自研 `api/client.ts`（fetch 封装：baseURL、头部注入、`{data}` 解包、错误归一）与 `hooks/useHomeOverview.ts`；上传进度用 `XMLHttpRequest`。**不引入** TanStack Query。

**Rationale**:
- 首页只有 1 个只读请求（列表，取 total 与最近 6 条）+ 1 个上传请求，无缓存失效、无轮询、无并发编排需求；引入查询库属于纯增重。
- `fetch` 无法上报上传进度，而空态引导需要进度反馈 → 上传单独走 XHR。
- 后续业务页（文档库分页、任务轮询）确有查询库需求，届时再引入，不影响本期结构。

**Alternatives considered**:
- *TanStack Query v5*：为将来铺路，但本期只带来依赖与样板 → 推迟。
- *axios*：功能等价于自研封装，但对本项目只需约 60 行的封装而言是多余依赖 → 拒绝。

---

## D10. 后端时间字段不统一 → 统一适配层

**Decision**: 在 `api/documents.ts` 内做字段归一，输出统一的 `number`（epoch 毫秒）。列表/详情的 `createdAt`/`updatedAt` 是 **epoch 毫秒数字**（`long`），而异步上传与任务查询的时间是 **ISO-8601 字符串**。

**Rationale**: 实测确认两类接口时间表示不同（`DocumentInfoItem`/`DocumentDetail` 用 `long`，`AsyncDocumentUploadResponse`/job 响应用 `@JsonFormat(shape=STRING)` 的 Date）。若不归一，`new Date(value)` 在数字路径上仍可用但类型标注会失真，且排序/格式化代码会分叉。归一为一处，UI 层只接受 `number`。

**Alternatives considered**: 每个组件各自处理 → 类型与格式逻辑分散，拒绝。

---

## D11. 首页提问框实现方式

**Decision**: 使用 `@lobehub/ui/chat` 的 **`ChatInputArea.Inner`**（纯受控 TextArea）作为输入内核，外层用 `Surface`/`Block` 自建大号容器，发送按钮用 `ChatSendButton`；**不使用** `ChatInputArea` 外层组件。

**Rationale**: 实测 `ChatInputArea` 的 `expand` **默认为 `true`**，会渲染为 fixed 全屏浮层；即使设为 `false` 也是底部可拖拽面板（`DraggablePanel placement="bottom"`），两者都不适合"首页居中大号输入框"。`ChatInputArea.Inner` 无这些布局副作用，且透传原生 textarea props（`value`/`onChange`/`placeholder`/`rows`），与本期需要的受控 + Enter 提交语义完全吻合。

**Alternatives considered**:
- *`ChatInputArea expand={false}`*：底部面板形态与首页视觉冲突 → 拒绝。
- *antd 原生 `Input.TextArea`*：可行，但丢失 Lobe 设计语言的一致性 → 仅在 `ChatInputArea.Inner` 出现问题时作为回退方案。

---

## D12. 主题模式持久化（实现 `auto` 语义）

**Decision**: 应用持有 `themeMode`（`'auto' | 'light' | 'dark'`）并持久化到 `localStorage`；`auto` 时用 `matchMedia('(prefers-color-scheme: dark)')` 解析为具体模式传给 `ThemeProvider`；`ThemeSwitch` 受控。

**Rationale**: `ThemeProvider.themeMode` 默认是 `light` 且不自动跟随系统，"跟随系统"必须由应用实现。`ThemeSwitch` 无内部 state，必须受控传入。

---

## D13. 建议问题生成规则

**Decision**: 有可检索文档时，生成 2 条来自最近文档标题（模板如"《{title}》讲了什么？"）+ 2 条预置通用问题；无文档时不提交并提示先上传。

**Rationale**: 后端无检索/热度接口，无法获得真实高频问题；用最近文档标题生成的半动态问题成本为零且相关性可接受，预置问题保证冷启动永远有内容。spec FR-007 要求 3~4 条，取 4 条。

**Alternatives considered**: 从对话历史统计热问 → 无后端支持；纯静态 4 条 → 相关性差。

---

## D14. 文案与状态映射集中管理

**Decision**: 全部中文文案集中在 `src/content/zh-CN.ts`；后端错误码 → 用户可读文案映射在 `src/api/errors.ts`；文档状态 → 颜色/文案映射在 `components/DocumentStatusTag.tsx`。

**Rationale**: 首页文案会被反复打磨，集中管理避免散落各处；状态色在本期与后续文档库/任务中心必须一致（绿=可检索、蓝=处理中、橙=部分成功、红=失败、灰=去重）。

---

## D15. 测试与验证策略

**Decision**: Vitest + `@testing-library/react` + jsdom，覆盖三类：
1. **纯函数**：视图状态推导（空库/处理中/正常/错误）、错误码映射、时间归一、建议问题生成；
2. **组件行为**：空输入不提交、Enter/Shift+Enter 语义、无历史对话不渲染容器、空库禁用提问；
3. **合规断言**：渲染首页各状态后，DOM 中不得出现租户编码字符串或"租户"字样（把 SC-003 落为可执行断言）。

类型检查 `tsc --noEmit`；构建产物做 gzip 体积核对（首屏 JS ≤ 400 KB）。

**Rationale**: spec 的 SC-003/SC-004/SC-007 需要可重复核查；后端已有 JUnit 测试文化（`*Test.java`/`*IT.java` 均随 `mvn test` 执行），前端建立同级的最小测试基线合理。

**Alternatives considered**: 只做手工验收 → SC-003 这类"不应出现"的断言无法防回归。

---

## D16. 包体积治理

**Decision**: `@lobehub/ui` 的 `Markdown`（依赖 shiki，可选 katex/mermaid）在首页**不引入**；路由级 `React.lazy` 加载 `/chat` 与占位页；`manualChunks` 拆分 react / antd+lobehub 两个 vendor 包。

**Rationale**: 首页只用到基础件，重组件不应进入首屏包。首屏 JS 目标 ≤ 400 KB（gzip），满足 SC-002 的 2 秒目标。

---

## D17. 包管理器与忽略规则

**Decision**: 使用 **npm**（本机 npm 11.12.1；pnpm 未安装，可用 corepack 启用，但暂不引入）。仓库根 `.gitignore` 补规则：`node_modules/`、`web/dist/`、`*.local`、`.env.local`。

**Rationale**: 本机无 pnpm，引入 corepack 流程对单人项目无收益；`@lobehub/ui` 与 antd 均为 ESM + 常规依赖，npm 完全够用。

**风险事实（必须处理）**: 现有 `.gitignore` **未忽略 `node_modules/`**，且 `dist` 仅以 `/dist/` 根锚定形式出现，**`web/dist/` 不会被忽略** → 若不补规则，首次安装即产生数万行未跟踪文件。

---

## 未决事项（不阻塞本期）

| 事项 | 影响 | 处理 |
|---|---|---|
| 生产部署形态（nginx vs Spring 静态托管） | 影响 SPA 刷新 fallback 与同源策略 | 本期只交付构建产物与该形态的说明，部署时决定 |
| 未来登录 + 按用户解析租户 | 会把 D4 的第 3 层替换为"登录后返回值" | 已隔离在 `api/tenant.ts` 单点，将来只改这一处 |
| 前端 CI（lint/test/build 接入既有流程） | 影响团队协作质量门 | 本期提供 `npm run lint/test/build` 脚本，是否接入 CI 由部署阶段决定 |
