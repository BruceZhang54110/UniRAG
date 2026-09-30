# Contract: 向量表保证语义（Embedding Store Guarantees）

**Feature**: PGVector 向量存储落地 | **Date**: 2026-09-27 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

面向后续 feature（文档摄取、检索问答）与运维的保证条款。调用方按本契约依赖向量存储行为。

## 1. 资源配置 Schema（不变）

EMBEDDING_STORE 资源配置 JSON 与 003 完全一致，见 [003 contracts/resource-config-schemas.md](../../003-multi-tenant-rag-foundation/contracts/resource-config-schemas.md)（host/port/database/user/password/table/dimension/create-table）。本期不新增、不修改字段。

## 2. 就绪保证（加载期完成后成立）

| # | 保证 | 违反时的行为 |
|---|---|---|
| G1 | 通过 `TenantResourceRouter` 获取的 EMBEDDING_STORE Bean 一定可写入/检索 | 任何构建期未决问题（连不通、表不存在、维度不匹配、表名非法、冲突）都在加载期暴露为故障隔离记录，**不产生"半就绪"Bean**（FR-010） |
| G2 | 不同租户绝不同表 | 同库同表被第二个租户配置时，后者加载失败（ERROR 日志含双方租户与表名） |
| G3 | 表结构与配置一致 | 加载期校验 `embedding` 列维度 = 配置 `dimension`；已存在表被复用而非重建，数据不受影响 |
| G4 | `create-table:false` 时表必已存在 | 否则该资源加载失败 |

## 3. 写入/检索语义

- 检索排序：余弦相似度降序（score = (2 − 余弦距离)/2，∈ [−1, 1]），受 `maxResults` 与 `minScore` 约束。
- 默认纯向量检索（SearchMode.VECTOR）；混合检索（HYBRID）与 IVFFlat 索引不在本期启用。
- 元数据为单列 JSON（COMBINED_JSON），按 key 过滤可用 langchain4j `Filter` API。

## 4. 运维约束

- 日志与 health details 只含 tenantCode/type/key/表名/原因，**不含** password/api-key（FR-008 红线）。
- 扩展（vector）由迁移 V3 管理；应用运行账号无需扩展创建权限。
- 逐租户向量表为动态表：不做 Flyway 管理、不做业务约定外的列扩展；运维直接操作表数据属绕过契约，后果自负。

## 5. 多表租户（粗细粒度等场景的前置保证）

同一租户可配置任意多条 EMBEDDING_STORE 资源（不同 key、不同表、不同维度、可指向不同库），彼此独立加载、独立路由、独立健康明细——005 的粗/细粒度双切分可直接以"两个 key 的两条资源配置"落地，无需基座变更（spec US2 边界情况）。
