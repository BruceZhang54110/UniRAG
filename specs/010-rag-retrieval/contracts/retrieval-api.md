# 契约：检索 / 问答 / 观测 HTTP 接口

**Feature**: `specs/010-rag-retrieval/` | **Date**: 2026-10-14

本契约定义本期新增的 3 个 HTTP 端点。沿用 003/007 已确立的通用约定，**不新造信封格式**。

## 1. 通用约定（复用既有契约）

| 约定 | 规则 |
|---|---|
| 租户定位 | 请求头 `X-Tenant-Code`，由既有 `TenantContextInterceptor` 解析并落到 `TenantContext`（该组件 javadoc 已载明供 006/010 复用）。缺失 → `400 TENANT_CODE_REQUIRED`；未知 → `404 TENANT_NOT_FOUND`。**MUST NOT** 从请求体或查询参数推断租户 |
| 成功信封 | `{"data": <payload>}`（`com.unirag.upload.support.ApiResponse`） |
| 错误信封 | `{"error": {"code": "...", "message": "...", "details": <可选>}}`（`GlobalExceptionHandler`） |
| 内容类型 | `application/json; charset=utf-8` |
| 敏感信息 | 任何 `message` / `details` MUST NOT 含 api-key、密码、配置原文；`details` 只允许出现字段名、资源 key、表名、租户码等定位信息 |
| 端点注册 | 新增错误码 MUST 同步登记进 `GlobalExceptionHandler.statusOf` 的 switch，否则会退化为 500 |

## 2. POST /api/v1/retrieve —— 向量检索（FR-001）

**请求体**

