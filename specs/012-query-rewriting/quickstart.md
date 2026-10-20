# Quickstart: 查询改写（Query Rewriting）

**Feature**: `012-query-rewriting` | **Date**: 2026-10-19

本指南用于验证：改写启用时，检索使用改写后的查询、提示词与响应仍使用原问题；未配置/关闭/失败时行为与改造前一致。契约细节见 [contracts/query-rewrite.md](./contracts/query-rewrite.md) 与 [contracts/retrieval-api-delta.md](./contracts/retrieval-api-delta.md)，字段定义见 [data-model.md](./data-model.md)。

## 1. 前置条件

- Java 21、Maven Wrapper（`./mvnw`）。
- 本地 PostgreSQL，已安装 `pgvector`，连接参数与 `src/main/resources/application.yml` 的 `spring.datasource` 一致；Flyway 会自动迁移。
- 无需任何外部模型凭据：自动化测试全部使用确定性替身。

## 2. 自动化验证

```bash
# 单元测试：改写扩展 + QueryTransformer 适配器
./mvnw test -Dtest=QueryRewriteEnhancementTest,LlmQueryTransformerTest

# 集成测试：真实 PostgreSQL + pgvector，确定性 QueryTransformer / EmbeddingModel / ChatModel
./mvnw test -Dtest=QueryRewriteApiIT

# 全量回归（*Test 与 *IT 统一由 test 阶段执行）
./mvnw test
```

### 预期结果

| 用例 | 断言 |
|---|---|
| 改写成功 | 进入 `EmbeddingModel.embed(String)` 的文本是改写结果；`ChatRequest` 的 UserMessage 含原问题、不含改写文本 |
| 观察模式 | 返回结果与关闭改写一致；`QUERY_REWRITE` 行 `detail` 含 `shadow=true` |
| 改写失败/非法输出 | HTTP 200；`QUERY_REWRITE` 行为 `DEGRADED`；嵌入文本为原问题 |
| 请求 `rewrite=false` | 不调用改写模型；无 `QUERY_REWRITE` 行 |
| 租户无改写资源 | 不调用改写模型；无 `QUERY_REWRITE` 行；响应与改造前一致 |
| 同时启用重排 | `ScoringModel.scoreAll(..., 原问题)`；引用仍按重排结果 |
| 跨租户 | A 的改写资源/结果不被 B 使用 |

## 3. 手工验证（可选，需要真实模型）

### 3.1 配置租户改写资源

**默认复用该租户的主语言模型**，资源配一个空对象即可（资源本身是租户级启用开关）：

```sql
INSERT INTO tenant_resource_config
    (tenant_id, resource_type, resource_key, is_primary, config)
VALUES
    ((SELECT id FROM tenant WHERE tenant_code = 'demo'),
     'QUERY_REWRITE_MODEL', 'rewriteModel', TRUE, '{}'::jsonb);
```

如果希望改写使用独立模型/凭据/更短超时，再提供完整连接字段：

```sql
UPDATE tenant_resource_config
SET config = '{
      "base-url": "https://api.siliconflow.cn/v1",
      "api-key": "<tenant-secret>",
      "model-name": "Qwen/Qwen2.5-7B-Instruct",
      "timeout": "PT1.5S",
      "temperature": 0.0,
      "max-tokens": 200
    }'::jsonb
WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = 'demo')
  AND resource_type = 'QUERY_REWRITE_MODEL'
  AND resource_key = 'rewriteModel';
```

- 资源在应用启动时由 `TenantResourceRegistrar` 加载；插入/修改后重启应用。
- `{}` 模式使用租户已有的 `LLM` 资源（含其超时配置）；专用模式才会应用资源级 `timeout`。
- 不要把真实 `api-key` 提交到版本控制；手工验证结束后可删除该行或置 `enabled = false`。

### 3.2 调用检索接口

