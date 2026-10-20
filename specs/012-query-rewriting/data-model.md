# Data Model: 查询改写（Query Rewriting）

**Feature**: `012-query-rewriting` | **Date**: 2026-10-19 | **Spec**: [spec.md](./spec.md)

本文件定义查询改写引入的领域对象、配置对象与 API 字段。既有实体只做**向后兼容的扩展**，不重命名、不改变既有字段语义；本 feature **不新增数据库表/列**。

---

## 1. 领域模型

### 1.1 `RetrievalRequest`（扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `query` | `String` | 非空，≤2000 | **用户原始问题**；提示词、引用、重排、响应回显一律使用该字段 |
| `granularity` | `String` | 可空 | 粒度/管线 key；语义不变 |
| `documentId` | `Long` | 可空 | 单文档过滤；语义不变 |
| `topK` | `int` | `1..50` | 最终返回条数；语义不变 |
| `minScore` | `double` | `[0,1]` | 向量召回下限；语义不变 |
| `recallTopK` | `int` | `topK..50` | 内部召回条数；语义不变 |
| `rerank` | `boolean` | — | 本次是否启用重排；语义不变 |
| `rewrite` | `boolean` | — | **新增**：本次是否允许/实际启用查询改写（服务层解析后的值） |
| `retrievalQuery` | `String` | 非空，默认等于 `query`；改写文本 ≤500 | **新增**：实际送入嵌入模型的检索文本 |

**构造器（保持源码兼容）**：

| 构造器 | 行为 |
|---|---|
| `(query, granularity, documentId, topK, minScore)` | `recallTopK = topK`、`rerank = false`、`rewrite = false`、`retrievalQuery = query` |
| `(query, granularity, documentId, topK, minScore, recallTopK, rerank)` | 011 构造器；`rewrite = false`、`retrievalQuery = query` |
| `(query, granularity, documentId, topK, minScore, recallTopK, rerank, rewrite)` | 服务层使用；`retrievalQuery = query` |
| `(query, granularity, documentId, topK, minScore, recallTopK, rerank, rewrite, retrievalQuery)` | 规范构造器 |

**副本方法**：

- `withRerank(boolean rerank, int recallTopK)`：保留 `rewrite` 与 `retrievalQuery`。
- `withRewrite(boolean rewrite)`：保留 `rerank`、`recallTopK` 与 `retrievalQuery`。
- `withRetrievalQuery(String retrievalQuery)`：保留其余全部字段，由改写扩展在成功时调用。

**校验规则**：

- `query`：沿用 `RetrievalConstraints.requireText`（非空、≤2000）。
- `retrievalQuery`：为 `null` 时取 `query`；显式提供时去空白、非空；逐字与 `query` 不同时长度 ≤500，否则拒绝（`VALIDATION_FAILED`）。
- `topK` / `minScore` / `recallTopK` / `granularity`：沿用既有校验。
- `rewrite` 不参与构造校验；它由服务层结合租户资源解析。

### 1.2 `RetrieveRequest`（Web 入参，扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `query` | `String` | 必填 | 用户查询 |
| `granularity` | `String` | 可空 | 粒度 |
| `documentId` | `Long` | 可空 | 单文档 |
| `topK` | `Integer` | 可空 | 默认 5 |
| `minScore` | `Double` | 可空 | 默认取 `unirag.retrieval.default-min-score` |
| `rerank` | `Boolean` | 可空 | `null`=默认开启（有资源时） |
| `rewrite` | `Boolean` | 可空 | **新增**；`null`=默认开启（有资源时），`false`=显式关闭 |

- 保留 5 参 / 6 参构造器；新增 7 参规范构造器。
- `toModel` 把 `rewrite` 解析为意图布尔值（`null` 视为 `true`），由 `RetrievalService` 再结合租户资源求实际值。

### 1.3 `AskRequest` / `QaRequest`（扩展）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `question` | `String` | 必填 | 用户原问题 |
| `granularity` | `String` | 可空 | 粒度 |
| `topK` | `Integer` / `int` | 可空 / 1..50 | 最终片段/引用条数 |
| `minScore` | `Double` / `double` | 可空 / `[0,1]` | 向量召回下限 |
| `sessionId` | `String` | 可空 | 仅透传，不用于改写 |
| `rerank` | `Boolean` | 可空 | 语义不变 |
| `rewrite` | `Boolean` | 可空 | **新增**；`null`=按租户默认，`false`=显式关闭 |

- `AskRequest` 保留 5 参 / 6 参构造器，新增 7 参规范构造器。
- `QaRequest` 保留 5 参 / 6 参构造器，新增 7 参规范构造器。
- `QaService` 用 `rewrite == null || rewrite` 求意图，再结合租户资源求实际值。

### 1.4 `QueryRewriteOutcome`（新增，内存对象）

由 `QueryRewriteEnhancement` 写入 `StageContext.attributes`，供观测扩展落行。

