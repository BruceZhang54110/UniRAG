# Data Model: PGVector 向量存储落地

**Feature**: PGVector 向量存储落地 | **Date**: 2026-09-27 | **Spec**: [spec.md](./spec.md)

## 实体关系总览

```text
Tenant (003 已有)
  └── TenantResourceConfig (003 已有, resource_type = EMBEDDING_STORE)  1 ── 1
        └── 向量表（本期落地的物理载体，动态创建/复用）
              └── 向量记录 1 ── N
```

## 向量表（Embedding Table）

每租户每条 EMBEDDING_STORE 资源配置对应一张物理表。表不由 Flyway 管理（数量与命名随租户配置动态变化），由 `PgVectorEmbeddingStore` 在资源加载期创建或复用。

**固定列结构**（langchain4j-pgvector 1.20.0-beta30 `createTable` DDL，本期不扩展）：

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| embedding_id | UUID | PRIMARY KEY | 记录唯一标识（写入时生成） |
| embedding | vector(N) | 维度 N = 配置 `dimension` | 向量数据；`atttypmod` 即维度（R2 实测） |
| text | TEXT | NULL | 关联原文 |
| metadata | JSON | NULL | 元数据（COMBINED_JSON 单列默认模式） |

**校验规则**：
- 表名必须匹配 `^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)?$`（可选 schema 限定；不通过 → 资源构建失败，R3）
- 同库同表全系统仅允许一个租户资源占用；冲突键 = `host:port:database:schema.table`（小写）。冲突时后加载者构建失败（R4）
- 表存在时 `embedding` 列维度必须等于配置 `dimension`（R2）；不一致 → 资源构建失败
- `create-table:false` 且表不存在 → 资源构建失败（FR-005 场景 4）

**命名约定**：沿用 003 契约建议"表名体现租户归属"（如 `embeddings_<tenantCode>_<用途>`）；配置可指向其他 PG 实例（host/port/database 独立），语义不变。

## 向量记录（Embedding Entry）

向量表中的一行。本期不引入业务实体表，记录结构即上表四列。写入/检索仅发生在集成测试中（构造确定性向量验证隔离），业务化写入（文档摄取）属 005。

## 维度校验元数据查询（R2）

加载期一次性查询（Provider 持有配置的连接参数，独立短连接）：

```sql
SELECT a.atttypmod
FROM pg_attribute a
JOIN pg_class c ON a.attrelid = c.oid
JOIN pg_namespace n ON c.relnamespace = n.oid
WHERE n.nspname = :schema AND c.relname = :table
  AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped;
```

- 返回空 → 表不存在
- 返回 N ≠ 配置 dimension → 维度不匹配
- schema 缺省（表名无 `schema.` 前缀）→ 按 `public` 处理

## 迁移（Flyway）

| 迁移 | 内容 | 幂等性 |
|---|---|---|
| V3__pgvector_extension.sql | `CREATE EXTENSION IF NOT EXISTS vector;` | 是（IF NOT EXISTS） |

失败语义：迁移账号无权限 → 启动失败，输出含失败语句的可读错误；人工预备步骤见 quickstart（R7）。V3 不建任何向量表（动态表归 Provider/store）。

## 与 003 模型的关系

- `tenant_resource_config`（EMBEDDING_STORE 类型的 `config` JSONB）Schema **不变**（host/port/database/user/password/table/dimension/create-table，见 003 contracts/resource-config-schemas.md）。
- `TenantResourceIndex` / Bean 命名 / Router 语义不变；本期仅 Provider 构建逻辑增强 + Registrar 新增冲突键去重。
- 健康检查明细结构不变（003 `TenantResourceHealthIndicator`），向量资源加载失败自动出现在 failures 明细中。
