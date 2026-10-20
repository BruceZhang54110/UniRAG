# Phase 1 Data Model: RAG 服务首页

**Feature**: `009-rag-service-home` | **Date**: 2026-10-11

本期为纯前端功能，**不新增任何服务端实体或数据库表**。本文件定义前端消费与持有的数据结构、派生规则、状态机与本地持久化约定；字段名与类型以既有后端接口的实测返回为准（见 `contracts/backend-api-consumption.md`）。

---

## 1. 领域实体

### 1.1 DocumentSummary（文档摘要，只读，来自后端）

首页最近更新列表与详情抽屉的基础数据。

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `docName` | `string` | 后端 | 租户内唯一标识，同时用作列表 key 与详情/下载路径参数 |
| `title` | `string \| null` | 后端 | 可为空（显示时回退为 `docName`） |
| `status` | `DocumentStatus` | 后端 | 见 §2 状态映射 |
| `version` | `number` | 后端 | 整数 |
| `chunkCount` | `number` | 后端 | 切片总数 |
| `fileSizeBytes` | `number \| null` | 后端 | **可为 null**（无文件元数据时），需判空后再格式化 |
| `mimeType` | `string \| null` | 后端 | **可为 null**，用于 `FileTypeIcon` 的 `filetype` 推断 |
| `createdAt` | `number` | 后端（epoch 毫秒） | 经适配层归一为数字；原样为 `long` |

**派生字段（前端计算，不来自后端）**

| 字段 | 规则 |
|---|---|
| `displayTitle` | `title` 非空且非空白 ? `title` : `docName` |
| `filetype` | 由 `mimeType`（`text/markdown` → `md`、`text/plain` → `txt`）或 `docName` 扩展名推断；均不可判定时用 `file` |
| `createdAtLabel` | 相对时间（"刚刚 / N 分钟前 / N 小时前 / N 天前 / N 个月前 / 具体日期"），超过 30 天显示绝对日期 |

### 1.2 DocumentDetail（文档详情，只读）

`DocumentSummary` 的全部字段，外加：

| 字段 | 类型 | 说明 |
|---|---|---|
| `updatedAt` | `number` | epoch 毫秒，经归一 |
| `source` | `string` | 后端当前**恒为 `"upload"`**，界面上不承诺其为真实来源 |

### 1.3 KnowledgeOverview（知识库概览，前端派生）

用于驱动首页视图状态与提示文案。**不新增接口**：由文档列表响应（`total` + `items`）派生。

| 字段 | 类型 | 派生规则 |
|---|---|---|
| `total` | `number` | 响应中的 `total` |
| `retrievable` | `number` | 已加载 items 中 `status === 'INGESTED'` 的数量（受分页窗口限制，仅作提示用，不作为精确统计对外承诺） |
| `processing` | `number` | 已加载 items 中 `status === 'INGESTING'` 的数量 |
| `hasDocuments` | `boolean` | `total > 0` |

> **设计取舍**：后端没有"可检索数量"的聚合接口。为了让"是否有可提问内容"的判断尽可能准确，首页请求 `size=6` 的同一响应即可用于判定空库；`retrievable`/`processing` 仅用于提示文案，界面上以"最近文档"语义呈现，不呈现为"精确统计"。若后续需要精确统计，应新增后端聚合接口而非在前端拼凑。

### 1.4 Suggestion（建议问题，前端生成）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `string` | 稳定 key |
| `text` | `string` | 问题文本 |
| `source` | `'document' \| 'preset'` | 来源，用于埋点区分 |
| `enabled` | `boolean` | 知识库不可提问时为 `false`（置灰 + 提示先上传） |

**生成规则**（`features/home/state.ts` 纯函数）：