| 字段 | 类型 | 说明 |
|---|---|---|
| `modelKey` | `String` | 本次解析到的 `QUERY_REWRITE_MODEL` 资源 key；解析失败时 `null` |
| `modelSource` | `String` | `primary-llm`（复用租户主语言模型）或 `dedicated`（专用模型）；解析失败时 `null` |
| `originalChars` | `int` | 原始问题字符数 |
| `retrievalChars` | `int` | 检索查询字符数；回退时等于 `originalChars` |
| `durationMs` | `long` | 改写调用耗时 |
| `status` | `StageStatus` | `SUCCESS` / `DEGRADED` |
| `reason` | `String` | 拒绝/降级原因；成功时 `null` |
| `shadow` | `boolean` | 是否观察模式 |
| `detail` | `String` | `shadow=<bool>;model=<primary-llm|dedicated>;prompt=rewrite-v1;originalChars=<n>;retrievalChars=<n>;preview=<脱敏短摘要>` |

**上下文键**：`query.rewrite.outcome`（常量 `CONTEXT_KEY`）。

**状态流转**：

```text
请求未开启 / 无资源 / 原问题超长
    → 跳过（不产生 outcome、不写观测行）

执行改写
    ├─ 观察模式成功 → SUCCESS(shadow=true)，主链路仍用原问题
    ├─ 正常成功     → SUCCESS(shadow=false)，主链路用 retrievalQuery
    ├─ 输出无效     → DEGRADED(reason=EMPTY_OUTPUT|MULTIPLE_QUERIES|MULTILINE_OUTPUT|
    │                            TOO_LONG|TOO_VERBOSE|DROPPED_CONSTRAINT)，回退原问题
    └─ 调用异常     → DEGRADED(reason=已脱敏异常摘要)，回退原问题
```

### 1.5 `QueryRewriteProperties`（新增，配置对象）

`@ConfigurationProperties(prefix = "unirag.retrieval.query-rewrite")`。

| 字段 | 类型 | 默认 | 约束 | 说明 |
|---|---|---|---|---|
| `enabled` | `boolean` | `true` | — | 全局开关；无租户资源时无效果 |
| `shadow` | `boolean` | `false` | — | 观察模式：计算并记录，不改变检索结果 |
| `maxOutputChars` | `int` | `500` | `1..2000` | 检索查询字符上限 |
| `maxOutputRatio` | `double` | `2.0` | `1.0..10.0`，有限数 | 检索查询相对原问题的长度倍率上限 |
| `maxInputChars` | `int` | `2000` | `1..2000` | 原问题超过该长度时跳过改写 |
| `previewChars` | `int` | `120` | `16..500` | 观测 detail 中摘要的最大长度 |

### 1.6 `ResourceType.QUERY_REWRITE_MODEL`（新增枚举值）

| 属性 | 值 |
|---|---|
| 枚举名 | `QUERY_REWRITE_MODEL` |
| `beanPrefix()` | `queryRewriteModel` |
| Bean 类型 | `dev.langchain4j.rag.query.transformer.QueryTransformer` |
| Provider | `QueryRewriteModelResourceProvider` |
| 解析方式 | `TenantResourceRouter.getPrimary(tenantCode, QUERY_REWRITE_MODEL, QueryTransformer.class)` |
| 启用判断 | `TenantResourceRouter.hasPrimary(tenantCode, QUERY_REWRITE_MODEL)` |

**资源配置 schema**（存于 `tenant_resource_config.config` JSONB）。

默认复用租户主语言模型（推荐，配置可为空）：

```json
{}
```

需要独立模型/凭据/超时时，显式提供专用连接：

```json
{
  "base-url": "https://api.siliconflow.cn/v1",
  "api-key": "<tenant-secret>",
  "model-name": "Qwen/Qwen2.5-7B-Instruct",
  "timeout": "PT1.5S",
  "temperature": 0.0,
  "max-tokens": 200
}
```

| 字段 | 类型 | 必填 | 默认 | 约束 |
|---|---|---|---|---|
| `base-url` | string | 条件必填 | — | 与 `api-key`、`model-name` 同时出现或同时省略；出现时须为 http/https URL |
| `api-key` | string | 条件必填 | — | 与另两个连接字段同时出现或同时省略；不出现在日志、观测、错误信息、测试断言或版本控制中 |
| `model-name` | string | 条件必填 | — | 与另两个连接字段同时出现或同时省略 |
| `timeout` | duration | 否 | `PT1.5S` | 仅专用模型可配置；ISO-8601 Duration，`> 0` 且 `≤ PT1.5S`；`primary-llm` 模式下出现即配置非法（沿用主 LLM 的超时） |
| `temperature` | double | 否 | `0.0` | `[0,1]`；两种模式都作为本次 `ChatRequest` 参数 |
| `max-tokens` | int | 否 | `200` | `32..1000`；两种模式都作为本次 `ChatRequest` 参数 |

**模型来源判定**：

