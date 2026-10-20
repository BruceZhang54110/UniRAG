# Quickstart: RAG 检索评测基线

**Feature**: `013-rag-evaluation` | **Date**: 2026-10-19

本指南用于验证评测能力端到端可用：从数据集准备到基线报告、replay 回归与报告比较。

## 1. 前置条件

| 条件 | 说明 |
|---|---|
| JDK 21 | `java -version` |
| Maven Wrapper | 仓库根 `./mvnw`，无需安装 Maven |
| PostgreSQL + pgvector | 与现有 `*IT` 相同的本地开发库；`application.yml` 指向的库可连 |
| 评测数据集 | `eval/datasets/baseline/`，至少 10 篇文档、30 条查询 |
| live/record 额外条件 | OpenAI 兼容嵌入端点；`EVAL_EMBEDDING_*` 环境变量 |
| Git 跟踪 | 仓库 `.gitignore` 需包含 `!specs/`，否则新增 spec 工件不会被跟踪（research R13） |

**验证数据库可用**：

```bash
./mvnw test -Dtest=HealthEndpointTest
```

**验证默认质量门不执行评测**：

```bash
./mvnw test
```

预期：既有 `*Test` / `*IT` 正常执行；任何 `@Tag("eval")` 类不执行；不需要 `EVAL_EMBEDDING_*`。

## 2. 准备数据集

目录结构：

```text
eval/datasets/baseline/
├── corpus/
│   ├── deploy.md
│   ├── tenant-config.md
│   └── ...
├── queries.jsonl
├── config.json
└── thresholds.json        # 可选
```

最小可用内容：

1. **10 篇语料**：覆盖真实业务主题，包含精确标识符、表格、代码片段、常见同义表述；
2. **30 条查询**：
   - 20 条可回答，覆盖普通、口语化、精确标识符、跨文档；
   - 5–10 条不可回答；
   - 每条标注 `doc` + `span`，不要写片段序号；
3. **1 条管线**（首版）：`maxSegmentTokens=300`、`overlapTokens=50`；
4. **检索配置**：`topK=5`、`recallTopK=20`、`minScore` 先用生产默认值；
5. **不配置阈值**：首次基线只记录数值。

格式细节见 [contracts/dataset.md](./contracts/dataset.md)。

**校验数据集**：运行评测时会在摄取前校验；也可以先执行数据集校验单元测试（实现后提供）。任何非法条目都会在摄取前失败，不会产生部分基线。

## 3. 建立首次权威基线（record 模式）

```bash
# 方式 A：复用数据库中已有租户的 EMBEDDING_MODEL（推荐）
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline \
  -Deval.mode=record \
  -Deval.embeddingTenant=demo

# 方式 B：通过环境变量提供模型
EVAL_EMBEDDING_BASE_URL=http://localhost:11434/v1 \
EVAL_EMBEDDING_API_KEY=local \
EVAL_EMBEDDING_MODEL=bge-m3 \
EVAL_EMBEDDING_DIMENSION=1024 \
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline \
  -Deval.mode=record
```

预期结果：

| 产物 | 路径 |
|---|---|
| 运行报告 JSON | `eval/reports/<runId>.json` |
| 运行报告 Markdown | `eval/reports/<runId>.md` |
| 冻结向量夹具 | `eval/fixtures/baseline/embeddings.json` |
| 冻结重排分数（rerank=true 时） | `eval/fixtures/baseline/rerank.json` |
| 控制台 | 运行成功，无凭据输出 |

确认报告：

- `run.status = SUCCESS`；
- `dataset.documentCount`、`queryCount` 与数据集一致；
- `overall` 包含 `documentRecall@5`、`chunkRecall@5`、`hitRate@5`、`mrr@10`、`ndcg@10`、`unanswerableFalsePositiveRate`、`latencyP50Ms`、`latencyP95Ms`；
- `queries` 数量等于查询总数，每个查询都有 `retrieved` 与 `metrics`；
- `failures` 覆盖全部失败查询；
- 报告与 Markdown 中搜索不到 `EVAL_EMBEDDING_API_KEY` 的值。

确认后，将本次报告复制为基线：

```bash
cp eval/reports/<runId>.json eval/baselines/baseline.json
```

基线进入版本控制；运行报告留在本地/CI。

## 4. 日常回归（replay 模式）

```bash
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline \
  -Deval.mode=replay
```

预期：