1. 取最近 6 条文档中 `status === 'INGESTED'` 的前 2 条，各生成一条："《{displayTitle}》讲了什么？"
2. 追加 2 条预置通用问题（来自 `content/zh-CN.ts`）。
3. 总计 4 条；可用文档不足 2 条时以预置问题补齐，仍保持 4 条。
4. `enabled = hasDocuments && retrievable > 0`（对所有条目一致）。

### 1.5 ChatSessionSummary（会话摘要，前端持有）

用于"最近对话"回访。**仅存于浏览器本地**，不依赖服务端。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `string` | 生成标识（时间戳 + 随机后缀） |
| `question` | `string` | 首个问题的摘要，落盘前截断至 80 字符 |
| `lastActiveAt` | `number` | epoch 毫秒 |
| `messageCount` | `number` | 会话内消息数（占位期恒为 1，为将来预留） |

**规则**：最多保留 6 条，按 `lastActiveAt` 倒序；超出时丢弃最旧记录；读取时若解析失败（脏数据/版本不兼容）则整体清空重置，不抛错阻塞首页。

### 1.6 UploadRequest / UploadResult（上传，前端构造）

**UploadRequest**（表单字段，与后端 multipart 字段一一对应）

| 字段 | 类型 | 约束 |
|---|---|---|
| `file` | `File` | 扩展名 ∈ {txt, md, markdown}（大小写不敏感）；≤ 10 MiB（10485760 字节）；内容为 UTF-8 且无 NUL 字节 |
| `docName` | `string` | 必填、非空、≤ 255 字符；默认取文件名去扩展名 |
| `title` | `string \| undefined` | 可选 |

**UploadResult**（同步 200 响应，经适配层）

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | `'INGESTED' \| 'PARTIAL' \| 'FAILED' \| 'NOOP'` | `NOOP` 表示内容指纹重复、未产生新切片 |
| `version` | `number` | 新版本号；`NOOP` 时不变 |
| `chunkCount` | `number` | 所有管线片段合计 |
| `fileSizeBytes` | `number` | |
| `pipelines` | `Array<{ pipelineKey: string; status: 'SUCCESS' \| 'FAILED'; chunks: number }>` | 分管线结果 |

> 异步模式（202）的 `{documentId, documentName, documentVersion, jobId, status, stage, createdAt, statusUrl}` **本期不消费**：默认配置 `unirag.ingestion.async.enabled=false`，且异步任务轮询属于任务中心 feature。适配层需在遇到 202 时给出明确提示（见 `contracts/backend-api-consumption.md` 的降级约定）。

---

## 2. 状态映射

### 2.1 文档状态（列表/详情）

| 后端 `status` | 中文文案 | 颜色语义 | 备注 |
|---|---|---|---|
| `INGESTED` | 可检索 | 绿（success） | 唯一可视为"可用于提问"的状态 |
| `INGESTING` | 处理中 | 蓝（info/主色） | 需要提示"知识库正在处理中" |
| `PARTIAL` | 部分成功 | 橙（warning） | 部分管线失败 |
| `FAILED` | 处理失败 | 红（error） | 需引导至后续任务中心 |

> 语义纪律：`INGESTED` 表示**该文档版本已进入索引**；`NOOP` 仅出现在上传结果中（灰/中性），不用于列表状态。

### 2.2 上传结果状态

| 结果 | 文案 | 颜色语义 |
|---|---|---|
| `INGESTED` | 已入库，可以提问了 | 绿 |
| `NOOP` | 内容未变化，已跳过重复处理 | 灰 |
| `PARTIAL` | 部分内容入库成功 | 橙 |
| `FAILED` | 入库失败 | 红（附后端 `error.message`） |

### 2.3 错误码（→ 用户文案，`api/errors.ts`）

