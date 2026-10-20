# Contract: 评测数据集

**Feature**: `013-rag-evaluation` | **Version**: 1

本契约定义 `eval/datasets/<name>/` 的目录结构、文件格式与校验规则。数据集是评测的唯一输入来源，不得依赖代码中的隐式常量。

## 1. 目录结构

```text
eval/datasets/<name>/
├── corpus/
│   ├── deploy.md
│   ├── tenant-config.md
│   └── ...
├── queries.jsonl
├── config.json
└── thresholds.json        # 可选
```

- `<name>`：小写字母、数字、连字符、下划线；必须与 `config.json` 的 `name` 一致。
- `corpus/`：UTF-8 文本，后缀仅 `.md` 或 `.txt`；允许子目录，`docName` 取相对路径（如 `guides/deploy.md`）。
- 数据集内容变化即产生新版本；版本指纹用于报告比较。

## 2. `queries.jsonl`

每行一个 JSON 对象：

```json
{"id":"q-001","question":"向量表是按租户创建的吗？","tags":["fact","chinese"],"split":"dev","answerable":true,"relevant":[{"doc":"tenant-config.md","span":"为每个租户创建独立的向量表"}]}
{"id":"q-002","question":"如何配置 S3 存储？","tags":["unanswerable"],"split":"test","answerable":false,"relevant":[]}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `id` | string | 是 | 数据集内唯一；建议 `q-NNN` |
| `question` | string | 是 | 去空白后非空；长度 ≤ 2000 |
| `tags` | string[] | 是 | 非空；用于分维度报告 |
| `split` | `dev` \| `test` | 否 | 缺省 `dev` |
| `answerable` | boolean | 是 | 是否可由语料回答 |
| `relevant` | object[] | 条件 | `answerable=true` 时至少 1 条；`false` 时必须为空 |
| `relevant[].doc` | string | 是 | 必须存在于语料 |
| `relevant[].span` | string | 是 | 去空白后非空；归一化后必须能在文档中找到 |

**标注原则**：
- 标注“能回答问题的原文片段”，不是关键词；
- 一个查询可有多条标注，覆盖多个文档或同一文档的多个片段；
- 不要标注片段序号；切片参数变化后序号会失效。

## 3. `config.json`

```json
{
  "name": "baseline",
  "tenant": "eval_baseline",
  "store": {
    "table": "embeddings_eval_baseline",
    "dimension": 1024
  },
  "pipelines": [
    {
      "key": "fine",
      "maxSegmentTokens": 300,
      "overlapTokens": 50,
      "modelKey": "eval-embedding",
      "storeKey": "eval-store"
    }
  ],
  "retrieval": {
    "topK": 5,
    "recallTopK": 20,
    "minScore": 0.2,
    "rerank": false,
    "rewrite": false
  },
  "metrics": {
    "k": [1, 3, 5, 10],
    "diagnosticTopK": 50
  }
}
```

| 字段 | 约束 |
|---|---|
| `name` | 与目录名一致 |
| `tenant` | 必须以 `eval_` 开头 |
| `store.table` | 必须以 `embeddings_eval_` 开头 |
| `store.dimension` | > 0；live 模式与 `EVAL_EMBEDDING_DIMENSION` 一致；replay 模式与夹具一致 |
| `pipelines` | 至少 1 条；`key` 唯一；`overlapTokens < maxSegmentTokens` |
| `retrieval.topK` | 1–50；必须包含在 `metrics.k` 中 |
| `retrieval.recallTopK` | ≥ `topK` 且 ≤ 50 |
| `retrieval.minScore` | 0–1 |
| `retrieval.rerank` | `true` 时必须通过 `-Deval.embeddingTenant` 复用源租户 primary `RERANK_MODEL`；record 会同时录制 `rerank.json` |
| `retrieval.rewrite` | 本期不支持；`true` 会以 V-010 显式拒绝 |
| `metrics.k` | 非空、升序、去重；每项 1–50 |
| `metrics.diagnosticTopK` | 默认 50，不得超过 50 |

### 多管线多表（可选 `stores`）

当多条管线使用不同向量表时，增加 `stores`：

```json
{
  "store": {"table": "embeddings_eval_dual_coarse", "dimension": 8},
  "stores": {
    "eval-store-coarse": {"table": "embeddings_eval_dual_coarse", "dimension": 8},
    "eval-store-fine": {"table": "embeddings_eval_dual_fine", "dimension": 8}
  },
  "pipelines": [
    {"key": "coarse", "maxSegmentTokens": 80, "overlapTokens": 10,
     "modelKey": "eval-embedding", "storeKey": "eval-store-coarse"},
    {"key": "fine", "maxSegmentTokens": 30, "overlapTokens": 5,
     "modelKey": "eval-embedding", "storeKey": "eval-store-fine"}
  ]
}
```

约束：

- 每个 `pipeline.storeKey` 必须在 `stores` 中有对应项；
- 所有 `stores` 与 `store.dimension` 必须一致；
- 省略 `stores` 时，所有管线共用 `store`。

## 4. `thresholds.json`（可选）

```json
{
  "chunkRecall@5": { "min": 0.70 },
  "documentRecall@5": { "min": 0.85 },
  "unanswerableFalsePositiveRate": { "max": 0.10 },
  "latencyP95Ms": { "max": 500 }
}
```

- 指标键必须来自报告支持的指标集合；
- 每个指标只能配置 `min` 或 `max` 之一；
- 未提供 `thresholds.json` 时，报告只记录数值，不判定通过/失败；
- 首次基线建议不配置阈值，看过数据后再定。

## 5. 文本归一化

标注匹配与校验共用以下规则：

1. Unicode NFKC 归一化；
2. 将连续空白（空格、制表、换行）折叠为单个空格；
3. 去除首尾空白；
4. 拉丁字母转小写。

中文不做分词、不做繁简转换；保留原文标点。

## 6. 版本指纹

数据集版本 = `SHA-256`，输入按以下顺序拼接：

```text
corpus: <相对路径>\n<文件内容>\n  （按路径字典序）
queries: <queries.jsonl 原文>\n
config: <config.json 原文>\n
```

`thresholds.json` 不计入数据集版本，但计入报告配置快照；修改阈值不改变数据集可比性，只改变判定结果。

## 7. 校验失败语义

任何校验失败都在摄取前终止运行，错误信息必须包含：

- 失败规则编号（见 `data-model.md` V-001–V-011）；
- 数据集名称；
- 具体条目定位：查询 `id`、文档 `docName`、字段名或行号；
- 可操作的修复提示。

不得输出“部分成功”的基线；不得在错误信息中包含模型凭据。
