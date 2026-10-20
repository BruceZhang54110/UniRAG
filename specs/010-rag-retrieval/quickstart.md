# Quickstart：RAG 检索问答端到端验证

**Feature**: `specs/010-rag-retrieval/` | **Date**: 2026-10-14

本指南给出可执行的验证场景，证明 US1（检索）/ US2（问答）/ US3（可观测）/ FR-013（最小前端）成立。
接口字段以 `contracts/retrieval-api.md` 为准，表结构以 `data-model.md` 为准，此处不重复。

## 前置条件

- JDK 21、Maven Wrapper（`./mvnw`）
- 可用的 PostgreSQL 16 + pgvector 扩展（扩展由 V3 迁移幂等启用）
- 环境变量 `PGPASSWORD`（`application.yml` 数据源密码占位符所需）
- 一个 OpenAI 兼容的嵌入与聊天模型端点（**仅手工场景需要**；自动化测试用确定性替身，不需要凭据）

## 场景 0：准备租户与资源（一次性）

检索/问答的全部依赖都来自 `tenant_resource_config`，且**在应用启动时注册为 Bean**。示例（可按需替换端点与模型）：

```sql
-- 租户
INSERT INTO tenant (tenant_code, name) VALUES ('demo', 'Demo 知识库');

-- 嵌入模型（此处以 OpenAI 兼容端点为示例；维度必须与下面 dimension 一致）
INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config)
VALUES ((SELECT id FROM tenant WHERE tenant_code='demo'), 'EMBEDDING_MODEL', 'default', true,
'{"base-url":"https://api.siliconflow.cn/v1","api-key":"<KEY>","model-name":"BAAI/bge-m3",
  "max-segments-per-batch":32}'::jsonb);

-- 向量存储（bge-m3 = 1024 维）
INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config)
VALUES ((SELECT id FROM tenant WHERE tenant_code='demo'), 'EMBEDDING_STORE', 'default', true,
'{"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
  "password":"<PGPASSWORD>","table":"embeddings_demo","dimension":1024,"create-table":true}'::jsonb);

-- 聊天模型（问答必需；纯检索场景可不配）
INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config)
VALUES ((SELECT id FROM tenant WHERE tenant_code='demo'), 'LLM', 'default', true,
'{"base-url":"https://api.siliconflow.cn/v1","api-key":"<KEY>","model-name":"Qwen/Qwen2.5-7B-Instruct"}'::jsonb);

-- 摄取管线（enabled=true 才会被检索纳入）
INSERT INTO ingestion_pipeline
  (tenant_id, pipeline_key, strategy, max_segment_tokens, overlap_tokens,
   embedding_store_key, embedding_model_key, enabled)
VALUES ((SELECT id FROM tenant WHERE tenant_code='demo'),
        'main', 'RECURSIVE', 500, 50, 'default', 'default', true);
```

> `timeout` 若配置，MUST 用 ISO-8601（如 `"PT60S"`）；写成 `"60s"` 会抛 `Duration.parse` 异常，
> 且因注册失败被隔离，只在后续调用时报"资源不存在"。

## 场景 1：自动化测试（无外部凭据）

```bash
export PGPASSWORD=<PGPASSWORD>
./mvnw test
```

**预期**：全绿。检索 `*IT` 用确定性嵌入替身（不需网络）、问答 `*IT` 用替身聊天模型；断言覆盖向量行、观测行、跨租户隔离与降级路径。

## 场景 2：手工端到端（真实模型）

### 2.1 启动应用并确认资源注册

```bash
export PGPASSWORD=<PGPASSWORD>
./mvnw spring-boot:run
```

启动日志 MUST 出现（每个资源一行）：
`Registered tenant resource: embeddingModel_demo_default` / `embeddingStore_demo_default` / `llm_demo_default`

健康检查：`curl -s localhost:8080/actuator/health` → `{"status":"UP"}`

### 2.2 摄取两份文档（一份相关、一份无关，用于验证排序）

```bash
printf '# UniRAG 演示\n原文通过 OpenDAL 存入 PostgreSQL 的 bytea 键值表，向量存在 pgvector 表中。\n' > /tmp/a.md
printf '# 手冲咖啡\n浅烘焙豆子用 92 度水温，粉水比 1:15，闷蒸 30 秒。\n' > /tmp/b.md
for f in a b; do
  curl -s -X POST localhost:8080/api/v1/documents -H "X-Tenant-Code: demo" \
    -F "file=@/tmp/$f.md" -F "docName=demo-$f" -F "title=demo-$f"
done
```

**预期**：两次都 `"status":"INGESTED"`，`pipelines[0].status = SUCCESS`。

### 2.3 检索（US1）

```bash
curl -s -X POST localhost:8080/api/v1/retrieve -H "X-Tenant-Code: demo" \
  -H 'Content-Type: application/json' \
  -d '{"query":"原文文件存在哪里？","topK":5}' | jq .
```

