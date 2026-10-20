# Contract: 评测报告与比较

**Feature**: `013-rag-evaluation` | **Version**: 1

本契约定义报告 JSON 结构、指标键、失败归因、可比性规则与比较输出。报告是评测的唯一结论载体；未写入报告的指标视为未评测。

## 1. 报告 JSON

```json
{
  "reportVersion": 1,
  "run": {
    "id": "20261019T120000Z-abc1234",
    "mode": "replay",
    "status": "SUCCESS",
    "startedAt": "2026-10-19T12:00:00Z",
    "finishedAt": "2026-10-19T12:02:10Z",
    "failureReason": null
  },
  "dataset": {
    "name": "baseline",
    "version": "sha256:...",
    "corpusHash": "sha256:...",
    "queriesHash": "sha256:...",
    "documentCount": 10,
    "queryCount": 30,
    "answerableCount": 25,
    "unanswerableCount": 5
  },
  "config": {
    "codeVersion": "git:abc1234",
    "embedding": { "model": "bge-m3", "dimension": 1024 },
    "pipelines": [
      { "key": "fine", "maxSegmentTokens": 300, "overlapTokens": 50,
        "modelKey": "eval-embedding", "storeKey": "eval-store" }
    ],
    "retrieval": { "topK": 5, "recallTopK": 20, "minScore": 0.2,
                   "rerank": false, "rewrite": false },
    "metrics": { "k": [1, 3, 5, 10], "diagnosticTopK": 50 },
    "thresholds": { "chunkRecall@5": { "min": 0.7 } }
  },
  "overall": {
    "documentRecall@5": 0.80,
    "chunkRecall@5": 0.72,
    "hitRate@5": 0.83,
    "mrr@10": 0.61,
    "ndcg@10": 0.66,
    "unanswerableFalsePositiveRate": 0.20,
    "latencyP50Ms": 80,
    "latencyP95Ms": 180
  },
  "byTag": {
    "fact": { "chunkRecall@5": 0.90 },
    "exact-term": { "chunkRecall@5": 0.55 },
    "unanswerable": { "unanswerableFalsePositiveRate": 0.20 }
  },
  "queries": [
    {
      "id": "q-001",
      "question": "向量表是按租户创建的吗？",
      "tags": ["fact"],
      "answerable": true,
      "retrieved": [
        { "rank": 1, "doc": "tenant-config.md", "chunkIndex": 3,
          "pipeline": "fine", "score": 0.81, "rerankScore": null,
          "documentRelevant": true, "chunkRelevant": true }
      ],
      "diagnostic": {
        "searchedTables": 1,
        "failedTables": [],
        "goldCandidates": [
          { "doc": "tenant-config.md", "pipeline": "fine", "chunkIndex": 3,
            "score": 0.81, "mergedRank": 1, "belowMinScore": false }
        ]
      },
      "metrics": { "documentRecall": 1.0, "chunkRecall": 1.0,
                   "firstRelevantRank": 1, "ndcg": 1.0 },
      "failure": null,
      "evidence": []
    }
  ],
  "failures": {
    "CHUNKING_GAP": 0,
    "NOT_RECALLED": 2,
    "MERGE_OR_TOPK_DROPPED": 1,
    "RERANK_DROPPED": 0,
    "THRESHOLD_FILTERED": 1,
    "BELOW_TOPK": 1,
    "LABEL_INVALID": 0
  }
}
```

## 2. 指标键

| 键 | 方向 | 定义 |
|---|---|---|
| `documentRecall@k` | 越大越好 | top-k 命中的唯一 gold 文档 / gold 文档总数 |
| `chunkRecall@k` | 越大越好 | top-k 覆盖的 gold 标注数 / gold 标注总数 |
| `hitRate@k` | 越大越好 | 是否至少命中一个 gold 标注 |
| `mrr@k` | 越大越好 | 首个相关片段排名的倒数均值 |
| `ndcg@k` | 越大越好 | 二元增益 nDCG |
| `unanswerableFalsePositiveRate` | 越小越好 | 不可回答查询返回片段的比例 |
| `latencyP50Ms` / `latencyP95Ms` | 越小越好 | 生产遍检索耗时 |

