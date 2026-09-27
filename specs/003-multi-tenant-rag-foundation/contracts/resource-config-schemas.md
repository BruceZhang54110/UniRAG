# Contract: 各资源类型 JSON 配置 Schema

**Feature**: 多租户 RAG 资源基座 | **Date**: 2026-09-16 | **Spec**: [spec.md](../spec.md)（FR-002 / FR-005）

`tenant_resource_config.config`（JSONB）按 `resource_type` 区分 Schema。各 Provider 负责解析与校验；缺必填字段视为该条配置构建失败（FR-006 记录并跳过，FR-010 不部分注册）。

## LLM → OpenAiChatModel（LlmResourceProvider）

```json
{
  "base-url": "https://api.openai.com/v1",
  "api-key": "${...}",
  "model-name": "gpt-4o-mini",
  "temperature": 0.7,
  "max-tokens": 4096,
  "timeout": "PT60S",
  "log-requests": false,
  "log-responses": false
}
```

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| base-url | 是 | — | OpenAI 兼容端点 |
| api-key | 是 | — | 敏感信息，禁止入日志（FR-008） |
| model-name | 是 | — | |
| temperature / max-tokens | 否 | 提供方默认 | |
| timeout | 否 | 提供方默认 | ISO-8601 Duration |
| log-requests / log-responses | 否 | false | 调试开关，开启后由 langchain4j 输出请求/响应日志 |

## EMBEDDING_MODEL → OpenAiEmbeddingModel（EmbeddingModelResourceProvider）

```json
{
  "base-url": "https://api.openai.com/v1",
  "api-key": "${...}",
  "model-name": "text-embedding-3-small",
  "max-segments-per-batch": 32,
  "timeout": "PT60S"
}
```

| 字段 | 必填 | 默认 |
|---|---|---|
| base-url | 是 | — |
| api-key | 是 | — |
| model-name | 是 | — |
| max-segments-per-batch | 否 | 提供方默认 |
| timeout | 否 | 提供方默认 |

## EMBEDDING_STORE → PgVectorEmbeddingStore（EmbeddingStoreResourceProvider）

```json
{
  "host": "localhost",
  "port": 5432,
  "database": "uni_rag_db",
  "user": "uni_rag_user",
  "password": "${...}",
  "table": "embeddings_acme",
  "dimension": 1024,
  "create-table": true
}
```

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| host / port / database / user / password | 是 | — | password 敏感（FR-008） |
| table | 是 | — | 建议按租户区分表名 |
| dimension | 是 | — | 必须与嵌入模型输出维度一致 |
| create-table | 否 | true | 表不存在时自动建表 |

## RERANK_MODEL

本期无 Provider。配置该类型的记录：启动期记录 WARN（tenant/type/key + "no provider"）并跳过，不影响其他资源。

## 通用规则

- 未知字段：忽略（向前兼容新增参数）。
- `config` 非合法 JSON 对象：该条构建失败。
- 任何字段解析/校验失败：该条构建失败，错误日志含 tenantCode/type/resourceKey 与原因，**不含** config 原文（FR-008）。
