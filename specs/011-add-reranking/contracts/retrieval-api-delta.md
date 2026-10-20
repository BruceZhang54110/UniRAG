# 契约增量：检索 / 问答接口的重排序字段

**Feature**: `011-add-reranking` | **Date**: 2026-10-18

本文件只定义相对 `specs/010-rag-retrieval/contracts/retrieval-api.md` 的**增量**。未在此列出的字段、信封、错误码、租户定位与降级语义，全部沿用 010 契约。

## 1. 通用

- 新增请求字段 `rerank`（可选 boolean）与响应字段 `rerankScore`（可选 double）。
- 未启用重排时，响应与 010 契约逐字段一致；`rerankScore` 缺省或为 `null`。
- 启用重排时，`chunks` / `citations` 的数组顺序按 `rerankScore` 降序；同分按 `(documentId, pipelineKey, chunkIndex)` 升序。
- `score` 始终表示**原向量相似度**，不因重排改变；调用方 MUST NOT 用 `score` 校验重排后的顺序。
- `topK` 始终表示调用方期望的**最终**条数；召回条数是服务端内部策略，不进入请求/响应。

## 2. POST /api/v1/retrieve

### 2.1 请求增量

```json
{
  "query": "《实时语音转写大模型》讲了什么？",
  "topK": 5,
  "rerank": true
}
```

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `rerank` | boolean | 否 | `null`/省略 = 按租户与全局默认；`false` = 显式关闭；`true` = 租户配置了重排序模型则启用 |

- `rerank=true` 但租户未配置 `RERANK_MODEL`：不报错，行为与未启用一致（不扩大召回、不调用重排）。
- `rerank=false`：不扩大召回、不调用重排，即使租户配置了模型。
- 全局 `unirag.retrieval.rerank.enabled=false`：所有租户视为 `false`。

### 2.2 响应增量

```json
{
  "data": {
    "traceId": "01J8Z...",
    "query": "《实时语音转写大模型》讲了什么？",
    "chunks": [
      {
        "documentId": 1457,
        "docName": "实时语音转写大模型",
        "pipelineKey": "main",
        "documentVersionId": 12,
        "chunkIndex": 0,
        "score": 0.8404,
        "rerankScore": 0.9821,
        "text": "## 科大讯飞实时语音转写大模型 ..."
      }
    ],
    "searchedTables": 1,
    "failedTables": []
  }
}
```

| 字段 | 类型 | 可空 | 语义 |
|---|---|---|---|
| `rerankScore` | double | 是 | 重排相关性分数；未重排时为 `null`。刻度由重排模型决定，MUST NOT 与向量 `score` 混用 |
| `score` | double | 否 | 原向量相似度，语义不变 |

- 数组条数 ≤ `topK`。
- 启用重排时，可能因 `minScore`（重排阈值）或最终截断而少于召回条数；这是正确行为。
- `failedTables` 语义不变；重排失败不写入 `failedTables`，而是通过 `RERANK` 观测行标记 `DEGRADED`。

### 2.3 失败与降级

| 场景 | 行为 |
|---|---|
| 重排序服务超时/不可达/限流/非 2xx | `200`，返回原向量顺序；`RERANK` 观测行 `DEGRADED`，`error_message` 为脱敏原因 |
| 重排序响应缺分数、`index` 越界、分数非有限 | 同上传失败：整批回退原向量顺序，`DEGRADED` |
| 重排后所有候选低于阈值 | `200`，`chunks: []`；不报错 |
| 租户未配置重排序模型 | `200`，行为与 010 契约一致；不产生 `RERANK` 观测行 |
| 观察模式 | `200`，响应与未启用重排一致；`RERANK` 行 `target_key=shadow`，`detail` 记录建议顺序 |

重排失败 MUST NOT 改变 HTTP 状态码，也 MUST NOT 让检索失败。

## 3. POST /api/v1/ask

### 3.1 请求增量

```json
{
  "question": "《实时语音转写大模型》讲了什么？",
  "topK": 5,
  "rerank": true
}
```

- `rerank` 语义与 §2.1 完全一致。
- 省略时按租户/全局默认；问答侧默认使用重排结果（若租户配置了模型）。

### 3.2 响应增量

```json
{
  "data": {
    "traceId": "01J8Z...",
    "answer": "该文档介绍了科大讯飞实时语音转写大模型……",
    "citations": [
      {
        "documentId": 1457,
        "docName": "实时语音转写大模型",
        "pipelineKey": "main",
        "chunkIndex": 0,
        "score": 0.8404,
        "rerankScore": 0.9821,
        "text": "## 科大讯飞实时语音转写大模型 ..."
      }
    ],
    "degraded": false,
    "degradeReason": null
  }
}
```

- `citations` 只包含进入提示词并实际引用的片段；重排排除的片段不会出现。
- `rerankScore` 可空；`score` 语义不变。
- `degraded` / `degradeReason` 仍只表达问答降级（检索为空、模型不可达、模型空输出），不因重排失败而置为 `true`；重排失败在观测中体现。

### 3.3 失败与降级

与 §2.3 相同。重排失败时问答仍按原向量顺序组装提示词并返回答案；`RERANK` 观测行 `DEGRADED`。

## 4. 错误码

本增量**不新增**错误码：

- 请求字段类型错误 → 既有 `VALIDATION_FAILED`。
- 租户/资源错误 → 既有 `TENANT_CODE_REQUIRED` / `TENANT_NOT_FOUND`。
- 重排服务故障 → 不作为 HTTP 错误返回；按 fail-open 处理。

## 5. 兼容性声明

- 新增字段均可选/可空；旧客户端无需修改。
- 既有调用方若按 `score` 降序校验 `chunks`，在 `rerank=true` 时需要改为按数组顺序或 `rerankScore`；这是本 feature 唯一有感知的行为变化，契约中已显式说明。
- 未启用重排的租户与请求，响应字段、顺序、错误码与 010 契约完全一致。
