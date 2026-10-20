# Phase 1 Data Model: RAG 检索评测基线

**Feature**: `013-rag-evaluation` | **Date**: 2026-10-19

本 feature **不新增数据库表**。领域对象分为两类：

1. **文件工件**：数据集、冻结向量、报告、基线比较结果；
2. **既有持久化对象**：评测租户、租户资源、摄取管线、文档、片段、租户向量表。

---

## 1. 文件工件模型

### 1.1 EvaluationDataset（评测数据集）

一轮评测的完整输入。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 数据集名称，与目录名一致；用于评测租户与报告 |
| `version` | string | 是 | 数据集版本指纹（语料 + 查询 + 配置的哈希） |
| `corpus` | list<CorpusDocument> | 是 | 至少 1 篇文档 |
| `queries` | list<QueryCase> | 是 | 至少 1 条查询 |
| `config` | DatasetConfig | 是 | 运行配置 |
| `thresholds` | ThresholdConfig | 否 | 未配置时只记录数值，不判定通过/失败 |

**关系**：`QueryCase.relevant` 引用 `CorpusDocument.docName`；一个数据集对应一个评测租户。

**校验**：
- `name` 与目录名一致，且只含小写字母、数字、连字符、下划线；
- 语料文档标识唯一；
- 查询标识唯一；
- 至少一条可回答查询与至少一条（推荐）不可回答查询。

---

### 1.2 CorpusDocument（语料文档）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `docName` | string | 是 | 稳定业务标识，取语料目录下的相对路径（如 `deploy.md`），作为摄取 `docName` 与标注引用键 |
| `title` | string | 否 | 展示名，缺省等于 `docName` |
| `content` | string | 是 | UTF-8 正文；非空白 |
| `sourcePath` | string | 是 | 相对于数据集语料目录的路径，用于错误定位 |

**校验**：
- 后缀仅允许 `.md`、`.txt`；
- 同一数据集内 `docName` 唯一；
- 正文非空白。

---

### 1.3 QueryCase（查询用例）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 查询唯一标识，建议 `q-001` 形式 |
| `question` | string | 是 | 用户原始问题；长度遵守 `RetrievalConstraints.MAX_TEXT_LENGTH` |
| `tags` | list<string> | 是 | 维度标签，如 `fact`、`exact-term`、`colloquial`、`cross-doc`、`unanswerable` |
| `split` | enum(`dev`,`test`) | 否 | 数据划分，缺省 `dev`；比较与调参时用于隔离 |
| `answerable` | boolean | 是 | 是否可由语料回答 |
| `relevant` | list<RelevanceLabel> | 条件必填 | `answerable=true` 时至少 1 条；`answerable=false` 时必须为空 |

**校验**：
- 同一数据集内 `id` 唯一；
- `answerable=false` 时 `relevant` 必须为空；
- `answerable=true` 时 `relevant` 不得为空；
- 问题去空白后非空且不超长。

---

### 1.4 RelevanceLabel（相关性标注）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `doc` | string | 是 | 目标文档的 `docName` |
| `span` | string | 是 | 目标文档中的原文片段；必须能在归一化后的正文中找到 |

**校验**：
- `doc` 必须存在于语料；
- `span` 去空白后非空；
- `span` 在对应文档中可找到（按 `NormalizedText` 规则）。

**说明**：标注不包含片段序号。切片参数变化后，只要原文片段仍完整出现在某个切片中，标注依然有效。

---

### 1.5 DatasetConfig（运行配置）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tenant` | string | 是 | 评测租户码，必须等于 `eval_<name>` 或以其为前缀，避免误用业务租户 |
| `store.table` | string | 是 | 评测向量表名，必须以 `embeddings_eval_` 开头 |
| `store.dimension` | int | 是 | 向量维度；live 模式必须与环境变量维度一致，replay 模式必须与夹具维度一致 |
| `stores` | map<storeKey, StoreConfig> | 否 | 多管线多表时按 storeKey 指定各自表与维度；省略时所有管线共用 `store`；所有维度必须一致 |
| `pipelines` | list<PipelineConfig> | 是 | 至少 1 条；允许 2 条模拟粗/细粒度 |
| `retrieval` | RetrievalConfig | 是 | 生产遍参数 |
| `metrics.k` | list<int> | 是 | 如 `[1,3,5,10]`，必须包含生产 `topK` |
| `metrics.diagnosticTopK` | int | 是 | 诊断遍条数，默认 50，不得超过 `RetrievalConstraints.MAX_TOP_K` |

