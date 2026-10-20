# 数据模型：RAG 检索问答与全链路可观测

**Feature**: `specs/010-rag-retrieval/` | **Date**: 2026-10-14

本期**只新增一张表**（观测记录）。检索与问答不引入新的业务实体表 —— 片段、文档、管线均复用 004/005/007 已建立的模型。

## 1. 新增：观测记录表 `retrieval_observation`

Flyway 迁移：`src/main/resources/db/migration/postgresql/V8__retrieval_observation.sql`（V1–V7 已执行，**MUST NOT** 修改既有脚本）。

```sql
CREATE TABLE if not exists retrieval_observation (
   id            BIGSERIAL        PRIMARY KEY,
   trace_id      VARCHAR(32)      NOT NULL,          -- 链路标识（ULID），串联一次调用的全部阶段
   tenant_id     BIGINT           NOT NULL REFERENCES tenant (id),
   session_id    VARCHAR(128),                       -- 调用方透传，本期不持久化对话记忆
   kind          VARCHAR(16)      NOT NULL,          -- RETRIEVAL / QA / INGEST
   stage         VARCHAR(32)      NOT NULL,          -- 见 §2 阶段枚举
   target_key    VARCHAR(128),                       -- 资源 key 或向量表名（定位用）
   status        VARCHAR(16)      NOT NULL,          -- SUCCESS / FAILED / DEGRADED
   duration_ms   INT,
   hit_count     INT,                                -- 命中片段数
   top_score     DOUBLE PRECISION,                   -- 最高相似度
   item_count    INT,                                -- 通用计数（如引用条数）
   error_message VARCHAR(500),                       -- 已截断；MUST NOT 含密钥/配置原文
   query_digest  VARCHAR(500),                       -- 查询/问题摘要（留存策略见 research R3）
   document_id   BIGINT,                             -- 可空；用于与 005 摄取侧按文档关联
   created_at    TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE INDEX if not exists idx_obs_trace  ON retrieval_observation (tenant_id, trace_id);
CREATE INDEX if not exists idx_obs_time   ON retrieval_observation (tenant_id, created_at DESC);
CREATE INDEX if not exists idx_obs_stage  ON retrieval_observation (tenant_id, stage, status);
```

**设计说明**

| 决策 | 理由 |
|---|---|
| 单表承载检索/问答/摄取三类记录 | FR-006 要求"同一观测模型与查询入口"；用 `kind` + `stage` 区分，避免三张表三套查询 |
| `document_id` 可空且**不建外键** | 观测写入是 best-effort，**不得**因文档被删除或引用完整性问题而失败（FR-006） |
| `error_message` 限长 500 | 与 005 `ingestion_run` 的既有约定一致（截断且不含密钥） |
| 三个索引都带 `tenant_id` 前缀 | 查询入口严格租户隔离，避免跨租户扫描 |
| 无 `deleted` 软删列 | 观测记录按留存时长物理清理（见 §4），不参与业务软删语义 |

## 2. 阶段枚举（`stage`）

| 值 | 归属 | 说明 |
|---|---|---|
| `QUERY_EMBEDDING` | 检索/问答 | 查询向量化 |
| `VECTOR_SEARCH` | 检索/问答 | 逐表检索；每张表一条记录（`target_key` = 表名） |
| `MERGE` | 检索/问答 | 跨表归并 + topK |
| `PROMPT_ASSEMBLY` | 问答 | 提示词组装 |
| `CHAT_GENERATION` | 问答 | 聊天模型调用 |
| `INGEST` | 005 摄取 | 摄取执行（与 `ingestion_run` 对齐的投影，见 research R3） |

## 3. 复用的既有实体（不改表结构）

| 实体 / 表 | 本期的用途 | 权威定义 |
|---|---|---|
| `tenant` / `tenant_resource_config` | 经 `TenantResourceRouter` 解析租户的 `EMBEDDING_MODEL` / `EMBEDDING_STORE` / `LLM` | 003 V2 迁移 |
| `ingestion_pipeline` | 提供"已启用管线"清单与 `embedding_store_key` / `embedding_model_key`；`pipeline_key` 即检索请求里的粒度 | 005 V4 迁移 |
| `document` / `document_chunk` | 片段正文与回链（`document_id` / `pipeline_id` / `seq_index` / `embedding_id`） | 005 V4 + 007 V5 |
| `ingestion_run` | 005 摄取侧执行记录（**只读**，本期不改其语义） | 005 V4 迁移 |
| 租户向量表 `embeddings_*` | `embedding_id uuid` / `embedding vector(N)` / `text` / `metadata json` | 004 契约 §3、§4（动态表，不受 Flyway 管理） |

### 3.1 向量元数据契约（检索过滤的依据）

写入端（既有 `PipelineExecutor`）在每条的 `metadata` 里放 5 个键，检索端据此过滤与回链：

| key | 类型 | 检索端用途 |
|---|---|---|
| `doc_name` | string | 结果展示 |
| `document_id` | number | **过滤**（`documentId` 参数）+ 回链 |
| `pipeline_key` | string | **过滤/标识粒度** |
| `chunk_index` | number | 结果排序兜底 + 展示 |
| `document_version_id` | number | 版本追踪（异步摄取路径才写） |

**待确认（research R5）**：该列在 langchain4j 建表时是 `json` 而非 `jsonb`。需核实 `Filter` 翻译出的谓词在 `json` 列上的行为，以及数值型比较是否需要类型转换；若确认不可靠，数据模型层的对策（改列类型 / 建表达式索引 / 检索端改走 `-»` 强转）在 research.md 中给出。

## 4. 留存与清理

- 观测记录 MUST 有留存上限：新增配置项 `unirag.observability.retention`（`Duration` 格式，默认值在 research R3 中定），由定时任务按 `created_at` 物理删除过期行。
- 清理任务失败 MUST NOT 影响主链路；清理量 MUST 记入日志与指标。
- `query_digest`（查询/问题摘要）的留存策略在 research R3 中定：默认留存供排障，且 MUST NOT 存储超出摘要所需的内容。

## 5. 领域模型（非持久化）

| 模型 | 字段 | 说明 |
|---|---|---|
| `RetrievalRequest` | `query` / `granularity?` / `documentId?` / `topK` / `minScore` | 契约要素，见 `contracts/retrieval-api.md` §2 |
| `RetrievedChunk` | `documentId` / `docName` / `pipelineKey` / `documentVersionId?` / `chunkIndex` / `score` / `text` | 检索输出单元；排序确定性见契约 |
| `QaRequest` | `question` / `granularity?` / `topK` / `minScore` / `sessionId?` | 见契约 §3 |
| `QaAnswer` | `answer` / `citations` / `degraded` / `degradeReason?` | 见契约 §3 的降级语义表 |
| `StageContext` | `traceId` / `tenantCode` / `tenantId` / 阶段间共享数据 | 贯穿一次调用的上下文（非持久化），见 `contracts/pipeline-extension.md` §2 |

## 6. 状态与不变量

| 不变量 | 依据 |
|---|---|
| 一次调用的所有阶段记录共享同一 `trace_id` | FR-006、SC-003（断链用例通过率 0） |
| 检索结果中每条片段的 `document_id` 归属发起租户 | FR-001、SC-002 |
| `status = DEGRADED` 仅出现在"检索为空"（问答）或"部分表失败但整体返回"（检索） | 契约 §2/§3 |
| 观测写入失败时主链路结果不变，且该降级事件本身被记录 | FR-006、spec 边界情况 |
