# Quickstart: RAG 服务首页验证指南

**Feature**: `009-rag-service-home` | **Date**: 2026-10-11

本文件是**可执行的验证指南**：按顺序执行即可证明首页端到端可用。实现细节见 `plan.md`、数据与状态定义见 `data-model.md`、行为约定见 `contracts/ui-contract.md`。

---

## 1. 前置条件

| 项 | 要求 | 校验命令 |
|---|---|---|
| Node.js | ≥ 22.22（`@lobehub/ui` 硬性要求；本机实测 v24.15.0） | `node --version` |
| npm | 随 Node 提供（本机 11.12.1） | `npm --version` |
| Java | 21（后端运行时） | `java -version` |
| PostgreSQL + pgvector | 后端依赖，`localhost:5432/uni_rag_db` | `pg_isready -h localhost -p 5432` |
| 数据库迁移 | Flyway V1–V7 已应用 | 见后端 README |

**关键前置：数据库中存在至少一个可用租户**，其编码将作为前端的 `VITE_DEFAULT_TENANT`。首页不展示租户，但后端强制要求该编码有效（否则所有请求返回 `404 TENANT_NOT_FOUND`）。

---

## 2. 启动后端

```bash
# 仓库根目录
./mvnw spring-boot:run
# 期望：启动完成后监听 8080；健康检查
curl -s http://localhost:8080/actuator/health
# 期望：{"status":"UP"}
```

确认接口可用（把 `<TENANT>` 替换为真实租户编码）：

```bash
curl -s -H 'X-Tenant-Code: <TENANT>' 'http://localhost:8080/api/v1/documents?page=1&size=6'
# 期望：{"data":{"total":N,"items":[...]}}；无该请求头时返回 400 TENANT_CODE_REQUIRED
```

---

## 3. 启动前端

```bash
cd web
npm install

# 配置默认租户（不进入版本库）
cp .env.example .env.local
# 编辑 .env.local：VITE_DEFAULT_TENANT=<TENANT>

npm run dev
# 期望：Vite 启动在 http://localhost:5173，/api 请求被代理到 http://localhost:8080
```

打开 `http://localhost:5173`。**若首页请求全部失败**，先按 §8 排查租户编码与后端状态。

---

## 4. 按用户故事验收

### US1（P1）无前置步骤直接提问

| 步骤 | 期望结果 |
|---|---|
| 打开 `http://localhost:5173/` | 首屏出现提问输入框；**无登录、无租户选择、无任何"租户"字样** |
| 在输入框点 `Enter`（内容为空） | 无跳转、无错误页，焦点仍在输入框 |
| 输入多行文本并用 `Shift+Enter` | 输入框内换行，不提交 |
| 按 `Enter` | 跳转到 `/chat?q=<问题>`，页面上显示刚才的问题 |
| 在页面任意处按 `/` 或 `Ctrl/Cmd+K` | 焦点定位到提问框 |
| 检查 DOM（`document.body.textContent`） | 不包含 `租户`、`tenant`，也不包含租户编码字符串 |

### US2（P2）浏览知识库内容

| 步骤 | 期望结果 |
|---|---|
| 知识库中有文档时打开首页 | 展示最近更新文档条目（≤6 条），含图标、标题、相对时间 |
| 点击某条文档 | 打开详情抽屉；关闭后仍在 `/`（URL 未变化） |
| 点击"浏览全部文档" | 进入 `/documents` 占位页（"即将上线"）。**若这里出现 404 或空白页，视为不通过** |
| 将浏览器窗口缩到移动宽度 | 无横向滚动；标题超长时单行截断且悬停可见全名 |

### US3（P2）空知识库时的启动引导

> 需一个没有任何文档的租户（可新建租户或用清理后的测试租户）。

| 步骤 | 期望结果 |
|---|---|
| 用空知识库租户打开首页 | 提问框禁用并说明原因；导航层变为三步引导（上传 → 自动处理 → 提问） |
| 点击建议问题 | 提示需先上传文档，**不提交问题** |
| 通过引导卡上传一份 `.md` 文件（≤10 MB） | 显示上传进度；成功后给出结果文案（`已入库` / `内容未变化，已跳过` / `部分成功` / `失败`） |
| 上传非法文件（如 `.pdf` 或 >10 MB） | **不发起请求**，就地提示类型/大小限制 |
| 回到首页（或等待刷新） | 副标题文档数增加；最近更新出现该文档；提问框恢复可用 |
| 重复上传同一文件内容 | 结果提示"内容未变化，已跳过重复处理"（后端 `NOOP`） |

