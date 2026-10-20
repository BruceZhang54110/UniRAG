# Data Model: 检索结果重排序（Reranking）

**Feature**: `011-add-reranking` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

本文件定义重排序引入的领域对象、持久化变更与 API 字段。既有实体（`RetrievalRequest`、`MergeResult`、`QaAnswer`、`RetrievalObservation`）只做**向后兼容的扩展**，不重命名、不改变既有字段语义。

---

## 1. 领域模型

### 1.1 `RetrievedChunk`（扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `documentId` | `long` | 必填 | 文档标识 |
| `docName` | `String` | 可空 | 文档名 |
| `pipelineKey` | `String` | 必填 | 粒度/管线 key |
| `documentVersionId` | `Long` | 可空 | 异步摄取版本 |
| `chunkIndex` | `int` | 必填 | 片段序号 |
| `score` | `double` | `[0,1]` | **原向量相似度**，语义不变 |
| `text` | `String` | 必填 | 片段原文 |
| `rerankScore` | `Double` | 可空 | 重排相关性分数；未重排时为 `null` |

- 保留 7 参构造器：`rerankScore = null`，既有代码与测试无需改动。
- 新增 8 参规范构造器；`RerankEnhancement` 用 `withRerankScore(double)` 生成副本。
- 排序：重排启用时按 `rerankScore` 降序；同分按 `documentId`、`pipelineKey`、`chunkIndex` 升序。

### 1.2 `RetrievalRequest`（扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `query` | `String` | 非空，≤2000 | 查询文本 |
| `granularity` | `String` | 可空 | 粒度/管线 key |
| `documentId` | `Long` | 可空 | 单文档过滤 |
| `topK` | `int` | `1..50` | **最终**返回条数 |
| `minScore` | `double` | `[0,1]` | 向量召回下限 |
| `recallTopK` | `int` | `topK..50` | 内部召回条数 |
| `rerank` | `boolean` | — | 本次是否实际启用重排（服务层解析后的结果） |

- 保留 5 参构造器：`recallTopK = topK`、`rerank = false`（保持旧行为）；Web 层/服务层用 7 参构造器显式设置重排意图。
- 新增 `withRerank(boolean rerank, int recallTopK)`，由 `RetrievalService` / `QaService` 在解析租户资源后构造。
- Web 层 `RetrieveRequest` / `AskRequest` 增加可选 `Boolean rerank` 的同时保留 5 参构造器（`rerank = null`），既有测试与调用方无需改动。

### 1.3 `QaRequest`（扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `question` | `String` | 非空，≤2000 | 问题 |
| `granularity` | `String` | 可空 | 粒度 |
| `topK` | `int` | `1..50` | 最终引用/片段条数 |
| `minScore` | `double` | `[0,1]` | 向量召回下限 |
| `sessionId` | `String` | 可空 | 会话标识 |
| `rerank` | `Boolean` | 可空 | `null`=按租户默认；`false`=显式关闭；`true`=有模型则启用 |

- 保留 5 参构造器（`rerank = null`），既有测试/调用方无需改动。

### 1.4 `RerankOutcome`（新增，内存对象）

| 字段 | 类型 | 说明 |
|---|---|---|
| `candidates` | `int` | 送入重排的候选数 |
| `kept` | `int` | 重排后保留数 |
| `topScore` | `Double` | 重排最高分；无候选时为 `null` |
| `durationMs` | `long` | 重排耗时 |
| `status` | `StageStatus` | `SUCCESS` / `DEGRADED` |
| `reason` | `String` | 降级原因（成功时为 `null`） |
| `shadow` | `boolean` | 是否观察模式 |
| `detail` | `String` | 紧凑候选明细（ID/分数/保留标记） |

- 存放于 `StageContext.attributes`，键为 `rerank.outcome`；每次请求独立，不跨请求共享。
- `RerankObservationExtension` 读取后落 `RERANK` 观测行；`RerankEnhancement` 负责写入。

### 1.5 重排序模型资源（`RERANK_MODEL`）

租户级配置，存于既有 `tenant_resource_config.config` JSONB：

| 配置键 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `base-url` | string | 是 | — | 服务地址，如 `https://api.siliconflow.cn/v1` |
| `api-key` | string | 是 | — | 租户凭据；不进入日志/观测 |
| `model-name` | string | 是 | — | 首期固定 `BAAI/bge-reranker-v2-m3` |
| `timeout` | duration | 否 | `PT3S` | ISO-8601 Duration，单次 HTTP 超时 |
| `max-documents-per-batch` | int | 否 | `32` | 单次请求候选上限 |

- 一个租户最多一个 primary；由 `TenantResourceRouter.getPrimary` 解析。
- 配置非法/加载失败：Registrar 记 ERROR，`hasPrimary=false`，该租户按未配置处理；不得影响应用启动或其他租户。

---

## 2. 持久化变更

