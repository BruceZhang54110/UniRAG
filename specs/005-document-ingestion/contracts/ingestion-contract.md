# Contract: 摄取服务与片段回链契约

**Feature**: 文档摄取与双粒度切片 | **Date**: 2026-10-01 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

面向调用方（006 的问答编排、未来上传接口、运维）的保证条款。

## 1. 服务契约（应用内 Java API）

```text
IngestionService.ingest(IngestRequest) → IngestOutcome

IngestRequest:
  tenantCode: string        // 必填
  docName: string           // 必填，租户内唯一（业务文档标识）
  title: string?            // 可选展示名
  content: string           // 必填，非空白；本期为纯文本/Markdown
  source: string?           // 可选来源标识

IngestOutcome:
  documentId: long
  docName: string
  status: INGESTED | PARTIAL | FAILED | NOOP      // NOOP = 同内容重复摄取
  version: int
  runs: List<RunOutcome>                          // 每管线一条
    ├─ pipelineKey, status: SUCCESS | FAILED, chunkCount, errorMessage?
    └─ embeddingStoreKey, embeddingModelKey       // 实际使用的租户资源
```

**语义保证**：
- 同步返回；全部管线执行完毕才返回（spec：同步摄取模型）。
- 异常不抛出给调用方——单管线失败体现在对应 RunOutcome FAILED；仅当入参非法（空内容、缺 tenant）才抛 `IllegalArgumentException`。
- 任何 outcome 字段不得包含密钥或配置原文（FR-010）。

## 2. 管线配置语义

- 摄取执行 **该租户所有 enabled=true 的管线**；未配置任何启用管线 → 文档 FAILED，原因"无可用摄取管线"。
- `embedding_store_key` / `embedding_model_key` 引用 003 资源体系（`TenantResourceRouter` 解析）；引用不存在 → 该管线 FAILED（原因含 key 与租户，对齐 003 `TenantResourceNotFoundException` 语义）。
- 同一向量存储资源被多条管线引用 → 合法（同表多管线写入，metadata 以 pipeline_key 区分）；维度以管线配置的模型输出为准，写入不符即该管线 FAILED。

## 3. 向量 metadata 固定键（写入契约）

每个写入向量表的片段，metadata 必须恰好包含以下键（006 检索过滤依赖，禁止改动键名）：

| 键 | 类型 | 说明 |
|---|---|---|
| `doc_name` | string | 业务文档名 |
| `document_id` | long | document 表主键 |
| `pipeline_key` | string | 摄取管线 key（粒度标识） |
| `chunk_index` | int | 片段序号（0 起） |

**不放 `tenant`**：表即租户边界（004 设计）。文档级自定义 metadata 不在本期（005 spec 范围）。

## 4. 回链契约（SC-002 的判据）

- `document_chunk.embedding_id` 非空且等于向量表 `embedding_id`（一一对应）。
- 判据：任取一片段，按 embedding_id 可在对应向量表找到记录且 metadata 四键与本行一致；反查同构。

## 5. 重摄取语义（FR-006）

| 场景 | 行为 |
|---|---|
| 同 (tenant, docName)、内容指纹相同 | NOOP：数据不动，version 不变，runs 记 SUCCESS/0 |
| 同 (tenant, docName)、指纹不同 | 替换：旧片段与旧向量全删，写入新片段，version+1 |
| 任意重试 | 安全：管线内"先清后写"，重复执行结果确定 |

## 6. 与 006 的边界

- 006 通过本契约的 IngestionService 触发摄取（如问答前按需索引），不绕过本服务直写向量表。
- 006 统一观测入口消费 `ingestion_run`（本契约第 1 节的 runs 即其数据源），键名与表结构冻结于本期。
