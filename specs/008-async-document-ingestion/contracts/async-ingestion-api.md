# 异步摄取 API 契约

本文件定义未来前端调用的 HTTP 契约；本期不实现前端页面，也不在此写实现代码。租户由现有 `X-Tenant-Code` 请求头定位。成功响应沿用现有 `ApiResponse<T>` 的 `{ "data": ... }` 包装；错误响应沿用统一顶层 `{ "error": { "code", "message", "details"? } }` 形状。

## 1. 上传并创建任务

`POST /api/v1/documents`  
`Content-Type: multipart/form-data`，字段：

- `file`：必填原文；继续使用现有类型、大小和内容校验。
- `docName`：必填，租户内逻辑文档名。
- `title`：可选。
- `Idempotency-Key`：推荐；服务端仍必须以租户、文档、版本/内容指纹唯一约束兜底。

成功：`202 Accepted`。上传返回不等待分片、向量化或可检索完成。

```json
{
  "data": {
    "documentId": 123,
    "documentName": "handbook",
    "documentVersion": 2,
    "jobId": 456,
    "status": "QUEUED",
    "stage": "UPLOADED",
    "createdAt": "2026-10-07T12:00:00Z",
    "statusUrl": "/api/v1/documents/123/ingestion-jobs/456"
  }
}
```

重复提交同一租户、逻辑文档、版本/指纹时，不创建第二个有效任务；服务端可返回 `202` 并返回已有任务，或返回等价的幂等结果，但字段语义必须相同。具体是否复用 HTTP `Idempotency-Key` 作为优化键，不得替代数据库唯一身份。

## 2. 查询任务

`GET /api/v1/documents/{documentId}/ingestion-jobs/{jobId}`

成功：`200 OK`，仅返回当前租户可见的 job。

```json
{
  "data": {
    "jobId": 456,
    "documentId": 123,
    "documentName": "handbook",
    "documentVersion": 2,
    "status": "RETRYING",
    "stage": "INGESTING",
    "attemptCount": 1,
    "maxAttempts": 3,
    "nextAttemptAt": "2026-10-07T12:00:30Z",
    "createdAt": "2026-10-07T12:00:00Z",
    "updatedAt": "2026-10-07T12:00:05Z",
    "finishedAt": null,
    "error": {
      "code": "TEMPORARY_INGESTION_FAILURE",
      "message": "摄取服务暂时不可用，请等待自动重试"
    }
  }
}
```

### 状态

- `QUEUED`：已保存原文/版本/job，等待 worker；不可检索。
- `PROCESSING`：worker 持有租约并执行；不可检索。
- `RETRYING`：本次失败但仍有额度，等待 `nextAttemptAt`；不可检索。
- `READY`：该版本满足全部启用管线的可检索条件，并已完成活动版本切换。
- `FAILED`：达到最大尝试次数或不可重试失败；保留原文和安全错误信息，不再自动重试。

### 阶段

`UPLOADED`、`CLAIMED`、`INGESTING`、`FINALIZING`、`READY`、`FAILED` 为契约建议有限值。前端必须把未知阶段当作可显示的处理中状态，而不是据此判定 READY。阶段不是状态替代品。

## 3. HTTP 语义

| 场景 | HTTP | 错误码/说明 |
|---|---:|---|
| 接受上传并创建/复用 job | 202 | `data.status=QUEUED` |
| 查询现有任务 | 200 | 返回完整状态 |
| 租户缺失/无效 | 400 | `TENANT_CODE_REQUIRED` 或租户校验错误 |
| 文件/参数校验失败 | 400 | `VALIDATION_FAILED`、`UNSUPPORTED_FILE_TYPE` |
| 文件超限 | 413 | `FILE_TOO_LARGE` |
| 任务/文档对当前租户不可见 | 404 | 统一 `DOCUMENT_INGESTION_JOB_NOT_FOUND`，不得区分不存在与跨租户 |
| 上传幂等冲突但已有任务可复用 | 202 | 返回已有 job；不得产生重复有效任务 |
| 持久化或服务内部故障 | 500 | `INTERNAL_ERROR`，不暴露数据库/配置细节 |

任务进入 `FAILED` 不等于查询请求失败：查询仍返回 `200`，失败原因位于 `data.error`。只有请求本身无效或资源不可见才使用 4xx。

## 4. 错误字段语义

`error.code` 是稳定、可编程的安全码；`error.message` 是面向开发者/用户的短摘要；可选 `details` 只能包含非敏感、结构化信息。禁止返回 API key、数据库密码、连接串、未经筛选的异常 message、堆栈或完整配置。失败任务至少暴露最后一次安全错误；无错误时 `error` 为 null。

## 5. 轮询建议

客户端收到 202 后保存 `jobId` 和 `statusUrl`，建议从 1 秒开始轮询，采用 1、2、4、8 秒退避并设置 30 秒上限；收到 `nextAttemptAt` 时不必在此前高频轮询。遇到 429/5xx 应继续退避。`READY` 或 `FAILED` 为终止状态；`QUEUED`、`PROCESSING`、`RETRYING` 继续轮询。客户端不得把上传响应中的 QUEUED 当成 READY，也不得因一次查询超时重复上传。