`k` 来自数据集配置；报告中所有 `@k` 指标均按配置的每个 k 输出。

## 3. 失败归因

| 值 | 含义 | 证据字段要求 |
|---|---|---|
| `CHUNKING_GAP` | 答案片段未被任何切片覆盖 | 文档名、缺失 span 的哈希/预览 |
| `NOT_RECALLED` | 片段存在但逐表均未召回 | 逐表命中数、目标表列表 |
| `MERGE_OR_TOPK_DROPPED` | 逐表召回但归并/截断后丢失 | 逐表排名、归并排名 |
| `RERANK_DROPPED` | 重排后丢失 | 重排前排名、重排分数 |
| `THRESHOLD_FILTERED` | 分数低于生产阈值 | 片段分数、阈值 |
| `BELOW_TOPK` | 分数达标但排名低于最终 topK | 合并排名、topK |
| `LABEL_INVALID` | 标注无法匹配 | 查询 id、文档、span |

`UNANSWERABLE_FALSE_POSITIVE` 单独计入 `overall` 与 `byTag`，不出现在 `failures` 中。

## 4. 可比性规则

比较两份报告前，以下字段 MUST 全部一致：

- `dataset.version`（或 `corpusHash` + `queriesHash`）
- `run.mode`（`live` 与 `replay` 不可混比；`record` 与 `live` 可视为同模式）
- `config.embedding.model`
- `config.embedding.dimension`
- `config.pipelines`（key、切片参数、模型/存储 key）
- `config.retrieval`（topK、recallTopK、minScore、rerank、rewrite）
- `config.metrics.k` 与 `diagnosticTopK`

不一致时：
- 输出 `comparable=false`；
- 列出每个差异字段的基线与候选值；
- **不输出**效果差值与通过/失败结论；
- 允许展示“配置差异”供人工判断。

## 5. 比较输出 JSON

```json
{
  "comparisonVersion": 1,
  "comparable": true,
  "baseline": { "runId": "...", "datasetVersion": "sha256:..." },
  "candidate": { "runId": "...", "datasetVersion": "sha256:..." },
  "differences": [],
  "metrics": [
    {
      "name": "chunkRecall@5",
      "baseline": 0.72,
      "candidate": 0.75,
      "absoluteDelta": 0.03,
      "relativeDelta": 0.0417,
      "threshold": { "min": 0.70 },
      "passed": true,
      "regressed": false
    }
  ],
  "byTag": [
    {
      "tag": "exact-term",
      "metrics": [
        { "name": "chunkRecall@5", "baseline": 0.55, "candidate": 0.50,
          "absoluteDelta": -0.05, "relativeDelta": -0.0909,
          "threshold": null, "passed": null, "regressed": true }
      ]
    }
  ],
  "summary": {
    "regressed": 1,
    "thresholdFailed": 0,
    "incomparableFields": []
  }
}
```

## 6. Markdown 报告

Markdown 报告至少包含：

1. 运行信息：模式、状态、时间、数据集版本、配置快照；
2. 总体指标表；
3. 分标签指标表；
4. 失败归因分布；
5. 失败查询明细：问题、gold 标注、top-k 结果、主归因与证据；
6. 若执行比较：指标差值表、退化项、阈值未通过项、不可比字段。

## 7. 脱敏

报告与 Markdown MUST NOT 包含：

- 模型 API key、密码、令牌；
- 租户资源配置原文；
- 环境变量值。

允许包含：模型标识、维度、资源 key、租户码、文档标识、片段序号、分数、问题文本、片段原文（评测语料为测试数据）。

错误原因必须经脱敏；若异常信息包含疑似凭据，只保留异常类型与字段名。
