# Quickstart: PGVector 向量存储落地

**Feature**: PGVector 向量存储落地 | **Date**: 2026-09-27

验证本 feature 三个用户故事的可执行指南。详细设计见 [plan.md](./plan.md) / [data-model.md](./data-model.md) / [contracts/](./contracts/embedding-store-guarantees.md)。

## 前置条件

- JDK 21、Maven（`./mvnw`）
- 本地 PostgreSQL 运行中（Postgres.app 或 docker），库 `uni_rag_db` 存在
- **数据库超级用户权限已执行过**（一次性，迁移 V3 也可自动完成但要求迁移账号有权限）：
  ```bash
  psql -h localhost -U postgres -d uni_rag_db -c "CREATE EXTENSION IF NOT EXISTS vector;"
  ```
- 环境变量：`PGPASSWORD`（application.yml 数据源密码占位符所需）

## 场景 1：自动化测试（US1 + US2 + US3，真实库 `./mvnw test`）

```bash
export PGPASSWORD=StrongPass123!
./mvnw test
```

预期全部通过（14 个 003 用例保持绿色 + 本期新增）：

- `PgVectorEmbeddingStoreIT`（US2）：两个租户各配置一张向量表（如 `embeddings_acmeUS4`、`embeddings_globexUS4`，维度 1024，create-table 默认 true），`loadAndRegister()` 后经 Router 取 Bean：各写入 2 条确定性向量（含区分性文本），各自检索——断言命中本方数据、检索对方内容返回空；两表物理隔离。
- `EmbeddingStoreCollisionIT`（US3）：两租户配置**同名同库**向量表，后加载者 Bean 不存在、ERROR 日志含双方租户与表名、先加载者正常。
- `EmbeddingStoreDimensionMismatchIT`（US3）：预先用 SQL 建 `vector(768)` 表，配置 dimension=1024 → 该资源加载失败并记录"实际维度 768 / 配置 1024"，其余资源正常。
- `HealthEndpointTest`（既有）：`/actuator/health` 返回 UP，details 中向量资源加载明细与插入数据一致且不含密码。

## 场景 2：手工端到端复核（新库零手工 DDL，US1）

```bash
# 造一个全新空库（验证迁移自举；迁移账号需有建扩展权限）
createdb -h localhost -U postgres uni_rag_fresh
export PGPASSWORD=... SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/uni_rag_fresh
./mvnw spring-boot:run
```

预期：

1. 启动日志中 Flyway 执行 V1/V2/V3，V3 启用 vector 扩展，无报错；
2. 空配置启动成功（无任何向量资源注册），`/actuator/health` UP；
3. 按 003 quickstart 插入 acme/globex 数据（含 EMBEDDING_STORE 配置）后重启：注册日志 `Registered tenant resource: embeddingStore_...`，失败条目 ERROR 含 tenant/type/key/原因。

**权限失败路径（FR-002）**：用无超级权限的账号执行迁移 → 启动失败，错误输出含 `CREATE EXTENSION` 语句与权限原因；按前置条件用超级用户执行一次后即可。

## 场景 3：双粒度多表租户（US2 扩展确认）

同一租户插入两条 EMBEDDING_STORE 资源配置（key=`coarse`/`fine`，表 `embeddings_t1_coarse`/`embeddings_t1_fine`，可配不同维度如 1024/768）：

- 启动后两个 Bean（`embeddingStore_<tenant>_coarse` / `_fine`）均注册成功；
- 两表独立写入/检索互不干扰；
- 该场景即 005 粗细粒度双切分的落地形态（见 [contracts §5](./contracts/embedding-store-guarantees.md)）。