**PipelineConfig**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `key` | string | 是 | `ingestion_pipeline.pipeline_key` |
| `maxSegmentTokens` | int | 是 | 切片上限 |
| `overlapTokens` | int | 是 | 重叠 token 数，必须小于上限 |
| `modelKey` | string | 是 | 嵌入模型资源 key |
| `storeKey` | string | 是 | 向量存储资源 key |

**RetrievalConfig**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `topK` | int | 是 | 生产最终条数，1–50 |
| `recallTopK` | int | 是 | 生产召回条数，≥ `topK` 且 ≤ 50 |
| `minScore` | double | 是 | 生产相似度阈值，0–1 |
| `rerank` | boolean | 是 | 是否在评测中启用重排 |
| `rewrite` | boolean | 是 | 是否在评测中启用查询改写 |

**校验**：
- `topK`、`recallTopK`、`minScore` 遵守既有 `RetrievalConstraints`；
- `rerank=true` 时租户必须有可用的 `RERANK_MODEL` 资源；`rewrite=true` 时必须有可用的 `QUERY_REWRITE_MODEL` 资源；
- live/record 模式必须提供全部 `EVAL_EMBEDDING_*` 环境变量；replay 模式必须提供与被测 `modelKey` 对应的夹具。

---

### 1.6 ThresholdConfig（阈值配置）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `metric` | string | 是 | 指标键，如 `chunkRecall@5`、`unanswerableFalsePositiveRate` |
| `min` | double | 条件必填 | 越大越好的指标使用；与 `max` 二选一 |
| `max` | double | 条件必填 | 越小越好的指标使用；与 `min` 二选一 |

**校验**：指标键必须来自报告支持的指标集合；`min`/`max` 只能出现一个；取值范围 `[0,1]`（耗时类指标除外，本期不设耗时阈值）。

---

### 1.7 ReplayFixture（冻结向量夹具）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fixtureVersion` | int | 是 | 当前为 1 |
| `model` | string | 是 | 录制时使用的模型标识 |
| `dimension` | int | 是 | 向量维度 |
| `vectors` | map<string, list<double>> | 是 | 键为 `SHA-256(UTF-8 精确文本)` 的十六进制，值为定长向量 |

**校验**：
- 维度与 `DatasetConfig.store.dimension` 一致；
- 所有向量长度等于维度；
- 所有值为有限浮点数；
- 摄取或查询时若文本缺少对应键，立即失败并列出缺失文本的哈希与截断预览。

---

### 1.8 EvaluationReport（评测报告）

| 字段 | 类型 | 说明 |
|---|---|---|
| `reportVersion` | int | 当前为 1 |
| `run` | RunInfo | 运行标识、模式、状态、开始/结束时间、失败原因 |
| `dataset` | DatasetSnapshot | 名称、版本、语料哈希、查询集哈希、文档/查询/可回答/不可回答数量 |
| `config` | ConfigSnapshot | 代码版本、模型标识与维度、管线切片参数、检索参数、指标参数 |
| `overall` | map<string, double> | 总体指标 |
| `byTag` | map<string, map<string, double>> | 分标签维度指标 |
| `queries` | list<QueryResult> | 逐查询详情 |
| `failures` | map<FailureCategory, int> | 失败归因计数 |

