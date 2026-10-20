# 契约：重排序模型资源与 ScoringModel 适配

**Feature**: `011-add-reranking` | **Date**: 2026-10-18

本文件定义租户级 `RERANK_MODEL` 资源、`ScoringModel` 适配器、流水线扩展与观测的最小契约。

## 1. 租户资源 `RERANK_MODEL`

### 1.1 配置 schema

```json
{
  "base-url": "https://api.siliconflow.cn/v1",
  "api-key": "<tenant-secret>",
  "model-name": "BAAI/bge-reranker-v2-m3",
  "timeout": "PT3S",
  "max-documents-per-batch": 32
}
```

| 字段 | 类型 | 必填 | 默认 | 约束 |
|---|---|---|---|---|
| `base-url` | string | 是 | — | http/https URL |
| `api-key` | string | 是 | — | 不得出现在日志、观测、错误信息、测试断言或版本控制中 |
| `model-name` | string | 是 | — | 首期 `BAAI/bge-reranker-v2-m3` |
| `timeout` | duration | 否 | `PT3S` | ISO-8601 Duration，> 0 |
| `max-documents-per-batch` | int | 否 | `32` | 1..100 |

### 1.2 注册与解析

- Provider：`RerankModelResourceProvider implements TenantResourceProvider<ScoringModel>`。
- `resourceType()`：`ResourceType.RERANK_MODEL`。
- `beanType()`：`ScoringModel.class`。
- `build(config)`：返回 `BgeRerankerScoringModel`（或后续其他实现）。
- 一个租户最多一个 primary；由 `TenantResourceRouter.getPrimary(tenantCode, RERANK_MODEL, ScoringModel.class)` 解析。
- 配置非法/加载失败：Registrar 记 ERROR，索引中无 primary；该租户按未配置处理，不影响启动与其他租户。
- `hasPrimary`：`TenantResourceRouter.hasPrimary(tenantCode, RERANK_MODEL)` 只读索引，不构建模型；用于是否扩大召回的快速判断。

### 1.3 凭据与隔离

- 每个租户独立配置；MUST NOT 使用全局单例模型或共享 key。
- 日志只允许出现租户、资源 key、模型名、状态；MUST NOT 出现 `api-key`、`Authorization`、请求体原文。
- 错误信息经 `Redaction.describe` 收敛后再进入观测/日志。

## 2. `ScoringModel` 适配器契约

实现 `dev.langchain4j.model.scoring.ScoringModel`：

```java
Response<List<Double>> scoreAll(List<TextSegment> segments, String query);
```

### 2.1 输入/输出

- 输入 `segments`：候选片段，顺序由调用方决定；适配器 MUST NOT 重排输入。
- 输出 `scores`：长度 MUST 等于 `segments.size()`，第 `i` 个分数对应第 `i` 个输入片段。
- 服务商返回的 `results` 可能按相关性排序；适配器 MUST 按 `results[].index` 回填，而不是按返回顺序直接返回。
- 缺失 `index`、`index` 越界、分数数量不匹配、分数为 `NaN`/`Infinity` → 抛异常（由上层 fail-open）。

### 2.2 HTTP 调用

首期适配 Cohere/SiliconFlow 风格：

```http
POST {base-url}/rerank
Authorization: Bearer {api-key}
Content-Type: application/json

{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "...",
  "documents": ["...", "..."],
  "top_n": 20
}
```

响应：

```json
{
  "results": [
    { "index": 0, "relevance_score": 0.9821 },
    { "index": 3, "relevance_score": 0.4210 }
  ]
}
```

- 请求 `top_n` 设为当前批次大小，避免服务端只返回部分候选。
- 若实际端点使用 TEI（`texts` + `[{index, score}]`），新增一个适配器实现即可，不改变阶段流水线。
- 分批：每批不超过 `max-documents-per-batch`；合并时保持全局输入顺序。
- 超时：使用配置的 `timeout`；超时按失败处理。
- 不为 `scoreAsync` / `doScoreAsync` 提供实现；同步路径足够，异步调用应显式失败。

## 3. 流水线扩展契约

`RerankEnhancement implements EnhancementExtension`：