### 2.1 `retrieval_observation.detail`

新增 Flyway 迁移 `V9__retrieval_observation_detail.sql`：

```sql
ALTER TABLE retrieval_observation ADD COLUMN IF NOT EXISTS detail VARCHAR(2000);
```

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `detail` | `VARCHAR(2000)` | 是 | 重排候选明细：`docId/pipelineKey/chunkIndex=rerankScore(keep|drop)`，用 `;` 分隔；不含片段原文或密钥 |

- `RetrievalObservation` 实体新增 `detail` 字段；MyBatis-Plus `save` 自动写入。
- `ObservationRecorder` 新增 `record(ctx, result, detail)` 重载；写入前用 `Redaction.sanitize(detail, 2000)` 截断。
- 统一查询入口本轮不返回 `detail`；测试直接查表。
- retention 任务按 `created_at` 删除，无需变更。

### 2.2 观测行语义（`RERANK`）

| 列 | 取值 |
|---|---|
| `stage` | `RERANK`（新增 `StageName`） |
| `status` | `SUCCESS` / `DEGRADED` |
| `hit_count` | 候选数 |
| `top_score` | 重排最高分 |
| `item_count` | 保留数 |
| `target_key` | 观察模式为 `shadow`；正常为 `null` |
| `error_message` | 降级原因（已脱敏/截断） |
| `detail` | 候选明细（仅 ID/分数/保留标记） |

---

## 3. API 字段变更

### 3.1 `POST /api/v1/retrieve`

请求新增：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `rerank` | boolean | 否 | `null`=按租户默认；`false`=显式关闭；`true`=有模型则启用 |

响应 `chunks[]` 新增：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `rerankScore` | double | 是 | 重排相关性分数；未重排时为 `null` |

- `score` 仍为原向量相似度；启用重排时数组按 `rerankScore` 降序。
- `chunks` 数量 ≤ 请求 `topK`；召回数由内部配置决定。
- 未启用重排时响应与 010 契约逐字段一致（新增字段为 `null`/缺省）。

### 3.2 `POST /api/v1/ask`

请求新增：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `rerank` | boolean | 否 | 同上；`null`=按租户默认 |

响应 `citations[]` 新增：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `rerankScore` | double | 是 | 重排相关性分数 |

- `answer`、`degraded`、`degradeReason` 语义不变。
- 启用重排时引用顺序为 `rerankScore` 降序；未启用时保持原向量顺序。

### 3.3 兼容性

- 新增字段均可空/可选；既有调用方无需修改。
- `topK`、`minScore`、`score`、`citations` 的既有语义不变。
- 唯一行为变化：启用重排时数组顺序不再与 `score` 降序一致，而以 `rerankScore` 为准；契约中显式说明。

---

## 4. 状态与转换

```text
RERANK_MODEL 未配置 / 全局 disabled / 请求 rerank=false
  → 不扩大召回，不调用 ScoringModel，不产生 RERANK 观测行

RERANK_MODEL 已配置 + 请求未关闭
  → recallTopK = max(topK, configuredRecallTopK)
  → ScoringModel.scoreAll(...)
      ├─ 成功 → 排序/阈值/截断 → RERANK: SUCCESS
      │         ├─ shadow=false → 返回重排后的 MergeResult
      │         └─ shadow=true  → 返回原向量顺序截断到 topK（target_key=shadow）
      └─ 失败/模型缺失 → 原向量顺序截断到 topK → RERANK: DEGRADED（error_message=原因）
```

- `DEGRADED` 不影响检索/问答的 HTTP 成功语义。
- 所有路径都保持 `RERANK` 记录的 `trace_id` 与同一次调用一致（正常路径）；未配置时无记录，链路仍完整。

---

## 5. 校验规则

- `recallTopK`：`topK ≤ recallTopK ≤ MAX_TOP_K(50)`；越界抛 `VALIDATION_FAILED`（内部构造时）。
- `rerankScore`：可空；非空时必须为有限数；`NaN`/`Infinity` 视为重排失败并回退。
- `detail`：写入前截断到 2000 字符；只允许 ID、pipeline key、chunk index、分数、`keep/drop` 标记。
- `RERANK_MODEL` 配置：`base-url`/`api-key`/`model-name` 必填；`timeout` 为正 Duration；`max-documents-per-batch` 为正整数。
- `min-score`（重排）：默认 `0.5`，可空；不做 `[0,1]` 校验，因为重排模型分数刻度由提供商决定；非有限数视为配置错误。

---

## 6. 索引与规模

- `retrieval_observation` 新增列不建索引；查询仍走既有 `(tenant_id, trace_id)` / `(tenant_id, stage, status)` 索引。
- `detail` 最大 2000 字符，单行膨胀可控；retention（30 天）继续约束总量。
- 重排候选默认 20，最大 50；HTTP 请求体大小由 `max-documents-per-batch` 控制。
