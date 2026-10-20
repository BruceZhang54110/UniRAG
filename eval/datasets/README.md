# 评测数据集维护指南

本目录存放 RAG 检索评测数据集。新增或修改数据只需编辑文件，不需要改代码。

## 目录结构

```text
eval/datasets/<name>/
├── corpus/            # UTF-8 文本，.md 或 .txt；允许子目录
├── queries.jsonl      # 每行一条查询
├── config.json        # 运行配置
└── thresholds.json    # 可选阈值
```

`<name>` 必须与 `config.json` 的 `name` 一致，并只能包含小写字母、数字、连字符、下划线。

## queries.jsonl

```json
{"id":"q-001","question":"向量表是按租户创建的吗？","tags":["fact"],"split":"dev","answerable":true,"relevant":[{"doc":"tenant-config.md","span":"为每个租户创建独立的向量表"}]}
{"id":"q-002","question":"支持 LDAP 吗？","tags":["unanswerable"],"split":"test","answerable":false,"relevant":[]}
```

规则：

- `id` 在数据集内唯一；
- `question` 去空白后非空，长度不超过 2000；
- `tags` 至少一个，用于分维度报告；
- `split` 取 `dev` 或 `test`，缺省 `dev`；
- `answerable=true` 时 `relevant` 至少一条；`false` 时必须为空；
- 每条形如 `{"doc":"<docName>","span":"<原文片段>"}` 的标注，`span` 必须能在对应文档中找到。

## 标注原则

- 标注“能回答问题的原文片段”，不是关键词；
- 不要写片段序号，切片参数变化后序号会失效；
- 一个问题可有多条标注，覆盖多个文档或多个片段；
- 不可回答查询也要保留，用于衡量误答率。

## config.json

```json
{
  "name": "baseline",
  "tenant": "eval_baseline",
  "store": {"table": "embeddings_eval_baseline", "dimension": 1024},
  "pipelines": [
    {"key": "fine", "maxSegmentTokens": 300, "overlapTokens": 50,
     "modelKey": "eval-embedding", "storeKey": "eval-store"}
  ],
  "retrieval": {"topK": 5, "recallTopK": 20, "minScore": 0.2,
                "rerank": false, "rewrite": false},
  "metrics": {"k": [1, 3, 5, 10], "diagnosticTopK": 50}
}
```

约束：

- `tenant` 必须以 `eval_` 开头；
- `store.table` 必须以 `embeddings_eval_` 开头；
- `overlapTokens < maxSegmentTokens`；
- `topK` 必须在 `metrics.k` 中；
- `recallTopK` 在 `topK` 到 50 之间；
- `rerank=true` 时需要配合 `-Deval.embeddingTenant` 复用源租户的 primary `RERANK_MODEL`；
  `rewrite=true` 暂不支持，评测会显式拒绝；
- 多管线使用不同向量表时，增加 `stores` 映射（key 为 pipeline.storeKey），所有表维度必须一致；
  省略 `stores` 时所有管线共用 `store`。

## thresholds.json（可选）

```json
{
  "chunkRecall@5": {"min": 0.70},
  "unanswerableFalsePositiveRate": {"max": 0.10}
}
```

未配置阈值时，报告只记录数值，不判定通过/失败。

## 版本与可比性

- 数据集版本由语料、查询集与配置的哈希组成；
- 修改任一内容都会产生新版本；
- 不同版本、不同模型、不同切片或检索配置的报告不可直接比较；
- `thresholds.json` 不改变数据集版本，但会改变判定结果。

## 常见校验错误

| 规则 | 含义 |
|---|---|
| V-001 | 数据集名称与目录名不一致 |
| V-002 | 语料为空、文档标识重复或正文为空 |
| V-003 | 查询为空、标识重复、标签缺失或 split 非法 |
| V-004 | 可回答/不可回答与标注不一致 |
| V-005 | 标注文档不存在或片段在文档中找不到 |
| V-006 | topK/recallTopK/minScore/k/diagnosticTopK 非法 |
| V-007 | 评测租户或向量表命名不符合约定 |
| V-008 | 管线为空、key 重复或切片参数非法 |
| V-009 | 模式所需的环境变量或夹具缺失 |
| V-010 | 启用了重排/改写但缺少对应资源 |
| V-011 | 阈值指标不支持或 min/max 配置非法 |

## 运行

```bash
# 首次基线：复用数据库中已有租户的 EMBEDDING_MODEL
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline -Deval.mode=record \
  -Deval.embeddingTenant=demo

# 日常回归（无外部凭据）
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline -Deval.mode=replay

# 报告比较
./mvnw test -Peval -Dtest=ReportCompareIT \
  -Deval.baseline=eval/baselines/baseline.json \
  -Deval.candidate=eval/reports/<runId>.json
```