| 属性 | 值 |
|---|---|
| `order()` | 固定值（例如 100），显式排序 |
| `stages()` | `Set.of(StageName.MERGE)` |
| `failurePolicy()` | `FailurePolicy.FAIL_OPEN` |
| `afterStage` | 读取 `MergeResult`，调用 `ScoringModel`，返回重排后的 `MergeResult` |

### 3.1 执行语义

```text
输入：MergeResult(request, chunks, searchedTables, failedTables)
  ├─ request.rerank=false 或 chunks.isEmpty() → 原样返回
  ├─ 租户无 RERANK_MODEL → 按原向量顺序截断到 request.topK()
  ├─ shadow=true：
  │     计算重排 → 写 RerankOutcome(shadow=true) → 按原向量顺序截断到 request.topK()
  └─ shadow=false：
        计算重排 → 按 rerankScore 降序、同分兜底 → 阈值过滤 → 截断到 request.topK()
        → 写 RerankOutcome(shadow=false) → 返回新的 MergeResult
```

- 任何异常（解析、HTTP、分数不匹配、非法值）→ 写 `RerankOutcome(status=DEGRADED)` → 返回**按原向量顺序截断到 `request.topK()`** 的 `MergeResult`（不能把扩大后的 `recallTopK` 候选原样透传）。
- `RerankOutcome` 写入 `StageContext.attributes`，键 `rerank.outcome`；每次请求独立。
- 不得修改 `MergeResult.request()`、`searchedTables`、`failedTables`。
- 不得在扩展里直接写观测表；观测由 `RerankObservationExtension` 负责。

### 3.2 排序与过滤

- 排序键：`rerankScore` 降序；同分 `documentId`、`pipelineKey`、`chunkIndex` 升序。
- 可选 `minScore`：仅当非 `null` 时过滤 `rerankScore < minScore`。
- 最终条数：`request.topK()`；不新增 `topN` 配置。
- 召回条数：`request.recallTopK()`；由服务层在启用重排时设为 `max(topK, configuredRecallTopK)`。

## 4. 观测契约

`RerankObservationExtension implements ObservationExtension`：

| 属性 | 值 |
|---|---|
| `stages()` | `Set.of(StageName.MERGE)` |
| `failurePolicy()` | `FailurePolicy.FAIL_OPEN` |
| `afterStage` | 读取 `ctx.get("rerank.outcome", RerankOutcome.class)`；存在则落 `RERANK` 行 |

记录字段：

| 列 | 值 |
|---|---|
| `stage` | `RERANK` |
| `status` | `SUCCESS` / `DEGRADED` |
| `hit_count` | `candidates` |
| `top_score` | `topScore` |
| `item_count` | `kept`（降级/回退时为实际返回的原向量条数，即 `min(candidates, topK)`） |
| `target_key` | `shadow`（观察模式）或 `null` |
| `error_message` | `reason`（已脱敏/截断） |
| `detail` | `RerankOutcome.detail`（仅 ID/分数/keep-drop） |

- 未配置重排/请求关闭：不写 `RERANK` 行；主链路阶段记录不变。
- `RERANK` 行与同一次调用的其他阶段共享 `trace_id`/`tenant_id`。
- 观测写库失败：沿用 `ObservationRecorder` 的 best-effort 降级，不影响主链路。

## 5. 指标契约

`ObservationMetrics` 新增：

- `unirag.rerank.requests`，标签 `tenant`、`status`（`SUCCESS`/`DEGRADED`）。
- `unirag.rerank.duration`，标签 `tenant`，Timer。

由 `RerankEnhancement` 在成功/失败路径记录；不依赖观测表开关。标签不得包含文档名、查询文本或模型 key。

## 6. 配置契约

```yaml
unirag:
  retrieval:
    rerank:
      enabled: true        # 全局开关；无租户模型时无效果
      shadow: false        # 观察模式：计算并记录，不改变返回
      recall-top-k: 20     # 内部召回数，上限 50；实际取 max(topK, 该值)
      min-score: 0.5       # 重排阈值；0.5 为首期保守起点，可按模型/语料校准
```

- `enabled=false`：所有租户视为未启用；不扩大召回、不调用模型。
- `shadow=true`：响应不变，`RERANK` 行 `target_key=shadow`。
- `recall-top-k` 越界（<1 或 >50）启动失败。
- `min-score` 非有限数启动失败；不强制 `[0,1]`，刻度由模型决定。
