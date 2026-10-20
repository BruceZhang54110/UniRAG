# Research: 检索结果重排序（Reranking）

**Feature**: `011-add-reranking` | **Date**: 2026-10-18 | **Input**: [spec.md](./spec.md)

本文件解决 plan 阶段的全部技术未知项。所有决策都以“复用既有阶段流水线/租户资源/观测模型”为前提，不引入新的编排框架。

---

## R1. 重排序接入方式：`ScoringModel`，而不是 `ReRankingContentAggregator`

**Decision**：使用 `langchain4j-core` 1.20.0 的 `dev.langchain4j.model.scoring.ScoringModel`，在自有阶段流水线中直接调用 `scoreAll(List<TextSegment>, String query)`。不引入 `DefaultRetrievalAugmentor` / `AiServices`，也不把 `ReRankingContentAggregator` 作为主路径。

**Rationale**：

- `ScoringModel` 是 LangChain4j 对“查询—片段相关性打分”的最小抽象；`scoreAll` 的契约是“返回分数顺序与输入 `segments` 顺序一致”，正好适配 `MergeResult.chunks()`。
- `ReRankingContentAggregator` 面向 `ContentAggregator`/`ContentRetriever` 的 `Map<Query, Collection<List<Content>>>` 输入，需要把 `RetrievedChunk` 包装成 `Content` 再解包，且会改变 metadata；对当前 `MergeResult` 是多余的适配层。
- 010 已明确“问答生成侧 MUST NOT 依赖 `AiServices`/`RetrievalAugmentor` 高层编排”（FR-012），保持自研流水线才能继续按阶段观测。
- `ScoringModel` 的异步方法 `scoreAsync` 默认返回 `AsyncNotSupportedException`；当前阶段流水线是同步调用，只实现 `scoreAll` 即可，不伪装异步能力。

**Alternatives considered**：

- `ReRankingContentAggregator`：官方示例的推荐路径，适合 `AiServices`；与本项目流水线不匹配。否决。
- 自定义 `ContentAggregator`：仍要引入 `Content`/`Query` 概念，收益不足。否决。
- 直接调用 HTTP 服务而不实现 `ScoringModel`：会把协议细节散落到阶段里，难以替换提供商、难以用替身测试。否决。

---

## R2. 首期重排序模型与客户端：`BAAI/bge-reranker-v2-m3`

**Decision**：首期主用模型为 `BAAI/bge-reranker-v2-m3`。实现一个 `BgeRerankerScoringModel implements ScoringModel`，调用租户配置的 Cohere/SiliconFlow 风格 `POST {base-url}/rerank`：

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "...",
  "documents": ["...", "..."],
  "top_n": 20
}
```

响应按 `results[].index` 映射回输入顺序，读取 `results[].relevance_score`。若实际租户使用 TEI（`texts` + `[{index, score}]`）或其他协议，只替换该适配器，不改变阶段流水线。

**Rationale**：

- 项目当前 `demo` / `demo2` 的嵌入与聊天模型都接在 SiliconFlow（`https://api.siliconflow.cn/v1`），同一账号体系下接入 rerank 的运维成本最低。
- `BAAI/bge-reranker-v2-m3` 是 cross-encoder 重排序模型，适合“查询—片段”二分类相关性打分，和向量检索的 bi-encoder 形成互补。
- `langchain4j-core` 只提供 `ScoringModel` 接口，没有 BGE/ SiliconFlow 的现成实现；自带 `CohereScoringModel` 需要额外依赖且模型不是 BGE。自定义适配器是必要的最小实现。
- 适配器只依赖 Spring Web 已带的 `RestClient`，不新增第三方依赖。

**Constraints**：

- `scoreAll` 返回的 `List<Double>` 必须与输入 `TextSegment` 顺序一致；服务商返回的 `results` 通常按相关性排序，必须用 `index` 回填，不能直接返回。
- 单次请求的候选数可能超过服务商批量上限；按 `max-documents-per-batch`（默认 32）分批，合并后保持输入顺序。
- 超时、限流、HTTP 非 2xx、响应缺字段、`index` 越界、分数数量不完整 → 抛出统一的重排异常，由上层按 fail-open 回退。
- 不实现 `scoreAsync` / `doScoreAsync`；同步路径足够，异步路径明确不可用。
- 不在日志中输出请求体（含文档原文）或 `Authorization`。

**Alternatives considered**：

