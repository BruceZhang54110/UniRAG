# Contract: 评测触发与运行

**Feature**: `013-rag-evaluation` | **Version**: 1

本契约定义评测如何被触发、使用哪些参数、产生哪些文件，以及成功/失败语义。

## 1. 触发方式

评测通过 Maven Wrapper + `eval` profile 显式触发，复用现有测试运行器：

```bash
./mvnw test -Peval -Dtest=RagEvaluationIT ...
```

默认质量门：

```bash
./mvnw test
```

默认命令 MUST NOT 执行任何 `@Tag("eval")` 的类，MUST NOT 读取 `EVAL_EMBEDDING_*`，MUST NOT 访问外部模型。

## 2. 运行参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `-Deval.dataset=<name>` | 是 | 数据集目录名，对应 `eval/datasets/<name>` |
| `-Deval.mode=live\|record\|replay` | 是 | 执行模式 |
| `-Deval.reportDir=<path>` | 否 | 报告输出目录，缺省 `eval/reports` |
| `-Deval.baselineDir=<path>` | 否 | 基线目录，缺省 `eval/baselines` |
| `-Deval.failOnThreshold=true\|false` | 否 | 是否将阈值未通过视为运行失败，缺省 `false` |
| `-Deval.compareTo=<report path>` | 否 | 运行完成后与指定报告比较 |
| `-Deval.embeddingTenant=<tenant>` | 否 | 复用数据库里已有租户的 `EMBEDDING_MODEL`（以及 `rerank=true` 时的 `RERANK_MODEL`）；设置后不再读取 `EVAL_EMBEDDING_*` |
| `-Deval.embeddingModelKey=<key>` | 否 | 指定源租户的模型资源 key；省略时取该租户的 primary 嵌入模型 |

**模式语义**：

| 模式 | 外部模型 | 录制夹具 | 典型用途 |
|---|---|---|---|
| `live` | 是 | 否 | 首次权威基线 |
| `record` | 是 | 是 | 更新 replay 夹具 |
| `replay` | 否 | 否 | 日常回归、CI、报告比较 |

## 3. 环境变量

仅 `live` / `record` 模式需要：

| 变量 | 说明 |
|---|---|
| `EVAL_EMBEDDING_BASE_URL` | OpenAI 兼容嵌入端点 |
| `EVAL_EMBEDDING_API_KEY` | 凭据；不得写入任何文件 |
| `EVAL_EMBEDDING_MODEL` | 模型标识，如 `bge-m3` |
| `EVAL_EMBEDDING_DIMENSION` | 向量维度，必须与 `config.json` 的 `store.dimension` 一致 |

`replay` 模式 MUST NOT 读取上述变量；即使存在也不得使用。

若指定 `-Deval.embeddingTenant`，评测直接解析该租户已注册的嵌入模型 Bean：凭据只存在于
`tenant_resource_config`，不进入环境变量、数据集、报告或日志。源模型的实际输出维度会以一次
真实嵌入探测校验，必须与 `config.json` 的 `store.dimension` 一致。

`rerank=true` 时也复用该租户的 primary `RERANK_MODEL`；record 模式会把重排分数一并写入
`eval/fixtures/<dataset>/rerank.json`，replay 模式只读该夹具，不访问外部重排服务。
`rewrite=true` 暂不支持，评测装配会以 V-010 显式拒绝。

## 4. 执行顺序

```text
加载并校验数据集
  → 校验运行参数与模式
  → 准备评测租户与资源（Registrar）
  → 清理评测索引
  → 摄取语料（IngestionService，同步）
  → 逐查询执行：诊断遍 → 生产遍
  → 计算指标与归因
  → 写出报告（JSON + Markdown）
  → 可选：与基线比较并输出门禁结果
```

## 5. 报告路径

| 产物 | 路径 |
|---|---|
| 运行报告 JSON | `<reportDir>/<runId>.json` |
| 运行报告 Markdown | `<reportDir>/<runId>.md` |
| 已确认基线 | `<baselineDir>/<dataset>.json` |
| 比较结果 | `<reportDir>/<runId>-comparison.json` |

`runId` 格式：`yyyyMMdd'T'HHmmss'Z'-<7位提交短哈希>`；冲突时追加 `-1`、`-2`。写入使用临时文件 + 原子移动。

## 6. 退出语义

| 场景 | 行为 |
|---|---|
| 数据集/配置非法 | 运行失败；不摄取；错误定位到具体条目；JUnit 失败 |
| 摄取失败 | 运行失败；报告 `run.status=FAILED`；不得作为基线 |
| 模型不可用/超时 | 运行失败；错误不含凭据；不得回退到 replay |
| 查询执行失败 | 运行失败；不得跳过该查询后继续产出“成功”基线 |
| 指标完成 | 运行成功；报告 `run.status=SUCCESS` |
| 阈值未通过且 `failOnThreshold=true` | 运行完成但 JUnit 失败；报告保留完整指标 |
| 阈值未通过且 `failOnThreshold=false` | 运行成功；比较结果标记未通过 |

## 7. 隔离与清理

- 评测租户必须匹配 `eval_*`；向量表必须匹配 `embeddings_eval_*`；否则拒绝运行。
- 运行前清理评测租户的片段、文档、摄取记录与向量表行；RUN 级清理失败即终止。
- 运行前后对非评测租户执行数据指纹检查；任何变化视为隔离失败，运行标记 `FAILED`。
- 同一数据集同一时刻只允许一个运行；并发运行必须被拒绝，不得互相覆盖报告。

## 8. 示例

```bash
# 首次权威基线（真实模型）+ 录制夹具
EVAL_EMBEDDING_BASE_URL=http://localhost:11434/v1 \
EVAL_EMBEDDING_API_KEY=local \
EVAL_EMBEDDING_MODEL=bge-m3 \
EVAL_EMBEDDING_DIMENSION=1024 \
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline -Deval.mode=record

# 日常回归（无外部凭据）
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline -Deval.mode=replay

# 报告比较
./mvnw test -Peval -Dtest=ReportCompareIT \
  -Deval.baseline=eval/baselines/baseline.json \
  -Deval.candidate=eval/reports/20261019T120000Z-abc1234.json
```