| 后端 `code` | HTTP | 用户可见文案（不外泄技术细节） |
|---|---|---|
| `TENANT_CODE_REQUIRED` / `TENANT_NOT_FOUND` | 400 / 404 | 服务暂时不可用，请稍后重试（**不提"租户"**） |
| `VALIDATION_FAILED` | 400 | 提交的内容不符合要求，请检查后重试 |
| `UNSUPPORTED_FILE_TYPE` | 400 | 仅支持 txt、md、markdown 格式的文本文件 |
| `FILE_TOO_LARGE` | 413 | 文件过大：直接上传上限 10 MB |
| `DOCUMENT_NOT_FOUND` | 404 | 该文档不存在或已被移除 |
| `INTERNAL_ERROR` / 网络失败 | 500 / — | 服务暂时不可用，请稍后重试 |

**统一规则**：所有错误都经 `client.ts` 归一为 `{ code, message, status }`；界面只展示映射后的中文文案 + 重试入口，不透传后端原始 message。

---

## 3. 视图状态机（首页）

首页由**两个独立维度**驱动，避免状态爆炸：

- **数据维度**（`useHomeOverview` 结果）：`loading | error | ready`
- **内容维度**（由 `KnowledgeOverview` 派生，`features/home/state.ts` 纯函数）：`empty | processing | ready`

合成后的视图状态：

| 视图状态 | 触发条件 | 首页表现 |
|---|---|---|
| `loading` | 列表请求未返回 | 副标题用 Skeleton；提问框与导航层正常渲染（**不整页 loading**） |
| `error` | 请求失败（含 `TENANT_*`、网络失败） | 顶部 `Alert` 告警条 + 重试按钮；提问框禁用 |
| `empty` | `total === 0` | 提问框禁用并提示先上传；导航层替换为三步引导 + 上传入口；最近对话隐藏 |
| `processing` | `total > 0` 且 `retrievable === 0` | 提问框可用；提示"知识库正在处理中，部分问题可能没有结果"；导航层显示文档列表 |
| `ready` | 存在可检索文档 | 标准态：提问框可用 + 建议问题可用 + 最近更新列表 + 行动卡 + 最近对话 |

**状态转移**：
```
loading ──成功──> empty | processing | ready
loading ──失败──> error
error ──重试──> loading
empty ──上传成功且可检索──> ready
processing ──重新拉取后发现可检索──> ready
any ──请求失败──> error
```

**优先级**：`error` > `empty` > `processing` > `ready`（失败不得被渲染为"知识库为空"，spec 边界要求明确区分）。

---

## 4. 本地持久化

| Key | 存储 | 值 | 约束 |
|---|---|---|---|
| `unirag.theme-mode` | localStorage | `'auto' \| 'light' \| 'dark'` | 非法值回落 `auto` |
| `unirag.recent-chats` | localStorage | `ChatSessionSummary[]` JSON | 最多 6 条；解析失败则清空 |
| `unirag.tenant` | sessionStorage | 租户编码字符串 | **仅由 `?tenant=` 写入**，不参与渲染；会话结束即失效 |

**明确不落地**：不缓存文档列表（首页每次进入重新拉取，保证"上传后返回即可见"）；不持久化建议问题。

---

## 5. 校验规则汇总（前端，与后端一致）

| 场景 | 规则 | 失败表现 |
|---|---|---|
| 提问提交 | 去空白后长度 ≥ 1 | 不提交、保持焦点、无错误页（FR-004） |
| 提问长度 | 不做硬性上限；超长输入不破坏布局 | 文本域内部滚动 |
| 上传文件类型 | 扩展名 ∈ {txt, md, markdown}，大小写不敏感 | 就地提示"仅支持 txt、md、markdown"，不发起请求 |
| 上传文件大小 | ≤ 10 MiB | 就地提示"文件过大：直接上传上限 10 MB" |
| 上传文档名 | 非空、≤ 255 字符 | 表单校验提示，不发起请求 |
| 分页参数 | `page ≥ 1`、`1 ≤ size ≤ 100` | 前端固定使用合法值（本期只用 `size=6`），不产生非法请求 |
| 时间字段 | 数字（epoch 毫秒） | 归一失败时显示"—"，不影响其余渲染 |