```bash
curl -s http://localhost:8080/api/v1/retrieve \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Code: demo' \
  -d '{"query":"那个把文件切块的玩意儿怎么配？","topK":5,"rewrite":true}'
```

检查：

- `data.query` 仍是原问题；
- 返回片段与问题相关；
- 用返回的 `traceId` 查观测：

```sql
SELECT stage, status, target_key, duration_ms, hit_count, item_count, error_message, detail
FROM retrieval_observation
WHERE trace_id = '<traceId>' AND stage = 'QUERY_REWRITE';
```

预期：`status='SUCCESS'`、`target_key='rewriteModel'`、`detail` 含 `shadow=false;model=primary-llm`（或 `model=dedicated`）与脱敏摘要。

### 3.3 调用问答接口

```bash
curl -s http://localhost:8080/api/v1/ask \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Code: demo' \
  -d '{"question":"那个把文件切块的玩意儿怎么配？","topK":5,"rewrite":true}'
```

检查：

- 回答与引用围绕原问题；
- 若同时开启 `rerank`，引用按重排结果；
- 提示词使用原问题这一点由自动化测试（捕获 `ChatRequest`）保证，无需在生产日志中输出提示词。

### 3.4 关闭与降级

```bash
# 显式关闭：不应产生 QUERY_REWRITE 行
curl -s http://localhost:8080/api/v1/retrieve \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Code: demo' \
  -d '{"query":"向量检索怎么配置？","topK":5,"rewrite":false}'
```

- 把资源配置中的 `base-url` 改成一个不可达地址并重启，或用全局开关 `UNIRAG_QUERY_REWRITE_ENABLED=false`，验证：
  - 检索/问答仍返回正常结果；
  - 不可达场景下 `QUERY_REWRITE` 行为 `DEGRADED`，`error_message` 可定位；
  - 日志/观测中看不到 `api-key`。

### 3.5 灰度顺序

1. 给试点租户插入 `QUERY_REWRITE_MODEL` 配置 `{}`（复用主语言模型），重启应用。
2. 设置 `UNIRAG_QUERY_REWRITE_SHADOW=true` 并重启：观察 `QUERY_REWRITE` 行的成功率、降级原因、耗时分布与 `detail` 中的脱敏摘要；此时用户看到的检索/问答结果不变。
3. 用人工标注查询集评估改写前后的 top-5 命中率；确认无明显劣化、延迟可接受。
4. 设置 `UNIRAG_QUERY_REWRITE_SHADOW=false`，先在一个试点租户生效，监控问答延迟与引用质量。
5. 逐步扩大租户范围；只有在需要独立模型/凭据/更短超时时，才把 `{}` 改为专用连接配置。

## 4. 关键观察点

- **原问题不变**：`RetrievalRequest.query`、`PromptAssemblyStage` 的“问题：”、`RerankEnhancement` 的 query、`RetrieveResponse.query` 四处一致。
- **只替换检索文本**：`QueryEmbeddingStage` 读取 `retrievalQuery`，其余阶段读取 `query`。
- **零影响**：无资源/关闭时不调用模型、不写 `QUERY_REWRITE` 行、响应逐字段与改造前一致。
- **可观测**：一行 `QUERY_REWRITE` 与同链路其他阶段共享 `trace_id`，只含长度/脱敏摘要，不含密钥。

## 5. 验证记录

- 2026-10-19：`./mvnw test` 全量通过（185 个测试，0 失败），包含 `LlmQueryTransformerTest`、`QueryRewriteEnhancementTest`、`QueryRewriteQaIT`、`QueryRewriteRetrievalIT`、`QueryRewriteObservationIT`、`QueryRewriteShadowIT`。
- 验证覆盖：嵌入入参为改写文本、提示词/重排/响应使用原问题、fail-open 回退、shadow 不改返回、观测行与指标、跨租户隔离、日志/观测零密钥。
- 测试使用真实 PostgreSQL + pgvector 与确定性替身，不依赖外部模型凭据。
