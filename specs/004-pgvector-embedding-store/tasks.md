---
description: "Task list for feature implementation: PGVector 向量存储落地"
---

# Tasks: PGVector 向量存储落地

**Input**: Design documents from `/specs/004-pgvector-embedding-store/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**Tests**: 包含测试任务。依据：spec FR-009（全部行为可在 `./mvnw test` 以真实 PG 验证、无模型凭据）；沿用 003 约定——测试先行（先写、确认失败、再实现）、真实本地开发库 `uni_rag_db` + 每测试类全局唯一租户码。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）
- 所有任务均含确切文件路径

## Path Conventions

单项目结构：`src/main/java/com/unirag/`、`src/test/java/com/unirag/`（与 plan.md 一致）

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 向量扩展迁移落地，解锁 US1

- [x] T001 新建迁移 `src/main/resources/db/migration/postgresql/V3__pgvector_extension.sql`：仅一条幂等语句 `CREATE EXTENSION IF NOT EXISTS vector;`（research.md R1/R7；Flyway 只管扩展，逐租户动态表不归迁移）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 冲突检测机制（Registrar/Provider SPI）与向量表校验器——US2/US3 的全部实现工作集中于此

**⚠️ CRITICAL**: 本阶段完成前 US2/US3 的验收场景无法通过（US1 仅依赖 T001）

- [x] T002 [P] 修改 `src/main/java/com/unirag/tenant/resource/TenantResourceProvider.java`：新增 `default Optional<String> collisionKey(TenantResourceConfig config)`（默认 `Optional.empty()` = 不参与去重），Javadoc 固化语义——冲突键相同的配置在单次加载中仅先加载者可构建（R4）；不改其余方法
- [x] T003 [P] 修改 `src/main/java/com/unirag/tenant/resource/TenantResourceRegistrar.java`：加载循环中 `build` 之前取 `provider.collisionKey(config)`，键已在本次加载集合中 → 记 ERROR（含双方 tenantCode/type/resourceKey 与冲突键，**不含密码等配置原文**，FR-008）并 `index.failed(config, reason)` 跳过（先加载者胜，加载顺序 = 记录 id 升序，确定性，R4）；无键/空键不参与去重。**验收：003 全部既有 IT（含 RegistrarIT/RouterIT/FailureIT）保持绿色零回退**——默认实现不改变既有行为
- [x] T004 [P] 新建 `src/main/java/com/unirag/tenant/resource/provider/EmbeddingStoreTableVerifier.java`：一次性 JDBC 短连接（DriverManager + 配置中的 host/port/database/user/password）元数据校验——① 表名白名单 `^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)?$`，不匹配抛异常（信息含表名，不回显完整配置，R3）；② 按 data-model.md 的 `pg_attribute.atttypmod` SQL 查 `embedding` 列（schema 缺省按 public）；③ 表不存在且 `create-table:false` → 抛异常（FR-005 场景 4）；④ 维度与配置 `dimension` 不一致 → 抛异常（信息含表名/实际维度/配置维度，R2）；校验通过静默返回
- [x] T005 修改 `src/main/java/com/unirag/tenant/resource/provider/EmbeddingStoreResourceProvider.java`：builder 增加 `.skipCreateVectorExtension(true)`（扩展创建归 Flyway，R1）；`build()` 在 store 构建后调用 T004 的 Verifier 完成存在性/维度校验（表名白名单可由 Verifier 第①步承担，Provider 不重复实现）；JSON 配置 Schema 不变（contracts §1）

**Checkpoint**: 冲突键机制就位且 003 回归全绿；Provider 构建路径具备表名/存在性/维度三重校验

---

## Phase 3: User Story 1 - 应用启动即具备向量存储能力 (Priority: P1) 🎯 MVP

**Goal**: V3 迁移在任何环境幂等启用 pgvector 扩展；无权限时失败可读；空配置启动成功（spec US1，FR-001/002/008）

**Independent Test**: `PgVectorMigrationIT`（真实库）断言扩展就绪 + 迁移幂等；空配置启动由每测试类上下文天然覆盖

### Tests for User Story 1 ⚠️（先写，确认失败后再实现）

- [x] T006 [US1] 新建 `src/test/java/com/unirag/tenant/resource/PgVectorMigrationIT.java`（继承 `AbstractPostgresIntegrationTest`，租户码约定本类无需插业务数据）：① 上下文启动后查询 `pg_extension` 断言 `vector` 扩展存在；② 注入 Flyway 再执行一次 `migrate()` 断言无 pending 迁移、无异常（幂等，验收场景 2）；③ `health` 端点 UP（空配置，验收场景 4）

### Implementation for User Story 1

-（无独立实现任务——迁移在 T001 已落地，本故事由 T001 + T006 构成）

**Checkpoint**: 全新/重复环境迁移幂等通过，空配置启动成功（quickstart 场景 1 + 2 的自动化部分）

---

## Phase 4: User Story 2 - 各租户向量存储按配置就绪且读写可用 (Priority: P2)

**Goal**: 双租户向量表加载后可写入/检索、跨租户 100% 隔离、建表/复用/禁建三态正确（spec US2，FR-003/005，contracts G1/G3/G4）

**Independent Test**: `PgVectorEmbeddingStoreIT`（真实库 + pgvector）经 Router 取 Bean 做确定性向量写入/检索

### Tests for User Story 2 ⚠️

- [x] T007 [P] [US2] 新建 `src/test/java/com/unirag/tenant/resource/PgVectorEmbeddingStoreIT.java`（继承基类，租户码 `acmeUS4`/`globexUS4`）：插入两租户 EMBEDDING_STORE 配置（表 `embeddings_acmeUS4`/`embeddings_globexUS4`，dimension 1024，create-table 默认 true）→ `loadAndRegister()` → 验收场景 1：经 `TenantResourceRouter` 取两 Bean 各自 `add` 两条确定性向量（`Embedding.from(float[])` + 含本方租户码的 `TextSegment` 文本）→ `search`（`EmbeddingSearchRequest`，maxResults=10）断言本方命中且 text 属本方、以对方内容构造的 query 检索返回空列表；验收场景 2/3：`noautoUS4` 租户 create-table=true 重复 `loadAndRegister()`（幂等重载不重建）+ 预先用 `JdbcTemplate` 建同维度表 `embeddings_reuseUS4`（create-table=true）加载后旧数据仍在、表未被重建；验收场景 4：`nocreateUS4` 租户 create-table=false 且表不存在 → 该 Bean 不存在、其余 Bean 正常、失败原因含"表不存在"
- [x] T008 [P] [US2] 新建 `src/test/java/com/unirag/tenant/resource/EmbeddingStoreHealthIT.java`（继承基类，租户码 `healthUS4`）：一合法 + 一非法（复用 nocreateUS4 场景或独立租户码）配置加载后，断言 `/actuator/health` details 中 `failures` 明细含 tenantCode/type/resourceKey/reason 且不含密码（FR-006/007，捕获方式沿用 003 FailureIT 的 OutputCaptureExtension 约定）

### Implementation for User Story 2

-（实现已在 Foundational T002–T005 完成；本故事验收驱动其正确性）

**Checkpoint**: 双租户写入/检索隔离通过；建表/复用/禁建三态与 health 明细验证完成（quickstart 场景 1 的 US2 部分 + 场景 3）

---

## Phase 5: User Story 3 - 向量存储相关的配置防护与运维可见性 (Priority: P3)

**Goal**: 跨租户同名表检出并隔离、非法表名/维度检出、失败可定位（spec US3，FR-004，contracts G2）

**Independent Test**: `EmbeddingStoreCollisionIT` / `EmbeddingStoreDimensionMismatchIT`（真实库）

### Tests for User Story 3 ⚠️

- [x] T009 [P] [US3] 新建 `src/test/java/com/unirag/tenant/resource/EmbeddingStoreCollisionIT.java`（继承基类，租户码 `colAUS4`/`colBUS4`/`invalUS4`）：① 前两者配置**同名同库**表 `embeddings_collision_us4`（不同 resource key）→ 断言先加载者（id 小）Bean 存在、后加载者 Bean 不存在；`OutputCaptureExtension` 捕获日志含两租户 code、表名与冲突说明；health details failures 含后者（验收场景 1）；② `invalUS4` 配置非法表名（如 `embeddings-bad` 或 `x;drop`）→ 构建失败、日志含表名但**不含** config 原文中的 password（R3/FR-008）
- [x] T010 [P] [US3] 新建 `src/test/java/com/unirag/tenant/resource/EmbeddingStoreDimensionMismatchIT.java`（继承基类，租户码 `dimUS4`/`goodUS4`）：`JdbcTemplate` 预建 `embeddings_dimUS4` 为 `vector(768)`，配置 dimension=1024 → 加载失败、失败原因含实际维度 768 与配置维度 1024、`dimUS4` Bean 不存在、`goodUS4` 全部正常（验收场景 2 + SC-005 定位要求）

### Implementation for User Story 3

-（实现已在 Foundational T002–T005 完成；本故事验收驱动其正确性）

**Checkpoint**: 同名表/非法表名/维度不匹配全部加载期检出并隔离（quickstart 场景 1 的 US3 部分）

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 端到端验证与收尾

- [x] T011 全量 `./mvnw test`：17+ 用例全绿、003 既有 IT 零回退；`grep -rn "password\|api-key" target/surefire-reports` 抽查测试输出无凭据泄漏（FR-008）
- [x] T012 [P] 按 `quickstart.md` 场景 2 做手工端到端复核（全新库 uni_rag_fresh 自举启动 + 空配置 health UP + 权限失败路径可读报错），与自动化 IT 互为印证
- [x] T013 更新 `README.md`：简述向量存储用法（EMBEDDING_STORE 配置示例 + Router 获取 + contracts G1–G4 链接 specs/004 文档）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup（T005 运行时需 pgvector 扩展已就绪，测试库已装）；**阻塞 US2/US3**。T002 ∥ T003 ∥ T004（不同文件），T005 依赖 T004
- **User Stories (Phase 3-5)**: US1 仅依赖 T001；US2/US3 依赖 Foundational 完成
- **Polish (Phase 6)**: 依赖 US1–US3 完成

### User Story Dependencies

- **US1 (P1)**: 仅依赖 T001 → **MVP**，可独立先行交付
- **US2 (P2)**: 依赖 T002–T005（Provider 校验逻辑）；测试 T007 可与 T008 并行起草
- **US3 (P3)**: 依赖 T002–T005（collisionKey 机制 + 校验器）；测试 T009 可与 T010 并行起草

### Within Each User Story

- 测试先行（T006/T007/T008/T009/T010 先于验收的实现调整并确认失败）
- 本 feature 的实现工作集中于 Phase 1–2；US2/US3 为纯验收驱动

### Parallel Opportunities

- T001 ∥ T002 ∥ T003 ∥ T004（不同文件；T005 在 T004 后）
- T006（US1 测试）∥ T002–T005（Foundational 实现）
- T007 ∥ T008（US2 测试，不同文件）∥ T009 ∥ T010（US3 测试，不同文件/不同租户码，互不干扰）

---

## Parallel Example: Foundational + US2/US3 测试

```bash
# Foundational 批:
Task: "T002 Provider.collisionKey 默认方法"
Task: "T003 Registrar 冲突键去重"
Task: "T004 EmbeddingStoreTableVerifier"

