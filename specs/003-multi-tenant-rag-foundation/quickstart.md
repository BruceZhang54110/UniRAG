# Quickstart: 多租户 RAG 资源基座验证指南

**Feature**: `specs/003-multi-tenant-rag-foundation/` | **Date**: 2026-09-16

本指南提供可执行的端到端验证场景，证明规格中 User Story 1~3 与 FR/SC 成立。实现细节与任务分解见 `tasks.md`（Phase 2 产物）与契约文件。

## 前置条件

- JDK 21、Maven（`./mvnw`）
- Docker（用于启动 PostgreSQL）；或已有可用的 PG 实例
- 环境变量：`PGPASSWORD`（application.yml 中数据源密码占位符所需，见 R13）

## 场景 1：自动化测试（真实连接本地开发库，验证 P1/P2/P3 核心逻辑）

**前置**: 本地 PG 运行中（开发环境本就需要）。测试上下文直接复用主配置的数据源（`uni_rag_db`），Flyway 自动执行 V1/V2 迁移；JSONB 映射、联表查询、Bean 注册全部走真实链路。每个集成测试类使用全局唯一租户码，断言只针对本类数据。本地 PG 已安装 pgvector 扩展，EMBEDDING_STORE 用例走完整建表路径（未安装的库使用 `"create-table": false`，构建期不建表即可断言 Bean 创建）。

```bash
export PGPASSWORD=StrongPass123!
./mvnw test
```

预期结果：

- `TenantResourceRegistrarIT`：两租户隔离加载、Bean 命名与参数生效、缺失类型不阻塞、空载正常（US1）
- `TenantResourceRouterIT`：get 命中/未命中抛 `TenantResourceNotFoundException`、getPrimary 三分支、跨租户隔离（US2）
- `TenantResourceRegistrarFailureIT`：单条失败隔离、不部分注册、日志无 api-key 泄漏、health details 明细（US3）
- `HealthEndpointTest`（既有）：`/actuator/health` 返回 UP

## 场景 2：真实数据库端到端验证（验证 JSONB 读取 + Flyway + Bean 注册）

### 2.1 启动 PostgreSQL

```bash
docker run -d --name unirag-pg -e POSTGRES_DB=uni_rag_db \
  -e POSTGRES_USER=uni_rag_user -e POSTGRES_PASSWORD=StrongPass123! -p 5432:5432 pgvector/pgvector:pg16
```

### 2.2 插入两个租户与各自资源

```sql
INSERT INTO tenant (tenant_code, name) VALUES ('acme', 'Acme Corp'), ('globex', 'Globex');
-- acme：OpenAI 聊天模型 + OpenAI 嵌入模型 + PgVector 存储
INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config) VALUES
  ((SELECT id FROM tenant WHERE tenant_code='acme'), 'LLM', 'default', true,
   '{"base-url":"https://api.openai.com/v1","api-key":"'$OPENAI_API_KEY'","model-name":"gpt-4o-mini"}'),
  ((SELECT id FROM tenant WHERE tenant_code='acme'), 'EMBEDDING_MODEL', 'default', true,
   '{"base-url":"https://api.openai.com/v1","api-key":"'$OPENAI_API_KEY'","model-name":"text-embedding-3-small"}'),
  ((SELECT id FROM tenant WHERE tenant_code='acme'), 'EMBEDDING_STORE', 'default', true,
   '{"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user","password":"StrongPass123!","table":"embeddings_acme","dimension":1536}');
-- globex：第二条聊天模型（验证同类型多 key）+ 一条故意损坏的配置（验证故障隔离）
INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config) VALUES
  ((SELECT id FROM tenant WHERE tenant_code='globex'), 'LLM', 'default', true,
   '{"base-url":"https://api.openai.com/v1","api-key":"'$OPENAI_API_KEY'","model-name":"gpt-4o"}'),
  ((SELECT id FROM tenant WHERE tenant_code='globex'), 'LLM', 'backup', false,
   '{"base-url":"https://api.openai.com/v1","api-key":"'$OPENAI_API_KEY'","model-name":"gpt-4o-mini"}'),
  ((SELECT id FROM tenant WHERE tenant_code='globex'), 'EMBEDDING_MODEL', 'broken', true,
   '{"model-name":"text-embedding-3-small"}');
```

### 2.3 启动应用并观察日志

```bash
export PGPASSWORD=StrongPass123! OPENAI_API_KEY=sk-...
./mvnw spring-boot:run
```

预期日志（关键行）：

- 注册成功：`Registered tenant resource: embeddingModel_acme_default ...`（共 5 条成功：acme×3 + globex×2）
- 故障隔离：`Failed to create tenant resource: tenant=globex, type=EMBEDDING_MODEL, key=broken, reason=...`（1 条 ERROR），**且应用继续启动完成**

### 2.4 验证 Bean 与路由

```bash
curl -s localhost:8080/actuator/health | jq .
```

预期：`status=UP`，details 中 tenantResource 含 `loadedCount=5, failedCount=1`，failures[0] 指向 globex/EMBEDDING_MODEL/broken。

```bash
# 若实现附带了调试端点/测试探针，或通过单元测试上下文验证：
# router.get("acme", LLM, "default") ≠ router.get("globex", LLM, "default")
# router.getPrimary("globex", LLM) → 返回 default 实例
# router.get("acme", LLM, "missing") → TenantResourceNotFoundException
```

### 2.5 验证空载启动（US1 场景 2）

```sql
DELETE FROM tenant_resource_config; DELETE FROM tenant;
```
重启应用 → 正常启动，health UP，`loadedCount=0`。

## 场景 3：故障隔离计时（SC-001/SC-003 佐证）

对比"全部合法配置"与"混入 1 条非法配置"两次启动的耗时（应用日志 `Started UniRagApplication in X seconds`），差异应 < 10%；非法配置所在租户与资源可在 ERROR 日志中直接定位。
