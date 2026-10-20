# Contract: 文档上传/下载 HTTP 接口

**Feature**: 文档上传与文件存储 | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md) | **Data Model**: [data-model.md](./data-model.md)

本文件为 007 对外 HTTP 契约。项目首个 Web 层约定在此定义，006 复用（§7）。

## §1 通用约定

- **租户定位**：所有接口必须携带请求头 `X-Tenant-Code`（租户码，如 `upUS1`）。缺失 → `400 TENANT_CODE_REQUIRED`；租户不存在 → `404 TENANT_NOT_FOUND`。解析结果以服务层 `tenantCode` 传递（与 005 `IngestRequest` 对齐）。
- **Base path**：`/api/v1`
- **错误响应模型**（全接口统一）：

```json
{ "error": { "code": "FILE_TOO_LARGE", "message": "文件大小 73400320 字节超过上限 52428800 字节", "details": { "limit": 52428800 } } }
```

  - `code` 稳定可枚举（客户端可分支）；`message` 人类可读、**不含密钥与 config 原文**（FR-010）；`details` 可选。
  - 错误码全集：`TENANT_CODE_REQUIRED` / `TENANT_NOT_FOUND` / `VALIDATION_FAILED` / `UNSUPPORTED_FILE_TYPE` / `FILE_TOO_LARGE` / `UPLOAD_NOT_FOUND` / `UPLOAD_EXPIRED` / `UPLOAD_INCOMPLETE`（details 含 `missingChunks: [3,7]`）/ `CHECKSUM_MISMATCH` / `DOCUMENT_NOT_FOUND` / `CONCURRENT_MODIFICATION` / `INTERNAL_ERROR`。
- **成功响应**：统一包一层 `{ "data": ... }`。

## §2 一次性上传（小文件，≤ 阈值默认 10MB）

`POST /api/v1/documents` — `multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | file | 是 | 原文；扩展名白名单 + UTF-8 内容嗅探（research R6） |
| docName | text | 是 | 文档标识，租户内唯一（005 语义） |
| title | text | 否 | 展示名 |

成功 `200`（`data`）：

```json
{
  "docName": "产品手册-v2",
  "title": "产品手册",
  "status": "INGESTED",
  "version": 1,
  "chunkCount": 12,
  "fileSizeBytes": 20480,
  "pipelines": [ { "pipelineKey": "fine", "status": "SUCCESS", "chunks": 9 }, { "pipelineKey": "coarse", "status": "SUCCESS", "chunks": 3 } ]
}
```

语义：原文经 OpenDAL 落 `document_blob` → 建 `document`/`document_file` → 同步触发 005 摄取；同指纹重复上传 → `status` 如实反映 `NOOP`（`version` 不变），内容变更 → `version+1` 且响应反映替换（005 语义，spec FR-005）。

失败：`400 VALIDATION_FAILED`（缺字段）、`400 UNSUPPORTED_FILE_TYPE`（扩展名或嗅探不过）、`413 FILE_TOO_LARGE`。

## §3 分块上传（断点续传，大文件路径）

三步均要求 `X-Tenant-Code`；`uploadId` 仅创建者租户可见（跨租户访问 = `404 UPLOAD_NOT_FOUND`，不泄漏存在性）。

**① 初始化** `POST /api/v1/uploads` — JSON body：

```json
{ "docName": "大型知识库.md", "title": "大型知识库", "totalSize": 41943040, "chunkSize": 5242880, "totalChunks": 8, "contentSha256": "hex64" }
```

成功 `201`：`{ "uploadId": 1001, "expiresAt": "...", "receivedChunks": [] }`。校验失败 `400 VALIDATION_FAILED`（chunkSize/totalChunks/totalSize 超限或不一致）。

**② 上传分块** `PUT /api/v1/uploads/{uploadId}/chunks/{seq}` — 请求体为**原始二进制**（非 multipart）。

- 单块请求体大小不得超过该会话对应块的精确期望值：非尾块必须等于 chunkSize，尾块必须等于 totalSize 扣除前序完整块后的剩余字节；服务端按实际接收字节校验，并拒绝使累计大小超过 totalSize 的数据；
- 成功 `200`：`{ "uploadId": 1001, "receivedChunks": [0,1,2] }`（幂等：同 seq 重传 = 覆盖，块清单如实返回——断点续传时客户端据此只补缺块）；
- 失败：`400 VALIDATION_FAILED`（seq 越界或请求体长度与对应块期望长度不符）、`404 UPLOAD_NOT_FOUND` / `410 UPLOAD_EXPIRED`。超大请求体由 Web 容器拒绝时返回 `413 FILE_TOO_LARGE`。

**③ 完成** `POST /api/v1/uploads/{uploadId}/complete`

服务端先读取全部块并检查 SHA-256；校验成功后调用与一次性上传相同的收尾流程：OpenDAL 写入原文、调用 005 摄取、更新 `document_file` 并清理暂存块。blob 写入失败时不创建文档记录。

- 成功 `200`：响应体同 §2（文档结果）；
- `409 UPLOAD_INCOMPLETE`：`details.missingChunks` 列缺失块号（客户端补传后重试 complete）；
- `409 CHECKSUM_MISMATCH`：拼装结果与 `contentSha256` 不符（要求重新上传全部块或重建会话）；
- 并发完成同（tenant, docName）：由 005 指纹/唯一约束裁决为 NOOP 或替换（`CONCURRENT_MODIFICATION` 仅用于会话级冲突）。

## §4 查询与下载

`GET /api/v1/documents?page=1&size=20` — 列表（仅本租户，按上传时间倒序）：

```json
{ "data": { "total": 2, "items": [ { "docName": "...", "title": "...", "status": "INGESTED", "version": 1, "chunkCount": 12, "fileSizeBytes": 20480, "mimeType": "text/markdown", "createdAt": "..." } ] } }
```

`GET /api/v1/documents/{docName}` — 详情（字段同上 + `updatedAt`、`source`）。

`GET /api/v1/documents/{docName}/file` — 下载原文：

- 成功 `200`，`Content-Type` = 登记 mime，`Content-Disposition: attachment; filename*=UTF-8''{file_name}`；
- 字节与上传原文逐字节一致（SC-005）；
- `404 DOCUMENT_NOT_FOUND`（含跨租户访问，spec US2-2）。

## §5 幂等与并发

- 同（tenant, docName, 指纹）重复上传/重复 complete → NOOP，数据不翻倍（spec FR-005）；
- 分块重传同 seq → 覆盖，块清单为准（data-model `upload_chunk` PK 语义）；
- 同文档并发上传/完成 → 005 唯一约束 + 分段锁裁决，确定性收尾（research R5）。

## §6 大小与配置

| 配置项 | 默认 | 说明 |
|---|---|---|
| `unirag.upload.max-file-size` | 50MB | 单文件上限（一次性与分块共用） |
| `unirag.upload.chunk-size` | 5MB | 固定分块大小 |
| `unirag.upload.one-shot-threshold` | 10MB | ≤ 此值走 §2 一次性接口 |
| `unirag.upload.session-ttl` | 24h | 未完成会话保留时长 |
| `unirag.storage.backend` | postgresql | `postgresql` \| `fs` \| `s3`（research R1/R2） |

## §7 跨 feature 约定（006 复用清单）

1. `X-Tenant-Code` 租户定位与错误模型（§1）；
2. `TenantContext` 解析器、`{ "data": ... }` 响应包装、统一异常处理器；
3. 文档标识 = `docName`（租户内唯一），与 005/006 检索过滤的 `doc_name` 元数据键一致。
