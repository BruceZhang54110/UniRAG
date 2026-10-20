# Data Model: 文档摄取与双粒度切片

**Feature**: 文档摄取与双粒度切片 | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

## 实体关系总览

```text
Tenant (003 已有)
  ├── ingestion_pipeline  1 ── N（租户内的摄取管线，粗/细 = 两行）
  │       └── 引用（非外键）：EMBEDDING_STORE / EMBEDDING_MODEL 资源 key（003 tenant_resource_config）
  └── document            1 ── N ingestion_run（每次摄取每管线一条）
            1 ── N document_chunk（每管线一组，经 pipeline 关联）

document_chunk.embedding_id ──→ 租户向量表 embedding_id（004 动态表，跨表引用非 DB 外键）
```

## 表结构（Flyway V4__ingestion_schema.sql）

### ingestion_pipeline（摄取管线）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | bigserial | PK | |
| tenant_id | bigint | FK→tenant, NOT NULL | |
| pipeline_key | varchar(64) | NOT NULL | 租户内唯一（如 coarse/fine） |
| strategy | varchar(32) | NOT NULL, 默认 'RECURSIVE' | 切片策略（可插拔，v1 仅 RECURSIVE） |
| max_segment_tokens | int | NOT NULL | 片段 token 上限（默认 500） |
| overlap_tokens | int | NOT NULL, 默认 50 | 相邻片段重叠 |
| embedding_store_key | varchar(64) | NOT NULL | 引用租户 EMBEDDING_STORE 资源 key |
| embedding_model_key | varchar(64) | NOT NULL | 引用租户 EMBEDDING_MODEL 资源 key |
| enabled | boolean | NOT NULL, 默认 true | |
| created_at / updated_at / deleted | timestamp×2 / boolean | | 沿用 003 软删除约定 |

唯一约束：`(tenant_id, pipeline_key)`（含 deleted=false 语义由应用保证；物理唯一约束允许复活场景先删后插）

### document（文档）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | bigserial | PK | |
| tenant_id | bigint | FK, NOT NULL | |
| doc_name | varchar(255) | NOT NULL | 调用方文档标识（租户内唯一） |
| title | varchar(512) | 可空 | 展示名 |
| content_fingerprint | char(64) | NOT NULL | SHA-256 hex（research R4） |
| status | varchar(16) | NOT NULL | INGESTING / INGESTED / PARTIAL / FAILED |
| chunk_count | int | 默认 0 | 全部管线片段总数 |
| version | int | 默认 1 | 每次内容变更重摄取 +1 |
| created_at / updated_at / deleted | | | |

唯一约束：`(tenant_id, doc_name)`（replace 语义的裁决者，research R5）

### document_chunk（文档切片）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | bigserial | PK | |
| document_id | bigint | FK, NOT NULL | |
| pipeline_id | bigint | FK→ingestion_pipeline, NOT NULL | 粒度归属 |
| seq_index | int | NOT NULL | 片段序号（langchain4j splitter 产生的 index） |
| content | text | NOT NULL | 片段文本 |
| token_count | int | NOT NULL | 计量（IngestionTokenizer） |
| embedding_id | varchar(64) | 可空 | 向量表记录 id（addAll 返回；回链核心） |
| created_at | timestamp | | |

索引：`(document_id, pipeline_id)`（替换清理与回链查询）；唯一 `(document_id, pipeline_id, seq_index)` 由"先清后写"保证，不加物理约束（便于清理语义）

### ingestion_run（管线执行记录——观测载体）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | bigserial | PK | |
| document_id | bigint | FK, NOT NULL | |
| pipeline_id | bigint | FK, NOT NULL | |
| status | varchar(16) | NOT NULL | SUCCESS / FAILED |
| chunk_count | int | 默认 0 | |
| error_message | text | 可空 | 截断 500 字符（对齐 003 rootCauseMessage 约定），不含密钥 |
| started_at / finished_at | timestamp | | |

## 状态机

```text
document.status:
  (创建) ──→ INGESTING ──全部管线 SUCCESS──→ INGESTED
                  │──部分 SUCCESS──→ PARTIAL
                  │──全部 FAILED──→ FAILED
                  └──进程重启滞留 >5min──→ FAILED(interrupted)   [启动恢复任务]
重新摄取：INGESTED/PARTIAL/FAILED 均可再次进入 INGESTING（version+1，先清后写）
```

## 校验规则（来自 spec FR）

- 空/纯空白内容 → 摄取前即拒绝（可读原因），不产生任何记录（spec 边界情况）
- 管线引用的 EMBEDDING_STORE / EMBEDDING_MODEL 资源不存在 → 该管线 FAILED（资源定位在 Router 处抛 TenantResourceNotFoundException，原因入 run 记录）
- 向量写入维度与表不符 → 该管线 FAILED（004 Verifier 语义延续），其余管线不受影响
- 重复摄取同内容 → no-op（指纹相同，status 保持 INGESTED，run 记 SUCCESS 0 片段或跳过——实现取"记录 SUCCESS/0 片段"便于观测）

## 迁移

| 迁移 | 内容 |
|---|---|
| V4__ingestion_schema.sql | 上列四表 + 索引 + 外键 + 两唯一约束 |

与 V3 关系：无依赖冲突；V4 不含任何向量 DDL（向量表仍由 004 机制动态创建）。