**QueryResult**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 查询标识 |
| `question` | string | 原始问题 |
| `tags` | list<string> | 标签 |
| `answerable` | boolean | 是否可回答 |
| `retrieved` | list<RetrievedItem> | 生产遍 top-k 结果，按最终顺序 |
| `diagnostic` | DiagnosticInfo | 诊断遍摘要：逐表命中数、gold 候选位置、阈值判定 |
| `metrics` | map<string, double> | 该查询的指标 |
| `failure` | FailureCategory? | 未命中时的主归因；命中为 `null` |
| `evidence` | list<string> | 归因证据，如“片段存在于管线 fine，但未进入逐表候选” |

**RetrievedItem**

| 字段 | 类型 | 说明 |
|---|---|---|
| `rank` | int | 最终排名，1 起 |
| `doc` | string | 文档标识 |
| `chunkIndex` | int | 片段序号（仅展示，不参与标注匹配） |
| `pipeline` | string | 管线 key |
| `score` | double | 向量分数 |
| `rerankScore` | double? | 重排分数，未启用时为 `null` |
| `documentRelevant` | boolean | 是否命中任一 gold 文档 |
| `chunkRelevant` | boolean | 是否包含任一 gold span |

**RunInfo.status**：`SUCCESS` / `FAILED`。`FAILED` 时报告仍写出，但不得包含不完整指标作为基线。

---

## 2. 枚举

### 2.1 RunMode

| 值 | 含义 | 外部模型 | 可复现 |
|---|---|---|---|
| `live` | 真实模型权威基线 | 是 | 模型版本不变时可复现 |
| `record` | 真实模型 + 录制夹具 | 是 | 是（生成夹具） |
| `replay` | 只读冻结夹具 | 否 | 完全可复现 |

### 2.2 RunState

```text
VALIDATED → INDEXING → EVALUATING → REPORTED
     │          │           │
     └──────────┴───────────┴──→ FAILED
```

- `VALIDATED`：数据集与配置校验通过；
- `INDEXING`：清理并摄取了评测语料；
- `EVALUATING`：完成全部查询；
- `REPORTED`：报告已写出；
- `FAILED`：任一阶段失败，写出失败报告（含原因），不产生可用基线。

### 2.3 FailureCategory

| 值 | 含义 |
|---|---|
| `CHUNKING_GAP` | 答案片段没有被任何切片覆盖 |
| `NOT_RECALLED` | 片段存在，但未被任何目标表召回 |
| `MERGE_OR_TOPK_DROPPED` | 逐表召回了，但归并/截断后丢失 |
| `RERANK_DROPPED` | 重排后丢失 |
| `THRESHOLD_FILTERED` | 分数低于生产阈值被过滤 |
| `BELOW_TOPK` | 分数达标但排名低于最终 `topK` |
| `LABEL_INVALID` | 标注在运行期无法匹配（正常应在校验阶段拦截） |
| `UNANSWERABLE_FALSE_POSITIVE` | 不可回答查询返回了片段（单独计数，不计入可回答失败归因） |

---

## 3. 指标定义

设某查询的 gold 标注集合为 `G`，gold 文档集合为 `D`，生产遍 top-k 结果为 `R_k`。

| 指标 | 公式 | 说明 |
|---|---|---|
| `documentRecall@k` | `|{d ∈ D : ∃r ∈ R_k, r.doc = d}| / |D|` | 文档级召回 |
| `chunkRecall@k` | `|{g ∈ G : ∃r ∈ R_k, match(r,g)}| / |G|` | 片段级召回 |
| `hitRate@k` | `1` 若 `chunkRecall@k > 0`，否则 `0` | 是否有任一相关片段 |
| `mrr@k` | `1 / rank(first relevant r ∈ R_k)`，无则 `0` | 首个相关片段的排名 |
| `ndcg@k` | `DCG@k / IDCG@k`，二元增益 | `IDCG = Σ_{i=1..min(|G|,k)} 1/log2(i+1)` |
| `unanswerableFalsePositiveRate` | 返回至少 1 个片段的不可回答查询数 / 不可回答查询总数 | 误答率 |
| `latencyP50Ms` / `latencyP95Ms` | 生产遍耗时分布 | 不含诊断遍 |

