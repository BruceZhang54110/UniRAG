# Contract: 冻结向量夹具

**Feature**: `013-rag-evaluation` | **Version**: 1

冻结向量夹具用于 `replay` 模式：在不访问外部模型的前提下，复现一次真实模型运行的向量化结果。

## 1. 文件位置

```text
eval/fixtures/<dataset>/embeddings.json
```

- `<dataset>` 与数据集名称一致；
- 夹具进入版本控制；
- 同一数据集可保留多个夹具版本时，使用 `embeddings-<model>.json`，由 `config.json` 的 `modelKey` 或运行参数选择；首版使用固定路径 `embeddings.json`。

## 2. JSON 结构

```json
{
  "fixtureVersion": 1,
  "model": "bge-m3",
  "dimension": 1024,
  "vectors": {
    "9f2c...e1": [0.0123, -0.0456, 0.0789],
    "7a1b...c4": [0.0333, 0.0210, -0.0098]
  }
}
```

| 字段 | 类型 | 约束 |
|---|---|---|
| `fixtureVersion` | int | 当前为 1 |
| `model` | string | 录制时的模型标识；报告记录该值 |
| `dimension` | int | > 0；必须与 `config.json` 的 `store.dimension` 一致 |
| `vectors` | object | 键为 64 位小写十六进制 SHA-256；值为定长浮点数组 |

## 3. 键规则

```text
key = lowercaseHex(SHA-256(UTF-8 bytes of exact text))
```

- 使用**精确文本**：摄取时送入 `EmbeddingModel` 的片段文本，查询时送入的 `retrievalQuery`；
- 不做归一化、不去空白、不截断；
- 同一文本多次出现只存一份；
- 不同切片参数会产生不同文本，需要重新录制。

## 4. 录制（record 模式）

1. 用真实模型包装 `RecordingEmbeddingModel`；
2. `embedAll`/`embed` 返回真实结果，同时按精确文本记录 `key → float[]`；
3. 摄取完成与全部查询完成后，校验记录数量与维度；
4. 按 key 字典序写出 JSON；
5. 使用临时文件 + 原子移动，避免中断留下半份夹具；
6. 录制失败时不得覆盖既有夹具。

**录制时机**：`record` 模式在评测运行结束后写出；若运行失败，不写夹具。

## 5. 复现（replay 模式）

1. 加载夹具，校验 `dimension`；
2. 以 `ReplayEmbeddingModel` 替换租户嵌入模型；
3. 每次 `embed`/`embedAll` 按精确文本查表；
4. 缺失键立即失败：

```text
Replay fixture missing vector: dataset=baseline, model=bge-m3,
key=9f2c..., textPreview="配置 tenant_resource_config 时……"
```

5. 不得回退到真实模型、不得使用确定性哈希向量、不得静默跳过缺失片段。

## 6. 维度与模型一致性

- 夹具 `dimension` ≠ 数据集 `store.dimension` → 拒绝运行；
- 夹具 `model` 与 `config.json`/环境变量模型标识不一致 → 拒绝运行，除非显式允许 `-Deval.allowModelMismatch=true`（首版不提供）；
- live/record 模式环境变量维度 ≠ `store.dimension` → 拒绝运行。

## 7. 版本与失效

夹具与以下输入绑定：

- 语料内容（切片文本由内容与切片参数共同决定）；
- 切片参数（`maxSegmentTokens`、`overlapTokens`、策略）；
- 查询文本与改写开关（改写后查询也需录制）；
- 嵌入模型标识与维度。

任一变化都应重新录制。报告记录夹具 `model` 与维度；replay 报告与 live 报告不可直接比较。

## 8. 大小控制

- 首版规模约 10–50 篇文档、30–100 条查询，切片后约 50–300 条文本；
- 1024 维浮点 JSON 约 10–20 KB/条，总大小约 1–5 MB，可接受；
- 若后续规模显著增长，可改为压缩二进制或分片夹具，但需新增版本号并保持旧夹具可读。
