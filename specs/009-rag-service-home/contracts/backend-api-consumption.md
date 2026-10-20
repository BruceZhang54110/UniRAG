# Contract: 后端接口消费契约

**Feature**: `009-rag-service-home` | **Date**: 2026-10-11
**来源**: 对 `src/main/java/com/unirag/**` 的只读核对（非文档转述）

本契约定义首页**消费**的后端接口。**本期不新增、不修改任何后端接口**；若后端行为变更，以本文件列出的差异点作为回归检查清单。

---

## 0. 通用约定

| 项 | 约定 |
|---|---|
| Base URL（开发） | `/api`（经 Vite 代理转发至 `http://localhost:8080`） |
| 必带请求头 | `X-Tenant-Code: <编码>` —— 作用于**全部 `/api/**`**（`TenantContextInterceptor`），缺失即 `400 TENANT_CODE_REQUIRED` |
| 成功响应外层 | 除文件下载外，一律为 `{ "data": ... }` |
| 错误响应外层 | `{ "error": { "code": string, "message": string, "details"?: object } }`（`details` 仅在非空时出现） |
| 端口 / 上下文路径 | `8080` / 根路径（`application.yml` 无 `server:` 配置块） |
| CORS | 后端**无任何 CORS 配置** → 开发期必须走代理 |

**前端义务**：
1. 所有请求经 `api/client.ts` 注入 `X-Tenant-Code`，取值见 plan 的 D4（`?tenant=` → `sessionStorage` → `VITE_DEFAULT_TENANT`）。
2. 租户编码与租户相关的错误语义**不得进入任何 UI 文案或 DOM**（spec FR-002 / SC-003）。

---

## 1. `GET /api/v1/documents` —— 文档列表（首页主数据源）

**请求**：`?page=<int, 默认 1>&size=<int, 默认 20, 1..100>`；首页固定使用 `page=1&size=6`。

**成功 200**：

```json
{
  "data": {
    "total": 2,
    "items": [
      {
        "docName": "employee-handbook",
        "title": "员工手册",
        "status": "INGESTED",
        "version": 3,
        "chunkCount": 128,
        "fileSizeBytes": 20480,
        "mimeType": "text/markdown",
        "createdAt": 1760000000000
      }
    ]
  }
}
```

| 字段 | 类型 | 契约要点 |
|---|---|---|
| `total` | number | 用于判定空库（`total === 0`）与副标题文案 |
| `items[].docName` | string | 详情/下载路径参数，列表 key |
| `items[].title` | string \| null | 空值时前端回退为 `docName` |
| `items[].status` | string | 仅 `INGESTING` / `INGESTED` / `PARTIAL` / `FAILED`（映射见 data-model §2.1） |
| `items[].version` | number | |
| `items[].chunkCount` | number | |
| `items[].fileSizeBytes` | number \| **null** | 判空后再格式化 |
| `items[].mimeType` | string \| **null** | 判空后推断图标类型 |
| `items[].createdAt` | number | **epoch 毫秒，不是 ISO 字符串** |

**排序**：`created_at DESC, id DESC`（即天然可作为"最近更新"使用）。

**失败**：`400 VALIDATION_FAILED`（`page < 1`、`size < 1` 或 `size > 100`）、`400 TENANT_CODE_REQUIRED`、`404 TENANT_NOT_FOUND`、`500 INTERNAL_ERROR`。

---

## 2. `GET /api/v1/documents/{docName}` —— 文档详情（首页抽屉）

**成功 200**：`{ "data": { ...同列表字段, "updatedAt": 1760000000000, "source": "upload" } }`

| 契约要点 | 说明 |
|---|---|
| `updatedAt` | **epoch 毫秒数字** |
| `source` | **后端硬编码为 `"upload"`** —— 界面不得宣称其为真实来源，按原值展示或不展示 |
| 不存在 / 跨租户 | `404 DOCUMENT_NOT_FOUND`（统一语义，不泄漏其他范围是否存在） |

---

## 3. `GET /api/v1/documents/{docName}/file` —— 下载原文

| 契约要点 | 说明 |
|---|---|
| 响应体 | **原始字节，不经 `{data}` 包裹**（唯一例外） |
| `Content-Type` | 登记的 mime（`text/markdown` / `text/plain`），无法解析时 `application/octet-stream` |
| `Content-Disposition` | **仅 `filename*=UTF-8''<percent-encoded>`**（RFC 5987），**没有普通 `filename=` 回退** |
| 无原文时 | `404 DOCUMENT_NOT_FOUND` |

**前端义务**：文件名解析必须走 RFC 5987，并对 `decodeURIComponent` 失败做兜底（回退 `docName`）；下载需以 `fetch` + `Blob` 方式实现以携带请求头（不能再用 `<a href>` 直链）。

> 首页 US2 的"查看详情"不要求下载；下载能力由文档库 feature 承担，本契约先行记录以便复用同一 `api/documents.ts`。

---

## 4. `POST /api/v1/documents` —— 一次性上传（空态引导闭环）

**请求**：`multipart/form-data`，字段 `file`（必填）、`docName`（必填，非空、≤255 字符）、`title`（可选）。

**服务端限制（前端必须前置校验，避免无谓请求）**：