`match(r,g)`：`r.doc = g.doc` 且 `NormalizedText.contains(r.text, g.span)`。

**汇总口径**：
- 总体指标 = 对应查询指标的算术平均（不可回答误答率按不可回答查询子集计算）；
- 分标签指标 = 至少包含该标签的查询子集上的算术平均；
- 文档级指标按文档去重；片段级指标按 gold 标注去重；多管线重复片段不跨管线去重，但在 `chunkRelevant` 上分别标记。

---

## 4. 既有持久化映射

评测运行复用以下既有表，不新增表：

| 表 | 用途 | 评测侧读写 |
|---|---|---|
| `tenant` | 评测租户 `eval_<dataset>` | 插入/启用；运行前按租户码清理 |
| `tenant_resource_config` | `EMBEDDING_STORE`、live/record 的 `EMBEDDING_MODEL`、可选 `RERANK_MODEL`/`QUERY_REWRITE_MODEL` | 插入/更新，调用 Registrar 注册 |
| `ingestion_pipeline` | 一条或两条评测管线 | 插入/更新 |
| `document` | 评测语料文档 | 由 `IngestionService` 写入 |
| `document_chunk` | 切片与回链 | 由 `IngestionService` 写入；失败归因读取 |
| `<store.table>` | 评测向量表 `embeddings_eval_<dataset>` | 由摄取写入；运行前清空 |
| `ingestion_run` | 摄取执行记录 | 由摄取写入；运行前清理 |

**清理顺序**（避免外键/残留）：`document_chunk` → `ingestion_run` → `document` → 向量表行 → 评测租户资源/管线（可选保留）。文档与向量清理必须覆盖所有评测管线。

---

## 5. 校验规则汇总

| 编号 | 规则 | 失败语义 |
|---|---|---|
| V-001 | 数据集目录与 `config.name` 一致 | 拒绝运行，指出目录与配置名 |
| V-002 | 语料至少 1 篇，`docName` 唯一，正文非空白 | 拒绝运行，指出冲突/空文档 |
| V-003 | 查询至少 1 条，`id` 唯一 | 拒绝运行，指出重复标识 |
| V-004 | 可回答查询至少 1 条标注；不可回答查询不得有标注 | 拒绝运行，指出查询标识 |
| V-005 | 标注 `doc` 存在，`span` 非空且在文档中可找到 | 拒绝运行，指出查询、文档、片段 |
| V-006 | `topK`/`recallTopK`/`minScore` 合法；`k` 包含 `topK` | 拒绝运行，指出字段与允许范围 |
| V-007 | 评测租户以 `eval_` 开头，表名以 `embeddings_eval_` 开头 | 拒绝运行，防止误用业务租户/表 |
| V-008 | 管线至少 1 条；`overlapTokens < maxSegmentTokens` | 拒绝运行，指出管线 key |
| V-009 | live/record 模式环境变量齐全；replay 模式夹具存在且维度一致 | 拒绝运行，列出缺失项（不含凭据值） |
| V-010 | `rerank=true`/`rewrite=true` 时对应资源存在 | 拒绝运行，指出资源类型与 key |
| V-011 | 阈值指标键受支持，`min`/`max` 二选一 | 拒绝运行，指出指标键与冲突 |
| V-012 | 报告比较的语料/查询/模型/切片/检索配置一致 | 拒绝比较，列出差异字段 |

---

## 6. 状态与并发

- 同一数据集同一时刻只允许一个评测运行；运行开始时在 `eval/reports/.lock` 或租户级标记上获取排他锁，结束后释放。
- 运行 ID 采用 `yyyyMMdd'T'HHmmss'Z'-<shortCommit>`；同名冲突时追加序号，不覆盖既有报告。
- 报告写入采用“临时文件 + 原子移动”，避免中断留下半份报告。
- 评测运行前清理评测租户；运行失败时保留现场数据供排障，但报告标记 `FAILED`，不得作为基线。