- `CohereScoringModel`：现成实现，但需要 `langchain4j-cohere` 依赖、Cohere 账号，且模型不是用户指定的 BGE。仅作为兼容备选。
- 自建 TEI 服务：无外部 key，但需要额外部署与运维；接口仍通过同一个 `ScoringModel` 适配器接入，首期不作为默认。
- 不接重排序、只用相对阈值：无法稳定区分“话题相似”和“真正回答”，见 spec P1。否决。

---

## R3. 流水线接入点：`MERGE` 阶段的增强型扩展

**Decision**：新增 `RerankEnhancement implements EnhancementExtension`，只作用于 `StageName.MERGE` 的 `afterStage`。它读取 `MergeResult`，调用租户的 `ScoringModel`，返回重排后的 `MergeResult`；失败时返回原 `MergeResult`（fail-open）。

**Rationale**：

- 010 FR-011 明确“重排以扩展点形式预留”，`EnhancementExtension` 正是为“重排、过滤、提示词增强”设计的可改写扩展；新增核心阶段会破坏“新增策略不改变检索主流程”的约定。
- `MERGE` 之后是唯一同时具备“完整候选集 + 查询文本 + 请求参数”的位置；放在 `VECTOR_SEARCH` 之后会逐表重排，无法跨表统一比较；放在 `PROMPT_ASSEMBLY` 之后已经无法影响提示词。
- 扩展的 `failurePolicy()` 返回 `FAIL_OPEN`：重排是可选优化，失败不得升级为主链路失败。
- 扩展顺序显式声明（`order()` 取一个较大的固定值），避免与其他增强型扩展竞争；当前只有这一个增强型扩展。

**Alternative: 新增 `RerankStage`**：观测更直接，但会改变核心阶段链（5 → 6 段），与 010 FR-011 的“扩展点”约定冲突；否决。观测通过 `RerankObservationExtension` 补足（见 R6）。

---

## R4. 召回数与最终条数分离：内部 `recallTopK`

**Decision**：`RetrievalRequest` 增加内部字段 `int recallTopK` 与 `boolean rerank`；`VectorSearchStage` 的 `maxResults`、`MergeStage` 的归并上限使用 `recallTopK`，`RerankEnhancement` 最终截断到调用方请求的 `topK`。

- `topK` 对外语义不变：调用方期望的最终条数。
- 召回数由配置 `unirag.retrieval.rerank.recall-top-k`（默认 20，上限 50）决定；实际值取 `max(topK, recallTopK)`。
- 只有“租户配置了 `RERANK_MODEL` 且未被请求关闭”时才扩大召回；未配置/未启用的租户 `recallTopK = topK`，行为与现在完全一致。
- `RetrievalService` / `QaService` 通过新增的 `RerankSupport.enabledFor(tenantCode)` 判断是否扩大召回；该方法只查资源索引，不构建模型。

**Rationale**：

- 若沿用 `topK` 同时作为召回和最终条数，`topK=5` 时重排只有 5 条候选，无法体现“先多召回、再精排”的价值（spec FR-002）。
- 若对所有租户都扩大召回，未配置重排的租户会多付向量检索延迟，违反 spec SC-006 的“行为与改造前一致”与“不产生额外延迟”。
- 不把 `recallTopK` 暴露到 HTTP 请求，避免调用方误以为它是新的结果条数；它是系统内部策略。

**Implementation notes**：

- `RetrievalRequest` 保留 5 参构造器（`recallTopK = topK`、`rerank = false`，保持旧行为），新增 7 参规范构造器；Web 层/服务层显式设置重排意图，避免直接调用方被动启用。
- `RetrieveRequest` / `AskRequest` 增加可选 `Boolean rerank`：`null` 表示按租户/全局默认，`false` 显式关闭，`true` 表示“若租户配置了模型则启用”。
- `TenantResourceRouter` 增加 `boolean hasPrimary(String tenantCode, ResourceType type)`，实现为 `indexRegistry.get().primaryKey(tenantCode, type).isPresent()`；这是对既有索引的只读查询，不新增抽象。

**Alternatives considered**：

- 只重排现有 `topK`：候选太少，效果有限。否决。
- 把 `topK` 改成召回数、另加 `topN` 作为最终条数：破坏既有 API 语义。否决。
- 对全部租户固定扩大召回：增加无谓延迟，且未配置租户会看到行为变化。否决。

---

