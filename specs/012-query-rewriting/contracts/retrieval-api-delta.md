# 契约增量：检索/问答 API 的 `rewrite` 开关

**Feature**: `012-query-rewriting` | **Date**: 2026-10-19 | **Base**: `specs/010-rag-retrieval/contracts/retrieval-api.md`

本文件只描述增量。端点、响应包装、错误码、租户解析方式与既有契约保持一致。

## 1. `POST /api/v1/retrieve`

### 1.1 请求体（新增可选字段）

```json
{
  "query": "那个把文件切块的玩意儿怎么配？",
  "granularity": "main",
  "documentId": null,
  "topK": 5,
  "minScore": 0.7,
  "rerank": true,
  "rewrite": true
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `rewrite` | boolean | 否 | `true` | **新增**。`true`：租户配置了改写资源时启用；`false`：本次显式关闭；省略等同 `true` |

- 既有 5/6 字段请求体完全兼容；省略 `rewrite` 不改变原有调用方行为（无改写资源时仍不启用）。
- `rewrite` 与 `rerank` 独立：可单独开启/关闭，也可同时开启。

### 1.2 响应

**结构不变。** `data.query` 始终是请求中的原始 `query`，MUST NOT 回显改写后的检索查询。

```json
{
  "data": {
    "traceId": "8f3c...",
    "query": "那个把文件切块的玩意儿怎么配？",
    "granularity": "main",
    "chunks": [
      {
        "documentId": 42,
        "docName": "摄取配置说明",
        "pipelineKey": "main",
        "documentVersionId": 7,
        "chunkIndex": 3,
        "score": 0.82,
        "rerankScore": 0.91,
        "text": "……"
      }
    ],
    "searchedTables": 1,
    "failedTables": []
  }
}
```

### 1.3 行为

1. `rewrite=true` 且租户有可用改写资源：先把原问题改写为一条检索查询，用它做向量检索；响应 `query`、`chunks` 的来源元数据与既有语义不变。改写资源默认复用该租户的主语言模型（对话/查询大模型），只有显式配置专用连接时才使用独立模型。
2. `rewrite=false`：不调用改写模型，直接用原问题检索。
3. 租户无改写资源：无论 `rewrite` 取值，均不调用改写模型，行为与改造前逐字段一致。
4. 改写失败/输出无效：回退原问题继续检索；HTTP 状态仍为 200（除非检索本身失败），失败原因只进入观测记录。
5. 改写不改变 `granularity` / `documentId` / `topK` / `minScore` 的过滤语义。

## 2. `POST /api/v1/ask`

### 2.1 请求体（新增可选字段）

```json
{
  "question": "那个把文件切块的玩意儿怎么配？",
  "granularity": null,
  "topK": 5,
  "minScore": 0.7,
  "sessionId": "session-1",
  "rerank": true,
  "rewrite": true
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `rewrite` | boolean | 否 | `true` | **新增**；`false` 显式关闭本次改写 |

- `sessionId` 仍只透传，不参与改写；本期不做多轮压缩。
- `rerank` 语义不变。

### 2.2 响应

**结构不变。**

```json
{
  "data": {
    "traceId": "8f3c...",
    "answer": "可以在摄取管线里配置 max-segment-tokens 与 overlap-tokens……[1]",
    "citations": [
      {
        "documentId": 42,
        "docName": "摄取配置说明",
        "pipelineKey": "main",
        "documentVersionId": 7,
        "chunkIndex": 3,
        "score": 0.82,
        "rerankScore": 0.91,
        "text": "……"
      }
    ],
    "degraded": false,
    "degradeReason": null
  }
}
```

### 2.3 关键不变量

1. **提示词中的问题**：`PromptAssemblyStage` 的“问题：”后 MUST 是请求中的 `question` 原文，MUST NOT 是改写文本。
2. **引用与回答**：基于改写后的检索查询召回片段，但回答围绕原问题生成；引用结构、编号、收窄逻辑不变。
3. **重排**：若同时启用重排，`ScoringModel.scoreAll` 的 query 参数 MUST 是原问题。
4. **失败**：改写超时/不可达/非法输出不影响问答返回；`degraded` 只表示检索为空，不因改写失败而置真。

## 3. 观测查询

一次启用改写的调用会产生一行独立的 `QUERY_REWRITE` 记录，与其他阶段共享 `trace_id`：

```sql
SELECT stage, status, target_key, duration_ms, hit_count, item_count, error_message, detail
FROM retrieval_observation
WHERE trace_id = ? AND stage = 'QUERY_REWRITE';
```

- 成功：`status='SUCCESS'`，`target_key` 为改写资源 key，`detail` 含 `shadow=false` 与脱敏摘要。
- 降级：`status='DEGRADED'`，`error_message` 为原因码/脱敏异常摘要；主链路仍返回正常结果。
- 未启用/无资源：查询结果为空。

## 4. 兼容性

- 新增字段全部可选，旧客户端无需改动。
- 响应体不新增字段，不改变字段语义。
- 错误码集合不变；改写相关失败不新增对外错误码。
- `RetrievalRequest` / `QaRequest` 保留既有构造器，既有测试与内部调用方可继续编译运行。
