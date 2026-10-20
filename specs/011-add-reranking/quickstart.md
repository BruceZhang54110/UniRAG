# Quickstart: 检索结果重排序（Reranking）

**Feature**: `011-add-reranking` | **Date**: 2026-10-18

本指南用于验证重排序从租户配置、检索、问答到观测的端到端行为。自动化测试不需要外部 key；手工验证需要可用的 reranker 服务。

## 1. 前置条件

- 本地 PostgreSQL + pgvector 可用，`uni_rag_db` 已完成 Flyway 迁移（含 V9）。
- 至少有 `demo` 租户及其 `demo-doc-1`、`实时语音转写大模型` 等文档。
- 已配置租户的 `EMBEDDING_MODEL`、`EMBEDDING_STORE`、`LLM`。
- 手工验证需要一个可用的 `BAAI/bge-reranker-v2-m3` 服务；不要把 key 写入仓库或日志。

## 2. 配置重排序模型

向 `tenant_resource_config` 插入一条 `RERANK_MODEL` 配置（示例值用占位符）：

```json
{
  "base-url": "https://api.siliconflow.cn/v1",
  "api-key": "<tenant-key>",
  "model-name": "BAAI/bge-reranker-v2-m3",
  "timeout": "PT3S",
  "max-documents-per-batch": 32
}
```

重启/重新加载租户资源后，`RERANK_MODEL` 应出现在已加载资源中；配置非法时只影响该租户，应用仍可启动。

## 3. 自动化验证（无外部 key）

```bash
./mvnw -Dtest=RerankEnhancementTest,BgeRerankerScoringModelTest test
./mvnw -Dtest=RerankApiIT test
./mvnw test
```

预期：

- `RerankEnhancementTest`：替身 `ScoringModel` 下顺序/截断/阈值/shadow/失败回退/无模型跳过均通过。
- `BgeRerankerScoringModelTest`：本地 HTTP 桩验证 `index` 回填、分批、缺失分数、非 2xx、超时。
- `RerankApiIT`：真实 PG + pgvector，`@MockBean TenantResourceRouter` 返回替身 `ScoringModel`；断言 `/retrieve`/`/ask` 行为、`RERANK` 观测行、租户隔离。
- 既有 `*IT`：未配置 `RERANK_MODEL` 的租户行为不变。

## 4. 手工验证：弱相关片段被排除

问一个明确指向 `实时语音转写大模型` 的问题：

```bash
curl -s -X POST localhost:8080/api/v1/ask \
  -H "X-Tenant-Code: demo" \
  -H "Content-Type: application/json" \
  -d '{"question":"《实时语音转写大模型》讲了什么？","topK":5}' | jq
```

预期：

- `answer` 非空且围绕实时语音转写。
- `citations` 只包含 `实时语音转写大模型` 的片段；`demo-doc-1`（UniRAG 演示文档）不再因为 0.7117 这类弱相关分数被引用。
- 每条 citation 含 `rerankScore`（非空），`score` 仍是原向量分数。
- `degraded=false`。

再执行检索接口：

```bash
curl -s -X POST localhost:8080/api/v1/retrieve \
  -H "X-Tenant-Code: demo" \
  -H "Content-Type: application/json" \
  -d '{"query":"《实时语音转写大模型》讲了什么？","topK":5,"rerank":true}' | jq
```

预期：`chunks` 按 `rerankScore` 降序；条数 ≤ 5；`rerankScore` 与 `score` 明显不同（刻度不同）。

## 5. 观察模式

在 `application.yml` 或环境变量中设置：

```yaml
unirag:
  retrieval:
    rerank:
      shadow: true
```

重复第 4 步：

- 响应与未启用重排时一致（数组顺序、字段）。
- `retrieval_observation` 出现 `stage='RERANK'`、`target_key='shadow'` 的行，`detail` 含建议顺序与保留标记。

```sql
SELECT trace_id, status, hit_count, item_count, target_key, detail
FROM retrieval_observation
WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code='demo')
  AND stage = 'RERANK'
ORDER BY id DESC
LIMIT 5;
```

## 6. 失败回退

- 将 `RERANK_MODEL` 的 `base-url` 改为不可达地址，或把 `timeout` 设为 `1ms`。
- 重复第 4 步：`/ask` 与 `/retrieve` 仍返回 `200`，结果退回原向量顺序；`RERANK` 行 `status='DEGRADED'`，`error_message` 可读且不含 key。
- 恢复配置后，重排重新生效。

## 7. 未配置租户的回归

找一个没有 `RERANK_MODEL` 的租户（例如 `demo2` 或新建租户），重复第 4 步：

- 响应字段与顺序与改造前一致；`rerankScore` 为 `null`/缺省。
- 不产生 `RERANK` 观测行。
- `./mvnw test` 中既有租户隔离/检索/问答用例全部保持绿色。

## 8. 观测与指标

```bash
curl -s "localhost:8080/actuator/metrics/unirag.rerank.requests" | jq
curl -s "localhost:8080/actuator/metrics/unirag.rerank.duration" | jq
```

- 计数按 `tenant` / `status` 维度。
- 延迟计时器按租户维度；标签中不得出现查询文本、文档名或模型 key。

## 9. 相关契约

- [检索/问答接口增量](./contracts/retrieval-api-delta.md)
- [重排序模型与 ScoringModel 契约](./contracts/rerank-model.md)
- [数据模型](./data-model.md)
- 010 基线契约：`specs/010-rag-retrieval/contracts/retrieval-api.md`