# 验收测试批（T005 完成后运行）:
Task: "T007 [US2] PgVectorEmbeddingStoreIT 双租户读写隔离"
Task: "T009 [US3] EmbeddingStoreCollisionIT 同名表检出"
Task: "T010 [US3] EmbeddingStoreDimensionMismatchIT 维度不匹配检出"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001（V3 迁移）→ T006（迁移 IT）→ **STOP and VALIDATE**：新库自举与幂等验证通过
2. 此后 Foundational + US2/US3 为连续增量

### Incremental Delivery

1. T001 → 扩展就绪（US1）
2. + T002–T005 → 校验与冲突机制就绪（Foundational）
3. + T007/T008 → 读写隔离闭环（US2）
4. + T009/T010 → 防护闭环（US3）
5. Polish → 手工复核 + 文档

### Parallel Team Strategy

- 开发者 A: T001 → T006（US1 线）
- 开发者 B: T002 → T003（冲突机制线）
- 开发者 C: T004 → T005（校验器线）→ T007–T010（验收测试）

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 验证标准：每个 Checkpoint 处 `./mvnw test` 全绿（前置：本地 PG 运行中且 `uni_rag_db` 已装 pgvector 扩展）
- 测试数据约定：复用开发库 `uni_rag_db` + 每测试类全局唯一租户码（`acmeUS4`/`globexUS4`/`nocreateUS4`/`reuseUS4`/`healthUS4`/`colAUS4`/`colBUS4`/`invalUS4`/`dimUS4`/`goodUS4`）；向量表用 `embeddings_<租户码小写>` 命名，避免跨类冲突
- 敏感信息红线：任何新增日志/health details 不得含 password/api-key（FR-008），T009 含专门断言
- T003 触碰 Registrar 核心流程，必须保持 003 全部 IT 绿色（collisionKey 默认 empty 不改变既有行为）；如有回退立即停止排查
- 003 降级约定演进（research.md R6）：`create-table:false` 语义变为"表必须已存在且维度匹配"；本地库已装 pgvector，测试一律走 create-table=true 完整链路