| 限制 | 值 | 违反时的后端响应 |
|---|---|---|
| 扩展名白名单 | `txt` / `md` / `markdown`（大小写不敏感） | `400 UNSUPPORTED_FILE_TYPE` |
| 内容 | 完整 UTF-8、无 NUL 字节 | `400 UNSUPPORTED_FILE_TYPE` |
| 空内容 | 不允许 | `400 VALIDATION_FAILED` |
| 单文件上限（本接口） | **10 MiB（10485760 字节）** | `413 FILE_TOO_LARGE`（**注意不是 50 MiB**：更大文件必须改走分块接口，本期不做） |
| 容器上限 | 单文件 60 MB / 单请求 70 MB | `413 FILE_TOO_LARGE` |

**同步成功 200**（当前默认 `unirag.ingestion.async.enabled=false`）：

```json
{
  "data": {
    "docName": "employee-handbook",
    "title": "员工手册",
    "status": "INGESTED",
    "version": 3,
    "chunkCount": 128,
    "fileSizeBytes": 20480,
    "pipelines": [
      { "pipelineKey": "coarse", "status": "SUCCESS", "chunks": 21 },
      { "pipelineKey": "fine", "status": "SUCCESS", "chunks": 107 }
    ]
  }
}
```

| 契约要点 | 说明 |
|---|---|
| `status` | `INGESTED` / `PARTIAL` / `FAILED` / **`NOOP`**（同内容重复上传，未产生新切片） |
| `chunkCount` | 此处为 `int`（列表/详情为 `long`），前端统一按 number 处理 |
| `pipelines[].status` | `SUCCESS` / `FAILED` |
| 幂等 | **不支持 `Idempotency-Key`**，前端不得假设服务端会去重请求；重复内容由 `NOOP` 语义表达（仅同步路径） |

**异步 202**（`async.enabled=true` 时）：`{ "data": { documentId, documentName, documentVersion, jobId, status: "QUEUED", stage: "UPLOADED", createdAt: "<ISO-8601 字符串>", statusUrl: "/api/v1/documents/{id}/ingestion-jobs/{jobId}" } }`

| 契约要点 | 本期处理 |
|---|---|
| 字段名是 `documentName`（**非 `docName`**） | 不消费；仅需识别 202 分支 |
| `statusUrl` 是**相对路径** | 若将来消费需拼接 base |
| 时间字段是 **ISO-8601 字符串**（与列表/详情的 epoch 毫秒不同） | 见 data-model §1.6 与 plan D10 |
| 相同内容不会返回 `NOOP`，而是复用已有 job 并返回 202 | 本期不涉及 |

**降级约定（本期）**：若收到 202（说明部署开启了异步），前端不得把上传判为失败，也不做轮询，而是提示"文档已接收，正在后台处理，稍后可在文档库查看"。**这是唯一允许的降级路径**，且需在 `api/documents.ts` 中以显式分支实现，便于将来替换为轮询。

---

## 5. 错误码全表（含本期是否处理）

| code | HTTP | 触发 | 首页处理 |
|---|---|---|---|
| `TENANT_CODE_REQUIRED` | 400 | 缺 `X-Tenant-Code` | 归一为通用"服务暂时不可用" + 重试 |
| `TENANT_NOT_FOUND` | 404 | 租户不存在 | 同上（**文案不得出现"租户"**） |
| `VALIDATION_FAILED` | 400 | 参数/分页/空 docName/空文件/缺字段 | 就地表单提示 |
| `UNSUPPORTED_FILE_TYPE` | 400 | 扩展名或内容不合规 | 就地文件提示 |
| `FILE_TOO_LARGE` | 413 | 超 10 MiB 或容器上限 | 就地文件提示 |
| `DOCUMENT_NOT_FOUND` | 404 | 详情/下载不存在、跨租户、无原文 | 抽屉内提示"文档不存在或已被移除" |
| `UPLOAD_NOT_FOUND` | 404 | 分块会话不存在 | 本期不涉及（分块不做） |
| `DOCUMENT_INGESTION_JOB_NOT_FOUND` | 404 | 任务不可见 | 本期不涉及（任务轮询不做） |
| `UPLOAD_EXPIRED` | 410 | 会话过期 | 本期不涉及 |
| `UPLOAD_INCOMPLETE` | 409 | 缺块（`details.missingChunks`） | 本期不涉及 |
| `CHECKSUM_MISMATCH` | 409 | 校验和不符 | 本期不涉及 |
| `CONCURRENT_MODIFICATION` | 409 | 并发修改 | 归一为"操作冲突，请重试" |
| `INTERNAL_ERROR` | 500 | 兜底（不泄漏内部细节） | 通用"服务暂时不可用" + 重试 |

---

## 6. 首页刻意不消费的接口（范围边界）

| 接口 | 原因 |
|---|---|
| `POST /api/v1/uploads`、`PUT .../chunks/{seq}`、`POST .../complete` | 分块上传属文档库 feature |
| `GET /api/v1/documents/{id}/ingestion-jobs/{jobId}` | 任务中心 feature；当前默认同步模式下无 job 产生 |
| `/actuator/health` | 首页不主动探活；服务可用性通过业务请求的失败反馈体现 |