- 不读取 `EVAL_EMBEDDING_*`，不访问外部模型；
- 启用重排时同时读取 `eval/fixtures/<dataset>/rerank.json`，不访问外部重排服务；
- 同一数据集与配置连续运行 3 次，`overall` 指标完全一致；
- 报告 `run.mode = replay`；
- 与 live/record 报告不可直接比较（模式不同）。

## 5. 与基线比较

```bash
./mvnw test -Peval -Dtest=ReportCompareIT \
  -Deval.baseline=eval/baselines/baseline.json \
  -Deval.candidate=eval/reports/<runId>.json
```

预期：

- 生成 `<runId>-comparison.json`；
- `comparable=true` 时输出每个指标与分标签维度的差值、退化标记、阈值结果；
- 数据集/模型/切片/检索配置不一致时 `comparable=false`，列出差异字段，不输出效果结论；
- 未配置阈值时 `passed=null`，只记录数值。

## 6. 验证失败归因

构造或挑选以下查询，确认报告归因符合预期：

| 场景 | 预期归因 | 验证点 |
|---|---|---|
| gold span 被切片截断 | `CHUNKING_GAP` | `document_chunk` 中无包含 span 的片段 |
| 片段存在但向量检索未召回 | `NOT_RECALLED` | 逐表诊断命中为空 |
| 逐表召回但未进 top-k | `MERGE_OR_TOPK_DROPPED` 或 `BELOW_TOPK` | 诊断遍有排名，生产遍缺失 |
| 分数低于 `minScore` | `THRESHOLD_FILTERED` | 分数 < 阈值 |
| 启用重排后被丢弃 | `RERANK_DROPPED` | 诊断遍存在，重排后缺失 |
| 不可回答查询返回片段 | `UNANSWERABLE_FALSE_POSITIVE` | 计入误答率，不计入可回答归因 |

每条失败查询在报告 `evidence` 中给出可复核依据，不得只给结论。

## 7. 阈值与门禁

首次基线不配置阈值。观察 2–3 轮后，在 `thresholds.json` 中设置保守阈值：

```json
{
  "chunkRecall@5": { "min": 0.70 },
  "unanswerableFalsePositiveRate": { "max": 0.10 }
}
```

运行比较时：

- 默认 `failOnThreshold=false`：报告标记未通过，但 JUnit 成功；
- `-Deval.failOnThreshold=true`：未通过时 JUnit 失败，用于 CI 门禁。

## 8. 排障

| 现象 | 原因 | 处理 |
|---|---|---|
| 报告缺失向量键 | 语料/切片/查询变化后未重新录制 | 用 `record` 模式重新生成夹具 |
| 维度不一致 | 夹具或环境变量维度与 `store.dimension` 不同 | 对齐配置；不要手工改夹具 |
| 租户/表名被拒绝 | `tenant` 不以 `eval_` 开头，或表不以 `embeddings_eval_` 开头 | 修改 `config.json`，防止误用业务数据 |
| 摄取 NOOP、索引未更新 | `IngestionService` 指纹只看内容；切片参数变化不会触发重建 | 运行前清理评测租户；实现中由 `EvaluationIndexPreparer` 统一处理 |
| 资源未注册 | 插入配置后未调用 Registrar | 确认实现调用了 `TenantResourceRegistrar.loadAndRegister()` |
| replay 读取了环境变量 | 模式判断错误 | `replay` 模式禁止读取 `EVAL_EMBEDDING_*` |
| 默认测试要求凭据 | 评测类未正确打 tag 或被 profile 误选 | 检查 `@Tag("eval")` 与 surefire `excludedGroups` |
| 报告不可比 | 数据集/模型/切片/检索配置变化 | 视为新基线；不要强行比较 |
| 非评测租户数据变化 | 隔离失败 | 运行标记 `FAILED`，检查租户码与清理范围 |

## 9. 清理

评测运行会自动清理评测租户的片段、文档、摄取记录与向量表行。手工清理时只允许操作：

- `tenant_code` 以 `eval_` 开头的租户；
- `embeddings_eval_` 开头的向量表。

**不得**清理业务租户或非评测向量表。

## 10. 完成标准

- [ ] 默认 `./mvnw test` 通过，未执行任何评测类，未要求模型凭据；
- [ ] `record` 模式生成报告与冻结夹具；
- [ ] `replay` 模式连续 3 次指标完全一致；
- [ ] 报告包含总体、分标签、逐查询详情与失败归因；
- [ ] 比较功能正确拒绝不可比报告，正确标记退化与阈值；
- [ ] 非评测租户数据指纹前后一致；
- [ ] 报告与日志中无凭据；
- [ ] 新增 10 篇文档、30 条查询无需修改代码。