### US4（P3）建议问题与回访继续

| 步骤 | 期望结果 |
|---|---|
| 知识库有可检索文档时打开首页 | 展示 4 条建议问题（含来自文档标题的条目） |
| 点击一条建议问题 | 与手动输入提交等价，跳转 `/chat?q=...` |
| 返回首页 | "最近对话"出现刚提交的问题条目 |
| 打开全新浏览器配置文件（无 localStorage） | **不渲染"最近对话"区块**（无空容器、无占位留白） |

### 边界状态验收

| 场景 | 构造方式 | 期望结果 |
|---|---|---|
| 服务不可用 | 停掉后端进程后刷新首页 | 顶部告警条 + 重试按钮；提问框禁用；**不得显示为"知识库为空"** |
| 处理中 | 上传一份文档后立刻刷新（或使用 `status` 为 `INGESTING` 的数据） | 提问可用，并提示"知识库正在处理中，部分问题可能没有结果" |
| 加载中 | 限速网络（DevTools throttling）刷新 | 概览区域用占位骨架；提问框立即可用，不被阻塞 |

---

## 5. 自动化验证

```bash
cd web
npm run lint          # 静态检查（存在则运行）
npm run typecheck     # tsc --noEmit，零错误
npm test              # Vitest：纯函数 + 组件行为 + 禁止租户元素断言
npm run build         # 生产构建，零错误
npm run preview       # 本地预览构建产物
```

**必须为绿的三类测试**（对应 `plan.md` D15）：

1. 视图状态推导（`empty` / `processing` / `ready` / `error` 及其优先级）
2. 提问框行为（空输入不提交、Enter/Shift+Enter 语义、禁用条件）
3. 禁止项断言（各状态下 DOM 不含"租户"/编码）

**包体积核对**：构建后确认首屏 JS gzip ≤ 400 KB，且产物中不含 `shiki`/`katex`/`mermaid` 相关 chunk。

---

## 6. 后端无回归

本期不改后端，验证方式：

```bash
# 仓库根目录
./mvnw test
```

期望与改动前一致（本特性不触碰 `src/main/**` 与 `pom.xml`）。若该命令因本地 PostgreSQL 未就绪而失败，属环境问题，与本特性无关，但需在交付说明中如实标注。

---

## 7. 部署说明（本期只交付构建产物，形态待定）

```bash
cd web && npm run build     # 产物在 web/dist
```

| 部署方式 | 需要做的事 | 注意 |
|---|---|---|
| 静态服务器（nginx 等） | 托管 `web/dist`，并配置 **SPA fallback**（未匹配路径回落到 `index.html`） | 否则刷新 `/chat` 会 404 |
| 反向代理到后端 | 把 `/api` 代理到 `http://localhost:8080` | 同源后可省去 CORS |
| 由 Spring 托管 | 需把产物放入 `src/main/resources/static` 并新增 SPA forward 配置 | **属于后端改动，不在本期范围**，需单独决策 |

无论哪种方式，都必须保证浏览器发出的 `/api/**` 请求携带 `X-Tenant-Code`（由前端注入，与部署形态无关）。

---

## 8. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 首页全部请求失败，告警条出现 | `VITE_DEFAULT_TENANT` 为空或租户不存在 | 核对 `.env.local` 与数据库租户；后端日志应出现 `TENANT_NOT_FOUND` |
| 浏览器控制台报 CORS | 未走 Vite 代理（例如直接指向 8080） | 确认 `vite.config.ts` 的 `server.proxy` 生效，请求路径以 `/api` 开头 |
| 上传返回 413 | 文件超过 10 MiB 阈值 | 前端本应拦截；若已放行则检查大小校验实现 |
| 上传返回 400 `UNSUPPORTED_FILE_TYPE` | 扩展名不在白名单或内容非 UTF-8 | 使用 `.md`/`.txt` 且为纯 UTF-8 文件 |
| 提问框变成全屏浮层 | 误用了 `ChatInputArea` 外层组件 | 改用 `ChatInputArea.Inner`（见 plan D11） |
| 明暗切换无效 | `ThemeSwitch` 未受控传入 | 该组件无内部 state，必须传 `themeMode` 与 `onThemeSwitch`（plan D2） |
| 组件样式/颜色缺失 | provider 顺序颠倒 | 必须 `ConfigProvider` 在外、`ThemeProvider` 在内（plan D2） |
| 首次 `npm install` 后出现大量未跟踪文件 | `.gitignore` 未忽略 `node_modules/` | 已在计划内补充忽略规则（plan D17），确认规则已生效 |
