# Data Model: 文档上传与文件存储

**Feature**: 文档上传与文件存储 | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md)

## 实体关系总览

```text
Tenant (003 已有)
  └── upload_session  1 ── N upload_chunk（分块暂存，完成/过期后清除）
  └── document (005 已有)  1 ── 1 document_file（本期新增关联：原文元数据）
        └── document_file.storage_path ──→ document_blob.path（OpenDAL postgresql 服务，key→bytea）

完成拼装 = 单事务：读 upload_chunk → 写 document_blob（经 OpenDAL）→ 建 document + document_file → 删 upload_chunk
触发摄取 = 事务外调用 005 IngestionService（其自身保证幂等/串行化，research R5）
```

## 表结构（Flyway V5__upload_storage_schema.sql）

### document_file（文档原文元数据，与 blob 分离）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| document_id | bigint | PK, FK→document | 1:1 |
| storage_path | varchar(1024) | NOT NULL | OpenDAL 键：`document_file/{tenantCode}/{docName}/v{version}`（research R7） |
| file_name | varchar(512) | NOT NULL | 归一化后的原始文件名（去路径分隔符，FR 边界） |
| mime_type | varchar(128) | NOT NULL | 固定 `text/plain` / `text/markdown`（v1 仅文本类） |
| size_bytes | bigint | NOT NULL | 原文字节数 |
| created_at / updated_at | timestamp | | 沿用项目惯例 |

查询路径分离：文档列表/详情 JOIN `document` + `document_file`（均小字段）；下载时才按 storage_path 经 OpenDAL 取 blob。

### upload_session（分块上传会话）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | bigserial | PK | 上传标识（完成前对客户端 opaque） |
| tenant_id | bigint | FK→tenant, NOT NULL | |
| doc_name | varchar(255) | NOT NULL | 目标文档标识（租户内唯一，005 语义） |
| title | varchar(512) | 可空 | |
| total_size | bigint | NOT NULL | 声明的总字节数（与 totalChunks×chunkSize 一致性校验） |
| chunk_size | int | NOT NULL | 固定分块大小（默认 5MB，尾块可小） |
| total_chunks | int | NOT NULL | 总块数 |
| content_sha256 | char(64) | NOT NULL | 声明的全文 SHA-256（完成时校验，research R4-3） |
| status | varchar(16) | NOT NULL, 默认 INIT | INIT / COMPLETED / EXPIRED |
| created_at / updated_at | timestamp | | |
| expires_at | timestamp | NOT NULL | 默认 now()+24h；过期可被清理（spec 边界） |

索引：`(tenant_id, status, expires_at)`（过期清理扫描）；唯一性依赖 document 表 `(tenant_id, doc_name)`（完成建文档时裁决并发完成，后完成者按 005 指纹语义 NOOP/替换）。

### upload_chunk（分块暂存，完成/过期即删）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| session_id | bigint | FK→upload_session | |
| seq | int | NOT NULL | 块序号，0 起 |
| size_bytes | int | NOT NULL | 本块字节数 |
| data | bytea | NOT NULL | 块内容（TOAST 外置） |
| created_at | timestamp | | |

主键：`(session_id, seq)`——同块重传 = 幂等覆盖（断点续传/乱序补传的裁决者）；查询 `SELECT seq FROM upload_chunk WHERE session_id=?` 即"已收块清单"接口的数据源。

### document_blob（OpenDAL postgresql 服务专用，Flyway 建表，业务代码不直读写）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| path | text | PK | OpenDAL keyField（默认 `path`） |
| data | bytea | NOT NULL | OpenDAL valueField（默认 `data`） |

> 仅经 OpenDAL Operator 访问（StorageAutoConfiguration 注入）。列名与 OpenDAL postgresql 服务默认 key/value 字段对齐；若服务版本要求特定列类型，tasks 阶段以官方文档核实后微调（不外键、无索引——键值查找靠 PK）。

## 状态转换

```text
upload_session: INIT ──(complete 成功)──→ COMPLETED（暂存块删除，转生为 document）
                INIT ──(expires_at 到点)──→ EXPIRED（连同暂存块被清理任务删除）

document: 沿用 005 状态机（INGESTING / INGESTED / PARTIAL / FAILED），本期仅增加 file 关联与 source="upload"
```

## 校验规则（来自 spec FR-003 / research R6）

- `upload_session`：chunk_size ≤ 10MB、total_chunks ≤ 10000（50MB/5MB 派生上限，可配置）；`total_size` ≤ 单文件上限（默认 50MB）。
- 一次性上传：`Content-Length` 预检 + 实际接收终检 ≤ 10MB（小文件阈值）。
- 文件内容：扩展名白名单（`.txt/.md/.markdown`）+ UTF-8 可解码且无 NUL 字节。
- 文件名：剔除路径分隔符与 `..` 段，长度截断 512，非 UTF-8 替换为 `_`。