## R5. 分数与 API 契约：保留向量 `score`，新增可选 `rerankScore`

**Decision**：

- `RetrievedChunk` 增加可空字段 `Double rerankScore`；7 参构造器保留，默认 `null`。
- `/retrieve` 与 `/ask` 的 `chunks`/`citations` 增加可选字段 `rerankScore`。
- `score` 继续表示原向量相似度，不因重排改变语义。
- 启用重排时，数组顺序按 `rerankScore` 降序；同分以 `(documentId, pipelineKey, chunkIndex)` 升序兜底。未启用时行为不变。
- `/retrieve` 请求增加可选 `Boolean rerank`；`/ask` 同样增加，便于显式关闭。
- `topK` 继续表示最终条数；召回数是内部配置，不进入响应。

**Rationale**：

- 若把 `score` 改成重排分数，现有调用方会把重排分数误当成向量相似度，且 `minScore` 语义失效；违反“字段语义不变”的兼容目标。
- 若完全不暴露 `rerankScore`，排障与灰度无法解释“为什么顺序变了”；新增可空字段是向后兼容的扩展。
- 数组顺序与 `score` 不再严格同序，是启用重排后的预期变化；契约必须显式写明，避免调用方仍按 `score` 校验顺序。

**Alternatives considered**：

- 用重排分数覆盖 `score`：破坏兼容，否决。
- 不暴露重排分数、只改顺序：可观测性差，否决。
- 新增独立的 `/rerank` 端点：增加接口面积，且无法复用检索/问答链路；本期不做。

---

## R6. 失败语义、灰度与观测

**Decision**：

- 重排失败一律 fail-open：`RerankEnhancement` 捕获异常，返回原向量顺序并**截断到 `request.topK()`**（因为启用重排时 `MergeResult` 可能已扩大到 `recallTopK`）；`RerankOutcome` 记为 `DEGRADED`，原因经 `Redaction` 收敛。
- 新增 `StageName.RERANK`，由 `RerankObservationExtension`（观察型扩展，`stages()={MERGE}`）读取 `ctx.attributes` 中的 `RerankOutcome` 并落一条 `RERANK` 记录：
  - `hit_count` = 候选数
  - `top_score` = 重排最高分
  - `item_count` = 保留数
  - `target_key` = `shadow`（观察模式）或 `null`
  - `error_message` = 降级原因（成功时为 `null`）
- 观察模式（`unirag.retrieval.rerank.shadow=true`）：照常计算重排并记录 `RERANK`，但返回**按原向量顺序截断到 `request.topK()`** 的 `MergeResult`，确保响应与未启用重排时一致（不能把 `recallTopK` 的扩大候选透传）。
- 新增 `retrieval_observation.detail` 可空列（Flyway V9），保存紧凑的候选顺序/分数/保留标记（只含文档 ID、pipeline key、chunk index、分数；不含片段原文或密钥），用于灰度对比与排障。
- `ObservationMetrics` 增加 `unirag.rerank.requests`（按租户/状态）与 `unirag.rerank.duration`（按租户）计时器；由 `RerankEnhancement` 直接记录，不依赖观测表是否开启。

**Rationale**：

- spec FR-006/FR-011 要求失败不拖垮主链路，并能在观察模式下留下建议顺序；现有 `StageResult` 字段不足以表达“顺序”，新增 `detail` 列是最小持久化方案。
- 复用 `StageName`/`StageStatus`/`ObservationRecorder`/`ObservationMetrics`，不新建观测体系；`RerankObservationExtension` 与既有 `RecordingObservationExtension` 并列，职责单一。
- `detail` 只放标识与分数，不放原文，避免把敏感内容写入观测表；`ObservationRecorder` 复用 `Redaction.sanitize` 与长度上限。

**Alternatives considered**：

- 把建议顺序塞进 `error_message`：语义错误、长度不足；否决。
- 新增独立 `retrieval_rerank_candidate` 表：高基数、清理复杂；否决。
- 只在进程日志记录顺序：不满足“持久化观测”的既有约束；否决。

---

## R7. 观测数据与迁移

**Decision**：新增 `V9__retrieval_observation_detail.sql`：

```sql
ALTER TABLE retrieval_observation ADD COLUMN IF NOT EXISTS detail VARCHAR(2000);
```