- 三个连接字段全省略（含空对象 `{}`）→ **`primary-llm`**：运行时通过 `TenantResourceRouter.getPrimary(tenantCode, LLM, OpenAiChatModel.class)` 解析主语言模型；每租户至多一个 primary，沿用其凭据与超时。
- 三个连接字段全提供 → **`dedicated`**：Provider 构建独立 `OpenAiChatModel`，使用资源级 `timeout`。
- 只提供部分连接字段 → 配置非法，Registrar 记 ERROR 并隔离，不影响启动与其他租户。
- 无论哪种模式，`QUERY_REWRITE_MODEL` 资源的存在都是租户级启用条件；没有该资源的租户行为不变。

**提示词版本**：固定为 `rewrite-v1`，作为 `LlmQueryTransformer` 的实现常量随代码版本化；本期不允许租户覆盖提示词，避免新增注入面与版本管理复杂度。观测 `detail` 中的原因码、长度与模型来源已足够定位，不需要单独存储提示词版本。

### 1.7 `StageName.QUERY_REWRITE`（新增枚举值）

- 仅用于观测记录的 `stage` 列，**不是**阶段链成员。
- 实际执行仍发生在 `QUERY_EMBEDDING` 的 `beforeStage` 增强中。
- 观测行的 `stages()` 过滤条件为 `StageName.QUERY_EMBEDDING`。

---

## 2. 持久化变更

**无数据库迁移。**

- `retrieval_observation.stage` 为 `VARCHAR(32)`，`QUERY_REWRITE`（13 字符）可容纳。
- `retrieval_observation.detail` 已由 V9 提供（`VARCHAR(2000)`），用于存放脱敏摘要。
- `tenant_resource_config.resource_type` 为 `VARCHAR(32)`，`QUERY_REWRITE_MODEL`（19 字符）可容纳；主资源唯一索引按 `(tenant_id, resource_type)` 生效，无需变更。
- 观测留存、租户隔离、查询入口均沿用 010/011 的既有实现。

---

## 3. 观测行映射

`QueryRewriteObservationExtension` 写入 `retrieval_observation`：

| 列 | 值 |
|---|---|
| `trace_id` | 同一次检索/问答的链路标识 |
| `tenant_id` | 发起租户 |
| `session_id` | 问答请求透传；检索为空 |
| `kind` | `RETRIEVAL` / `QA` |
| `stage` | `QUERY_REWRITE` |
| `target_key` | `QueryRewriteOutcome.modelKey`（资源 key） |
| `status` | `SUCCESS` / `DEGRADED` |
| `duration_ms` | 改写耗时 |
| `hit_count` | `originalChars` |
| `top_score` | `null` |
| `item_count` | `retrievalChars` |
| `error_message` | `QueryRewriteOutcome.reason`（脱敏、截断） |
| `detail` | `QueryRewriteOutcome.detail`（脱敏、截断） |
| `query_digest` | 原始问题（由 `ObservationRecorder` 统一写入） |
| `document_id` | 请求限定文档；检索可为空 |

**不写行的场景**：请求关闭改写、租户无改写资源、全局开关关闭、原问题超过 `max-input-chars`。

---

## 4. 指标

`ObservationMetrics` 新增：

| 指标 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `unirag.query.rewrite.requests` | Counter | `tenant`、`status`（`SUCCESS`/`DEGRADED`） | 改写调用量 |
| `unirag.query.rewrite.duration` | Timer | `tenant` | 改写耗时 |

标签不得包含问题文本、改写文本、模型名或密钥。

---

## 5. 配置

```yaml
unirag:
  retrieval:
    query-rewrite:
      enabled: ${UNIRAG_QUERY_REWRITE_ENABLED:true}   # 全局开关；无租户资源时无效果
      shadow: ${UNIRAG_QUERY_REWRITE_SHADOW:false}    # 观察模式：计算并记录，不改变返回
      max-output-chars: 500                           # 检索查询字符上限
      max-output-ratio: 2.0                           # 相对原问题的长度倍率上限
      max-input-chars: 2000                           # 原问题超过该长度时跳过改写
      preview-chars: 120                              # 观测 detail 摘要长度
```

- 全局开关关闭时，服务层把 `rewrite` 解析为 `false`，不调用模型、不写观测行。
- 观察模式只影响是否采用改写结果，不影响是否记录。
- 资源级 `temperature` / `max-tokens` 两种模型来源都生效；`timeout` 仅专用模型可配置，`primary-llm` 模式沿用主 LLM 的超时。全局配置只提供策略开关与输出约束。

---

## 6. 不变量（实现与测试必须共同保证）

1. `RetrievalRequest.query` 在整条链路中始终等于用户原始问题；改写只能改变 `retrievalQuery`。
2. `PromptAssemblyStage` 的“问题：”后必须是 `request.query()`。
3. `RerankEnhancement` 的 `scoreAll(..., query)` 必须是 `request.query()`。
4. `RetrieveResponse.query` 必须是 `request.query()`。
5. 任一改写失败/拒绝路径都必须返回原始输入，且不抛出到 Controller。
6. 无改写资源的租户不产生 `QUERY_REWRITE` 观测行，且不调用改写模型。
7. `primary-llm` 模式只解析发起租户自己的主 `LLM`，不新建、不共享凭据；`dedicated` 模式只使用该资源自己的连接配置。