**预期**（验收要点）：

- `chunks[0].docName == "demo-a"`，且其 `score` **明显高于** `demo-b`（语义排序生效）
- `chunks` 按 `score` 降序；重复同一请求，结果**逐字一致**（FR-003 确定性）
- 每条带 `documentId` / `pipelineKey` / `chunkIndex` / `text`

**粒度过滤**：

```bash
curl -s -X POST localhost:8080/api/v1/retrieve -H "X-Tenant-Code: demo" \
  -H 'Content-Type: application/json' -d '{"query":"存储","granularity":"main"}' | jq .
curl -s -X POST localhost:8080/api/v1/retrieve -H "X-Tenant-Code: demo" \
  -H 'Content-Type: application/json' -d '{"query":"存储","granularity":"nope"}' | jq .
```

**预期**：第一个为 `200` 且 `searchedTables == 1`；第二个为 `400 GRANULARITY_NOT_FOUND`，`error.details.available` 列出 `["main"]`（**不得**静默退化成全量检索）。

### 2.4 问答（US2）

```bash
curl -s -X POST localhost:8080/api/v1/ask -H "X-Tenant-Code: demo" \
  -H 'Content-Type: application/json' \
  -d '{"question":"原文文件存在哪里？"}' | jq .
```

**预期**：`answer` 非空且与知识库内容一致；`citations` 至少 1 条，其 `docName == "demo-a"`；`degraded == false`。

**空检索降级**：

```bash
curl -s -X POST localhost:8080/api/v1/ask -H "X-Tenant-Code: demo" \
  -H 'Content-Type: application/json' \
  -d '{"question":"量子引力与意式浓缩的萃取关系？"}' | jq .
```

**预期**：`200`，`degraded == true`，`degradeReason == "NO_CONTEXT"`，`citations == []`，`answer` 为"未找到相关内容"文案，**不得**编造。

### 2.5 观测查询（US3）

```bash
curl -s "localhost:8080/api/v1/observations?traceId=<上一步返回的 traceId>" \
  -H "X-Tenant-Code: demo" | jq .
```

**预期**：返回同一次调用的全部阶段（`QUERY_EMBEDDING` / `VECTOR_SEARCH` / `MERGE` / `PROMPT_ASSEMBLY` / `CHAT_GENERATION`），`durationMs` 均有值；摄取侧记录也可用 `?documentId=` 或 `?stage=INGEST` 查到。

**指标**：`curl -s localhost:8080/actuator/metrics` 中可检索到检索/问答的计数与耗时指标。

### 2.6 敏感信息红线（必查）

```bash
grep -iE "sk-|api-key|password" <(curl -s "localhost:8080/api/v1/observations?size=100" -H "X-Tenant-Code: demo")
psql -h localhost -U uni_rag_user -d uni_rag_db -tAc \
  "select count(*) from retrieval_observation where error_message ilike '%sk-%' or query_digest ilike '%sk-%';"
```

**预期**：两处均为 `0` / 无输出。

## 场景 3：最小前端（FR-013）

```bash
cd web && npm run dev
```

浏览器打开 `/chat`，逐项确认：

1. **加载态**：提交问题后出现加载指示（不是空白屏）
2. **正常态**：展示回答文本 + 引用片段（含文档标识、片段序号、原文）
3. **空结果态**：问一个知识库无关的问题，界面显示"未找到相关内容"，不显示假答案
4. **失败态**：把 `X-Tenant-Code` 配成未知租户或停掉模型端点后提交，界面显示可读错误而非白屏/裸异常

前端单测：`npm run test` 通过（覆盖上述三态）。

## 场景 4：隔离验证（SC-002）

建第二个租户（各自嵌入模型与向量表），摄取相似主题文档，用同一 `query` 分别调用 `/retrieve`：

```bash
for t in demo other; do
  curl -s -X POST localhost:8080/api/v1/retrieve -H "X-Tenant-Code: $t" \
    -H 'Content-Type: application/json' -d '{"query":"原文存在哪里"}' \
    | jq -r ".data.chunks[].docName"
done
```

**预期**：两次输出的 `docName` 集合**完全不重叠**；任何情况下都取不到对方租户的片段。

## 验收对照

| 场景 | 覆盖 |
|---|---|
| 1 | FR-010、SC-006（无凭据可跑且全绿） |
| 2.2–2.3 | FR-001、FR-002、FR-003、SC-001（纯检索 < 1s） |
| 2.4 | FR-004、FR-005、SC-005（空结果降级） |
| 2.5 | FR-006、FR-007、SC-003、SC-004 |
| 2.6 | FR-008、SC-006 |
| 3 | FR-013 |
| 4 | FR-001、SC-002 |
