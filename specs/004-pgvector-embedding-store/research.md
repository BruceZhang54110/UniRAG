# Research: PGVector 向量存储落地

**Feature**: PGVector 向量存储落地 | **Date**: 2026-09-27 | **Plan**: [plan.md](./plan.md)

本文件记录 Phase 0 研究决策。所有 NEEDS CLARIFICATION 已在本文档解决（技术上下文无遗留未知项；关键事实均来自 langchain4j-pgvector 1.20.0-beta30 源码与本地 pgvector 实测）。

## R1: pgvector 扩展创建的归属

**事实**：`PgVectorEmbeddingStore` 在每次获取连接时执行 `CREATE EXTENSION IF NOT EXISTS vector`（`getConnection()`，除非 `skipCreateVectorExtension(true)`）——即每个连接、每个向量存储实例都会尝试创建扩展。

**Decision**: Provider 构建时一律设 `skipCreateVectorExtension(true)`；扩展由 Flyway V3 迁移统一创建（`CREATE EXTENSION IF NOT EXISTS vector`，幂等）。

**Rationale**: ① 应用运行账号不应要求超级用户权限（spec 假设）；② 扩展创建单一职责归迁移，US1 可独立验收；③ 避免每连接重复执行系统级 DDL 的开销与权限耦合。

**Alternatives considered**: 依赖 store 自动创建——拒绝（每连接执行、要求应用账号高权限、与迁移职责重复）；完全人工创建、迁移不管——拒绝（违反 US1 零手工 DDL 目标）。

## R2: 建表语义与加载期维度校验

**事实**：`createTable=true` 时 store 执行
`CREATE TABLE IF NOT EXISTS <table> (embedding_id UUID PRIMARY KEY, embedding vector(N), text TEXT NULL, metadata JSON NULL)`
（metadata 为 COMBINED_JSON 单列默认）。表已存在时 `IF NOT EXISTS` 静默跳过——**不做任何维度校验**；维度不匹配要到写入时以 SQL 错误暴露。

**事实（本地实测）**：pgvector 列维度在系统目录中可直接查询：`pg_attribute.atttypmod` = 维度值（对 `vector(1024)` 列查询返回 1024）。

**Decision**: Provider 构建 store 后，用配置参数建立一次性 JDBC 连接，执行元数据校验：
- 表存在 → 读取 `embedding` 列 `atttypmod`，与配置 `dimension` 不一致 → 构建失败（故障隔离，日志含表名/配置维度/实际维度）
- 表不存在 → 仅当 `create-table:false` 时构建失败（FR-005 场景 4）；`create-table:true` 时不会发生（store 刚建表）

**Rationale**: 把"维度不一致"从运行期写入失败提前到加载期配置失败，满足 SC-005（1 分钟定位）与 spec 边界情况；查询为单次元数据读取，无热点开销。

**Alternatives considered**: 写入期自然失败——拒绝（运维定位成本高，违反 FR-005/SC-005 意图）；Flyway 管理逐租户表——拒绝（动态表、数量随租户配置变化，违反"Flyway 只管理扩展与基座结构"的假设）。

## R3: 表名安全（SQL 注入面）

**事实**：`table` 参数以 `String.format` 原样拼接进 DDL 与全部查询（`PgVectorEmbeddingStore` 无转义/白名单）。

**Decision**: Provider 校验表名格式，白名单正则 `^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)?$`（可选 schema 限定，如 `public.embeddings_acme`）；不匹配 → 构建失败，错误信息指明表名非法但**不回显完整配置**（FR-008）。

**Rationale**: 配置来自数据库，属受信但可错的数据；白名单同时解决注入面与非法标识符两类风险，且报错可读。

## R4: 跨租户同名表冲突检测

**事实**：Registrar 按记录 id 升序逐条加载（003 语义），天然确定性"先加载者胜"。

**Decision**:
1. `TenantResourceProvider` 新增 `default Optional<String> collisionKey(TenantResourceConfig config)`（默认空 = 不参与去重）；
2. Registrar 维护本次加载已见冲突键集合：键已存在 → 记 ERROR（含双方 tenantCode/type/key 与冲突键）并按故障隔离跳过该条，不构建不注册；
3. `EmbeddingStoreResourceProvider` 实现：冲突键 = `host:port:database:schema.table`（全小写规范化）；配置不足以构成键（字段缺失）→ 返回 empty（构建阶段仍会按必填校验失败）。

**Rationale**: 冲突语义是类型相关的（向量表的"同"=同库同表），但去重机制是类型无关的——与 003 "Provider SPI + Registrar 通用流程"的架构一致，未来其他类型可复用。

**Alternatives considered**: 数据库层唯一约束——拒绝（冲突可跨实例/跨库，DB 约束覆盖不到）；事后巡检——拒绝（不是加载期隔离，违反 FR-004）。

## R5: 写入/检索语义（集成验证依据）

**事实**（源码）：检索使用余弦距离 `embedding <=> query`，返回 score = (2 − dist) / 2（即余弦相似度，∈ [−1, 1]），`ORDER BY embedding <=> query LIMIT maxResults`，`minScore` 以相似度阈值过滤；默认 `SearchMode.VECTOR`。

**Decision**: 集成测试用 `Embedding.from(float[])` 构造确定性向量 + `TextSegment` 区分性文本：写入本方数据后检索，断言命中本方、查不到对方租户内容（score 阈值默认取 0，或按构造向量精确计算期望值）；无需任何外部模型凭据（FR-009）。

**事实/Decision**: IVFFlat 索引（`useIndex`）默认关闭——空表上建 IVFFlat 无意义（lists 划分依赖数据量）；索引调优属运维/后续 feature，本期保持默认。

## R6: 003 降级约定的演进（兼容性说明）

**事实**：003 约定"未装 pgvector 的库用 `create-table:false`，构建期不建表即可断言 Bean 创建"。004 后该校验使语义变为："`create-table:false` ⇒ 表必须已存在且维度匹配，否则该条构建失败"。

**Decision**: 本地开发/测试库已装 pgvector（quickstart 前置），004 集成测试一律走 `create-table:true` 完整链路；003 既有 IT 的 EMBEDDING_STORE 用例（`embeddings_acmeUS1`，create-table 默认 true）继续通过。维度校验对已存在且维度一致的表无副作用。

**Rationale**: 更严格的语义正是 FR-005 场景 4 的要求；不破坏 003 任何既有用例。

## R7: 迁移失败的可读性（FR-002）

**事实**：Flyway 迁移失败默认输出含失败语句与根因（权限/不存在等）的错误。

**Decision**: V3 仅含 `CREATE EXTENSION IF NOT EXISTS vector;`；失败时 Flyway 报错本身可读。人工预备步骤（以超级用户执行该语句）写入 quickstart 前置条件。不额外包装错误处理代码（YAGNI——失败即启动失败，信息已足够定位）。

## 遗留未知项

无。所有 NEEDS CLARIFICATION 已解决。