```json
{
  "query": "原文文件存在哪里？",
  "granularity": "main",
  "documentId": 620,
  "topK": 5,
  "minScore": 0.0
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `query` | string | 是 | 去空白后非空；长度上限由 plan 常量定义（超限 → `400 VALIDATION_FAILED`） |
| `granularity` | string | 否 | 摄取管线 key **或** 向量存储资源 key。省略 = 检索该租户**全部已启用管线**对应的向量表（FR-003）。给定但不存在或指向未启用管线 → `400 GRANULARITY_NOT_FOUND`，`details.available` 列出可用粒度（**MUST NOT** 静默退化为全量检索） |
| `documentId` | long | 否 | 限定单个文档；不存在于本租户 → 结果为空（**不**报错，避免探测他人文档存在性） |
| `topK` | int | 否 | 默认 5，上限 50；越界 → `400 VALIDATION_FAILED` |
| `minScore` | double | 否 | 省略时取 `unirag.retrieval.default-min-score`（默认 `0.70`，按 demo 语料校准，可用 `UNIRAG_DEFAULT_MIN_SCORE` 覆盖）；分数语义为 `(1 + cosine_similarity) / 2`，`0.70` 约等于余弦相似度 `0.40` |

**响应体**

```json
{
  "data": {
    "traceId": "01J8Z...",
    "query": "原文文件存在哪里？",
    "granularity": null,
    "chunks": [
      {
        "documentId": 620,
        "docName": "demo-doc-1",
        "pipelineKey": "main",
        "documentVersionId": 12,
        "chunkIndex": 0,
        "score": 0.4204,
        "text": "# UniRAG 演示文档 ..."
      }
    ],
    "searchedTables": 1,
    "failedTables": []
  }
}
```

| 字段 | 说明 |
|---|---|
| `traceId` | 本次调用的观测链路标识，可凭它在 `GET /api/v1/observations` 查出各阶段记录 |
| `chunks` | 按 `score` 降序；分数相同按 (documentId, pipelineKey, chunkIndex) 升序 —— **同输入必得同输出**（FR-003 确定性） |
| `searchedTables` / `failedTables` | 归并前实际检索的表数与失败表清单；单表失败不影响整体（`failedTables` 记录原因摘要，FR-009） |

**边界语义**

- 租户没有任何已启用管线 → `200`，`chunks: []`，`details`/附加字段说明"无可用向量表"（**不**报错，对应 spec 边界情况）。
- 检索命中的片段 MUST 全部属于发起租户（SC-002）。

## 3. POST /api/v1/ask —— RAG 问答（FR-004）

**请求体**

```json
{
  "question": "原文文件存在哪里？",
  "granularity": "main",
  "topK": 5,
  "minScore": 0.0,
  "sessionId": "optional-client-session-id"
}
```

字段约束与 §2 一致；`sessionId` 为可选透传标识，**本期不持久化对话记忆**（spec 明确不在本期范围），仅回填进观测记录便于关联同会话调用。

**响应体**

```json
{
  "data": {
    "traceId": "01J8Z...",
    "answer": "原文通过 OpenDAL 写入 PostgreSQL 的 bytea 键值表……",
    "citations": [
      { "documentId": 620, "docName": "demo-doc-1", "pipelineKey": "main",
        "chunkIndex": 0, "score": 0.4204, "text": "…" }
    ],
    "degraded": false,
    "degradeReason": null
  }
}
```

**降级语义（FR-005）**

| 场景 | 行为 |
|---|---|
| 检索结果为空 | `200`，`answer` = 明确的"知识库中未找到相关内容"文案，`citations: []`，`degraded: true`，`degradeReason: "NO_CONTEXT"`。**MUST NOT** 让模型在无上下文时自由作答 |
| 检索有结果但聊天模型不可达/超时 | `502 LLM_UNAVAILABLE`，`message` 为可读原因（不含密钥）；本次调用的观测记录 MUST 记下失败阶段与原因 |
| 检索有结果、模型返回空内容 | `502 LLM_EMPTY_RESPONSE`（**MUST NOT** 把模型空输出当作正常回答返回） |

`citations` 的顺序 MUST 与提示词中引用标注的顺序一致，便于用户对照。`citations` 只包含回答中以 `[n]` 实际标注引用到的片段（保持相对顺序）；回答没有任何有效标注时回退为全部检索片段。

## 4. GET /api/v1/observations —— 统一观测查询入口（FR-006）

**查询参数**（全部可选，组合过滤）

| 参数 | 说明 |
|---|---|
| `traceId` | 按链路标识查（一次问答的全部阶段） |
| `documentId` | 按文档查（可关联 005 摄取侧记录） |
| `stage` | 阶段名，如 `QUERY_EMBEDDING` / `VECTOR_SEARCH` / `MERGE` / `PROMPT_ASSEMBLY` / `CHAT_GENERATION` / `INGEST` |
| `status` | `SUCCESS` / `FAILED` / `DEGRADED` |
| `from` / `to` | ISO-8601 时间范围 |
| `page` / `size` | 默认 1 / 20，`size` 上限 100 |

**响应体**

```json
{
  "data": {
    "items": [
      {
        "traceId": "01J8Z...",
        "tenantCode": "demo",
        "stage": "VECTOR_SEARCH",
        "targetKey": "embeddings_demo",
        "status": "SUCCESS",
        "durationMs": 18,
        "hitCount": 1,
        "topScore": 0.4204,
        "errorMessage": null,
        "createdAt": "2026-10-14T21:35:02Z"
      }
    ],
    "total": 37,
    "page": 1,
    "size": 20
  }
}
```

**约束**

- 严格按租户隔离：只返回发起租户的记录。
- 本入口 MUST 同时能查到 005 摄取侧的执行记录（统一观测模型，见 `research.md` R3 的落地方案）。
- `errorMessage` MUST 已截断且不含密钥/配置原文（FR-008）。

## 5. 错误码

**新增**

| code | HTTP | 触发 |
|---|---|---|
| `GRANULARITY_NOT_FOUND` | 400 | `granularity` 不存在或指向未启用管线；`details.available` 给出可用粒度 |
| `LLM_UNAVAILABLE` | 502 | 租户聊天模型不可达 / 超时 / 配置失效 |
| `LLM_EMPTY_RESPONSE` | 502 | 聊天模型返回空内容 |

**复用既有**：`TENANT_CODE_REQUIRED`(400)、`TENANT_NOT_FOUND`(404)、`VALIDATION_FAILED`(400)、`INTERNAL_ERROR`(500)。

## 6. 与既有约定的关系

- 三个端点均**无状态**（本期不做会话记忆持久化），不引入新的鉴权机制。
- 与 007 的上传/下载接口同处 `/api/v1` 命名空间，共享同一套租户定位与错误模型。
