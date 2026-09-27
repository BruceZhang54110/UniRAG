# Data Model: 多租户 RAG 资源基座

**Feature**: `specs/003-multi-tenant-rag-foundation/spec.md` | **Date**: 2026-09-16

## 持久化实体（已有表结构，本期不新增迁移）

### tenant（V2 迁移已建）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGSERIAL | PK | 内部主键 |
| tenant_code | VARCHAR(64) | NOT NULL UNIQUE（部分唯一：`deleted=false`） | 对外租户标识，Bean 命名与 Router 入参使用 |
| name | VARCHAR(128) | NOT NULL | 租户名称 |
| enabled | BOOLEAN | NOT NULL DEFAULT TRUE | 停用租户不加载任何资源 |
| created_at / updated_at | TIMESTAMP | NOT NULL DEFAULT NOW() | |
| deleted | BOOLEAN | NOT NULL DEFAULT FALSE | 逻辑删除 |

### tenant_resource_config（V2 迁移已建）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGSERIAL | PK | 排序依据（重复 primary 防御取 id 最小者） |
| tenant_id | BIGINT | NOT NULL, FK→tenant.id | |
| resource_type | VARCHAR(32) | NOT NULL | `EMBEDDING_MODEL` / `EMBEDDING_STORE` / `RERANK_MODEL` / `LLM` |
| resource_key | VARCHAR(64) | NOT NULL | 租户内同类型唯一（部分唯一索引 `deleted=false`） |
| is_primary | BOOLEAN | NOT NULL DEFAULT FALSE | 每租户每类型至多一条主资源（部分唯一索引 `is_primary AND enabled AND deleted=false`） |
| config | JSONB | NOT NULL | 各 Provider 自定义 Schema（见 contracts/resource-config-schemas.md） |
| enabled | BOOLEAN | NOT NULL DEFAULT FALSE | 停用的单条资源不加载 |
| created_at / updated_at | TIMESTAMP | | |
| deleted | BOOLEAN | NOT NULL DEFAULT FALSE | 逻辑删除 |

**本期对持久化层唯一的改动**：MP 实体 `TenantResourceConfig` 映射方式升级（camelCase 字段 + `@TableField` 显式列名 + `config` 列使用 `JacksonTypeHandler`、`autoResultMap=true`），使 JSONB 自动映射为 `JsonNode`。表结构不变，无新迁移。

## 领域对象

### TenantResourceConfig（com.unirag.tenant.resource，已有，待补全）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 配置记录 ID |
| tenantCode | String | 冗余租户编码（联表查询时带出，领域对象不暴露 tenant_id） |
| resourceType | ResourceType | 枚举 |
| resourceKey | String | 原始 key（未规范化） |
| primary | boolean | 是否主资源 |
| config | JsonNode | JSON 配置体，由对应 Provider 解析 |
| enabled | boolean | |

> 与持久化实体分离（R9）：Registrar/Router/Provider 只依赖本领域对象。

### ResourceType（已有枚举，新增 beanPrefix()）

| 枚举值 | beanPrefix() | 对应 langchain4j 类型 | 本期 Provider |
|---|---|---|---|
| LLM | `llm` | `OpenAiChatModel` | LlmResourceProvider |
| EMBEDDING_MODEL | `embeddingModel` | `OpenAiEmbeddingModel` | EmbeddingModelResourceProvider |
| EMBEDDING_STORE | `embeddingStore` | `PgVectorEmbeddingStore` | EmbeddingStoreResourceProvider |
| RERANK_MODEL | `rerankModel` | —（后续扩展） | 无 → 告警跳过 |

### TenantResourceKey（新增，不可变值对象）

`(tenantCode, ResourceType, resourceKey)` —— 注册索引的键；`equals/hashCode` 全字段。

### TenantResourceIndex（新增）

Registrar 构建、注入 Router 的不可变注册索引：

- `Map<TenantResourceKey, String>`：key → beanName
- `Map<(tenantCode, ResourceType), NavigableMap<Long, TenantResourceKey>>`：primary 索引（key 为配置 id，重复 primary 防御时取 firstKey）
- `Set<(tenantCode, ResourceType)>`：已注册 (tenant, type) 集合，支撑 getPrimary 的"唯一实例回退"

构建完成后包装为不可变（`Map.copyOf` / `Collections.unmodifiableMap`），启动期之后不再变更（热更新不在本期范围）。

## Bean 命名规则（R2）

```
beanName = resourceType.beanPrefix() + "_" + normalize(tenantCode) + "_" + normalize(resourceKey)
normalize(s) = s 中 [^a-zA-Z0-9_-] 字符替换为 "_"
```

示例：`embeddingModel_acme_openai`、`embeddingStore_acme_default`

## 校验规则汇总

| 规则 | 来源 |
|---|---|
| `tenant_code` 全局唯一（活跃租户） | DB 唯一索引（已有） |
| `(tenant_id, resource_type, resource_key)` 租户内唯一 | DB 唯一索引（已有） |
| `(tenant_id, resource_type)` 最多一条 enabled primary | DB 部分唯一索引（已有）+ Registrar 防御逻辑 |
| Provider 缺失的资源类型 → 告警跳过 | 规格 Edge Case |
| 单条配置构建失败 → 记录并跳过，不部分注册 | FR-006 / FR-010 |
| 无效字符规范化防 Bean 名冲突 | 规格 Edge Case / R2 |

## 状态转换

本期无运行时状态机：配置记录的生效路径为"DB 记录（enabled/deleted/is_primary）→ 启动期一次性物化为运行时 Bean"。变更配置需重启（规格 Assumptions）。