- `RetrievalObservation` 实体增加 `detail` 字段；MyBatis-Plus `save` 自动写入。
- `ObservationRecorder` 增加 `record(ctx, result, detail)` 重载，`record(ctx, result)` 委托 `detail=null`。
- 统一查询入口暂不返回 `detail`（不改变既有 API）；测试直接查表断言。
- 保留既有 retention 任务，不需要改清理逻辑。

**Rationale**：`detail` 是可选排障/灰度字段，不改变既有查询契约；`VARCHAR(2000)` 足以容纳 20–30 条候选的紧凑表示，且可截断。

---

## R8. 测试策略

**Decision**：

- **阶段/扩展单测**：`RerankEnhancementTest` 用 `ScoringModel` 替身验证排序、截断、`minScore`、shadow、无模型跳过、异常回退、确定性兜底。
- **适配器单测**：`BgeRerankerScoringModelTest` 用本地 HTTP 桩（Spring `MockRestServiceServer` 或 JDK `HttpServer`）验证 `index` 回填、分批、缺失分数/非 2xx/超时的失败路径；不得访问外网。
- **集成测试**：`RerankApiIT` 走真实 PG + pgvector，经 `@MockBean TenantResourceRouter` 返回替身 `ScoringModel`，并 stub `hasPrimary`；断言 `/retrieve` 顺序与 `rerankScore`、`/ask` 引用集合、无模型租户行为不变、跨租户隔离。
- **观测测试**：断言 `RERANK` 行的 `hit_count`/`item_count`/`status`/`target_key`/`detail`；失败场景断言 `DEGRADED` 且主链路成功。
- **回归**：既有 `*IT` 在没有 `RERANK_MODEL` 时 `hasPrimary=false`（Mockito 默认），行为与改造前一致；无需改既有断言。
- **测试环境**：不配置真实 reranker key；适配器 HTTP 单测使用本地桩，集成测试使用 `ScoringModel` 替身。符合宪法原则 III。

**Alternatives considered**：

- 集成测试直连 SiliconFlow：违反“测试不依赖外部凭据”。否决。
- 只测 `ScoringModel` 接口、不测 HTTP 适配器：无法覆盖 `index` 映射与分批，风险高。否决。

---

## R9. 配置与默认值

**Decision**：新增 `RerankProperties`（`unirag.retrieval.rerank`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 全局开关；无租户模型时仍无效果 |
| `shadow` | `false` | 观察模式：计算并记录，不改变返回 |
| `recall-top-k` | `20` | 召回候选数；实际取 `max(topK, recallTopK)`，上限 50 |
| `min-score` | `0.5` | 重排分数阈值；0.5 为首期保守起点，可按模型/语料校准；null 表示只用最终条数 |
| `top-n` | 不设 | 最终条数沿用请求 `topK`，不新增配置 |

租户级 `RERANK_MODEL` 配置：

| 字段 | 必填 | 说明 |
|---|---|---|
| `base-url` | 是 | 例如 `https://api.siliconflow.cn/v1` |
| `api-key` | 是 | 租户级凭据；不落代码/日志 |
| `model-name` | 是 | `BAAI/bge-reranker-v2-m3` |
| `timeout` | 否 | 默认 `PT3S`（ISO-8601） |
| `max-documents-per-batch` | 否 | 默认 32 |

**Rationale**：与既有 `EMBEDDING_MODEL`/`LLM` 配置风格一致；`recall-top-k` 默认 20 与 spec 的“20–30”一致；`min-score` 默认 `0.5`，作为 BGE reranker relevance_score 的首期保守起点，避免弱相关尾部在候选不足 topK 时全部透传；仍可按模型/语料校准或设为 null 关闭。

---

## R10. 未采用方案汇总

| 方案 | 否决理由 |
|---|---|
| `DefaultRetrievalAugmentor` + `ReRankingContentAggregator` | 与自研阶段流水线/观测模型冲突；需要 `Content`/`Query` 包装，metadata 易丢 |
| 新增 `RerankStage` | 改变核心阶段链，违背 010 FR-011 的扩展点约定；观测用观察型扩展即可补足 |
| 复用 `topK` 作为召回数 | 候选太少，重排价值有限 |
| 对所有租户扩大召回 | 未配置租户多付延迟，违反行为不变 |
| 用重排分数覆盖 `score` | 破坏既有字段语义与 `minScore` 兼容 |
| 直连外部 reranker 做测试 | 违反无外部凭据的测试约束 |
| 只在日志记录 shadow 顺序 | 不满足持久化观测约束 |
