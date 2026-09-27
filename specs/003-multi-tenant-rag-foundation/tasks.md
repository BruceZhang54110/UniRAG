---

description: "Task list for feature implementation: 多租户 RAG 资源基座"
---

# Tasks: 多租户 RAG 资源基座

**Input**: Design documents from `/specs/003-multi-tenant-rag-foundation/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**Tests**: 包含测试任务。依据：`quickstart.md` 场景 1 明确要求 `TenantResourceRegistrarTest` / `TenantResourceRouterTest` 通过；**用户要求单元测试真实连接数据库**（research.md R12 修订），采用 Testcontainers + 真实 PG，JSONB 映射/联表 SQL/Flyway 均在 `./mvnw test` 内验证。测试先行（先写、确认失败、再实现）。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）
- 所有任务均含确切文件路径

## Path Conventions

单项目结构：`src/main/java/com/unirag/`、`src/test/java/com/unirag/`（与 plan.md 一致）

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 存量修正、测试基础设施（Testcontainers 真实 DB），解锁后续所有工作

- [ ] T001 修正 `src/main/resources/application.yml`：`mybatis-plus.type-aliases-package` 改为 `com.unirag.infrastructure.mp.entity`；`spring.datasource.password` 改为 `${PGPASSWORD:StrongPass123!}` 占位符形式；新增 `unirag.ai.models.default.api-key: ${OPENAI_API_KEY:sk-placeholder}` 结构，使既有 `src/test/java/com/unirag/ConfigHygieneTest.java` 通过（R13）
- [ ] T002 [P] 修正 mapper XML namespace 拼写错误（`infratrccture.mapper` → `infrastructure.mp.mapper`）：`src/main/resources/mapper/TenantResourceConfigMapper.xml` 与 `src/main/resources/mapper/TenantMapper.xml`（R13）
- [ ] T003 接入真实数据库测试基础设施：删除 `src/test/resources/application.yml` 中 `DataSourceAutoConfiguration` 的排除（测试上下文即复用主配置指向的本地开发库 `uni_rag_db`，R12）；新建 `src/test/java/com/unirag/AbstractPostgresIntegrationTest.java`——仅承担测试约定基类职责：Javadoc 固化**每个测试类使用全局唯一租户码**（如 `acmeUS1`、`globexUS1`）防 Bean 名冲突、提供可选 `@Sql` 类后清理模板；EMBEDDING_STORE 测试配置在未装 pgvector 扩展时统一 `"create-table": false`（构建期不建表）。pom.xml **不新增任何测试依赖**

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 领域对象、持久化映射、查询与索引——所有用户故事的前置

**⚠️ CRITICAL**: 本阶段完成前不可开始任何用户故事

- [ ] T004 升级持久化实体 `src/main/java/com/unirag/infrastructure/mp/entity/TenantResourceConfig.java`：字段改 camelCase + `@TableField` 显式列映射；类注解 `@TableName(value = "tenant_resource_config", autoResultMap = true)`；`config` 字段类型改 `JsonNode` 并加 `@TableField(typeHandler = JacksonTypeHandler.class)`（R7）
- [ ] T005 [P] 补全领域对象 `src/main/java/com/unirag/tenant/resource/TenantResourceConfig.java`：Lombok `@Value`（或 final + 构造器）访问器，字段 `id/tenantCode/resourceType/resourceKey/primary/config(JsonNode)/enabled`，与 data-model.md 一致（R9）
- [ ] T006 [P] 新建三个基础类型：`src/main/java/com/unirag/tenant/resource/ResourceType.java` 增加 `beanPrefix()`（LLM→llm、EMBEDDING_MODEL→embeddingModel、EMBEDDING_STORE→embeddingStore、RERANK_MODEL→rerankModel）；`src/main/java/com/unirag/tenant/resource/TenantResourceKey.java`（record/值对象，全字段 equals/hashCode）；`src/main/java/com/unirag/tenant/resource/TenantResourceNotFoundException.java`（message 模板见 contracts/tenant-resource-router.md）
- [ ] T007 [P] 新建不可变注册索引 `src/main/java/com/unirag/tenant/resource/TenantResourceIndex.java`：`Map<TenantResourceKey,String> beanNames`、`Map<(tenantCode,ResourceType), NavigableMap<Long,TenantResourceKey>> primaries`、`Set<(tenantCode,ResourceType)>` registeredPairs；不可变快照方法；Bean 命名工具 `beanNameOf(type, tenantCode, key)`（非法字符 `[^a-zA-Z0-9_-]`→`_`，R2）
- [ ] T008 实现查询链路：`src/main/resources/mapper/TenantResourceConfigMapper.xml` 增加 `findAllEnabled` 联表 SQL（`tenant_resource_config deleted=false AND enabled=true` 且 `EXISTS` 关联 `tenant deleted=false AND enabled=true`，`ORDER BY tenant_resource_config.id ASC`）；`src/main/java/com/unirag/infrastructure/mp/mapper/TenantResourceConfigMapper.java` 声明方法；`src/main/java/com/unirag/infrastructure/mp/service/impl/TenantResourceConfigServiceImpl.java` 实现 `findAllEnabled()` 并将持久化实体转换为领域 `TenantResourceConfig`（含 tenantCode 联表带出）（R6/R9）。验收：在 Testcontainers 基类上写一个查询冒烟用例（插两租户+停用租户+停用配置，断言只返回启用数据且 tenantCode 正确、按 id 升序）

**Checkpoint**: 领域/持久化对象、命名规则、查询、索引骨架就绪，真实 DB 冒烟通过

---

## Phase 3: User Story 1 - 应用启动时按租户加载各自的模型资源 (Priority: P1) 🎯 MVP

**Goal**: 启动期读取全部启用租户的资源配置，经 Provider SPI 创建实例并以 `{type}_{tenantCode}_{key}` 命名注册为 Spring Bean（spec US1，FR-001/002/003/005/007/010）

**Independent Test**: `TenantResourceRegistrarIT`（继承 AbstractPostgresIntegrationTest，真实 PG）验证 US1 全部三个验收场景；空载启动由每测试类天然覆盖（上下文随空库启动成功）

### Tests for User Story 1 ⚠️（先写，确认失败后再实现）

- [ ] T009 [P] [US1] 新建 `src/test/java/com/unirag/tenant/resource/TenantResourceRegistrarIT.java`（继承 `AbstractPostgresIntegrationTest`）：用 `JdbcTemplate` 插入两租户（`acmeUS1`/`globexUS1`）各 LLM+EMBEDDING_MODEL 配置（JSON 含区分性参数，如不同 `model-name`），调用 `tenantResourceRegistrar.loadAndRegister()`（R14），断言——场景 1：容器中存在 `llm_acmeUS1_default`、`llm_globexUS1_default` 等 4 个 Bean 且经 Provider 解析出的参数各自生效（如 `OpenAiChatModel` 的 modelName 可通过反射或 getter 断言）；场景 3：仅插入 LLM 的租户其 EMBEDDING_STORE Bean 不存在且不报错；场景 2：未插入数据的租户码查询无 Bean。Provider 构建不得发起真实网络调用（langchain4j builder 仅本地构造；EMBEDDING_STORE 用例配置 `"create-table": false`，R12）

### Implementation for User Story 1

- [ ] T010 [P] [US1] 新建三个 Provider（`src/main/java/com/unirag/tenant/resource/provider/`）：`LlmResourceProvider.java`（JSON→`OpenAiChatModel`，schema 见 contracts/resource-config-schemas.md，必填缺失抛 `IllegalArgumentException`）；`EmbeddingModelResourceProvider.java`（→`OpenAiEmbeddingModel`）；`EmbeddingStoreResourceProvider.java`（→`PgVectorEmbeddingStore`，builder 字段与既有 `EmbeddingStoreConfig` 注释代码一致）；均 `@Component`，实现 `TenantResourceProvider<T>`，不打印 config 原文（FR-008）
- [ ] T011 [US1] 完成 `src/main/java/com/unirag/tenant/resource/TenantResourceRegistrar.java`（R1/R4/R6/R14）：核心逻辑抽为 public 幂等 `loadAndRegister()`（重复调用跳过已注册 beanName 并 DEBUG 日志），`run()` 仅委托；流程＝`findAllEnabled()`→按 `resourceType` 匹配 Provider（无 Provider 记 WARN 跳过）→try 中先 `provider.build(config)` 成功后再 `applicationContext.registerBean(beanName, beanType, () -> instance)`→构建并发布 `TenantResourceIndex`；成功日志 `Registered tenant resource: <beanName>`，失败日志 ERROR 含 tenantCode/type/key/reason 且不含敏感配置（FR-006/008）；重复 primary 记 ERROR 取 id 最小者（FR-011 防御）
- [ ] T012 [US1] 新建 `src/main/java/com/unirag/tenant/resource/TenantResourceAutoConfiguration.java`：`@Configuration` `@Bean` 装配 Registrar（注入 `List<TenantResourceProvider<?>>`、`GenericApplicationContext`、`TenantResourceConfigService`）与 `TenantResourceIndex`→`TenantResourceRouter`（Router 类在 US2 实现，可先以接口/占位方式接线或留待 T015，保持编译通过）

**Checkpoint**: US1 独立可用——真实 DB 下 `./mvnw test` 的 US1 用例通过，注册日志正确

---

## Phase 4: User Story 2 - 按租户获取对应的模型资源 (Priority: P2)

**Goal**: 业务经 `TenantResourceRouter` 按"租户+类型+标识"或主资源获取实例；不存在抛专用异常（spec US2，FR-004/FR-011，contracts/tenant-resource-router.md）

**Independent Test**: `TenantResourceRouterIT`（真实 PG：插数→loadAndRegister→Router 断言）覆盖 get 命中/未命中异常、getPrimary 三分支；跨租户同 key 返回不同实例（SC-002）

### Tests for User Story 2 ⚠️

- [ ] T013 [P] [US2] 新建 `src/test/java/com/unirag/tenant/resource/TenantResourceRouterIT.java`（继承基类）：插入 `acmeUS2`（LLM default(primary)+backup、EMBEDDING_MODEL 两条无 primary）数据后 `loadAndRegister()`；断言契约表全部情形——get 命中返回同一单例、未命中抛 `TenantResourceNotFoundException` 且 message 含 tenant/type/key、getPrimary("acmeUS2", LLM) 返回 default、getPrimary(EMBEDDING_MODEL) 无 primary 且多条抛异常、另造仅单实例租户 `soloUS2` 验证唯一实例回退、跨租户同 key（`acmeUS2`/`globexUS2` 各插同名 LLM default）返回不同实例

### Implementation for User Story 2

- [ ] T014 [US2] 新建 `src/main/java/com/unirag/tenant/resource/TenantResourceRouter.java`：`@Component`，构造注入 `GenericApplicationContext` 与 `TenantResourceIndex`（Registrar 构建、AutoConfiguration 提供）；`get()` 索引未命中抛 `TenantResourceNotFoundException`，命中 `applicationContext.getBean(beanName, beanType)`；`getPrimary()` 按 data-model.md 语义表实现（含唯一实例回退）

**Checkpoint**: US1+US2 闭环——真实 DB 下加载、取回、异常语义全部通过

---

## Phase 5: User Story 3 - 单租户配置故障不影响其他租户 (Priority: P3)

**Goal**: 单条配置失败仅记录并跳过、不部分注册；健康检查暴露加载/失败明细（spec US3，FR-006/009/010，SC-003）

**Independent Test**: 故障场景真实 DB 测试——一条非法配置（缺必填字段）+ 其余合法，断言合法 Bean 全部可用、失败 Bean 不存在、ERROR 日志含定位信息且不含 api-key；`/actuator/health` details 含 loadedCount/failedCount/failures

### Tests for User Story 3 ⚠️

- [ ] T015 [P] [US3] 新建 `src/test/java/com/unirag/tenant/resource/TenantResourceRegistrarFailureIT.java`（继承基类，用 Spring Boot 自带 `OutputCaptureExtension` 捕获日志，不加新依赖）：插入 `badUS3` 租户——LLM default（合法）、EMBEDDING_MODEL broken（缺 `api-key`）、另租户 `goodUS3` 全合法；`loadAndRegister()` 后断言——合法 Bean 全部存在、broken Bean 不存在（FR-010 不部分注册）、捕获日志含 `badUS3`/`EMBEDDING_MODEL`/`broken`/原因且不含插入的 api-key 值（FR-006/008）；`TenantResourceHealthIndicator` 用例：details 中 loadedCount/failedCount/failures 明细与插入数据一致

### Implementation for User Story 3

- [ ] T016 [US3] 新建 `src/main/java/com/unirag/tenant/observability/TenantResourceHealthIndicator.java`：实现 `HealthIndicator`，details 输出 `{loadedCount, failedCount, failures:[{tenantCode, resourceType, resourceKey, error}]}`，status 恒 UP（R10）；失败明细由 Registrar 在 loadAndRegister 中收集并经 holder/索引暴露
- [ ] T017 [US3] 在 `TenantResourceAutoConfiguration` 注册 HealthIndicator Bean 并接线失败明细来源；运行 `./mvnw test` 全绿 + 既有 `HealthEndpointTest` 保持通过（不回退）

**Checkpoint**: 全部 US 完成；故障隔离与健康可见性在真实 DB 下达成（SC-003）

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 端到端验证与收尾

- [ ] T018 [P] 按 `quickstart.md` 场景 2 做手工端到端复核（docker pgvector + 插入 acme/globex 数据 + 启动观察日志），确认 5 成功/1 失败隔离、health details、空载启动——与自动化 IT 互为印证
- [ ] T019 全量 `./mvnw test`（含嵌入式 PG 集成套件）+ `quickstart.md` 场景 3 启动耗时对比（<10%）；更新 `README.md` 简述资源基座用法（Router 获取示例 + 配置表说明），链接 specs/003 文档

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始（T001/T002/T003 互相独立）
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有用户故事**。内部依赖：T004→T008（实体映射完成后写联表 SQL 与转换）；T005/T006/T007 互相独立可并行
- **User Stories (Phase 3-5)**: 均依赖 Foundational 完成
- **Polish (Phase 6)**: 依赖 US1-US3 完成

### User Story Dependencies

- **US1 (P1)**: Foundational 完成后即可开始，无其他故事依赖 → **MVP**
- **US2 (P2)**: 依赖 US1 的 Registrar 产物（`TenantResourceIndex`/`loadAndRegister`），紧随 US1
- **US3 (P3)**: 隔离逻辑内嵌 Registrar（T011）；测试 T015 可与 US2 并行起草

### Within Each User Story

- 测试先行（T009/T013/T015 先于对应实现并确认失败）
- 基础类型（T005-T007）→ Provider（T010）→ Registrar（T011）→ 装配（T012/T014/T017）
- 单故事完成即独立验证，再进入下一优先级

### Parallel Opportunities

- T001 ∥ T002 ∥ T003 ∥ T004 ∥ T005 ∥ T006 ∥ T007（Foundational 内部，仅 T004→T008 有序）
- T009（Registrar IT）∥ T010（Provider 实现）
- T013（Router IT）∥ T011（Registrar 实现）——IT 通过基类插数+loadAndRegister 驱动，不依赖 Registrar 内部细节之外的 API
- T015（故障隔离 IT）∥ T014（Router 实现）

---

## Parallel Example: Foundational + US1

```bash
# Foundational 批（除 T008 外可同一批次启动）:
Task: "T004 升级持久化实体 TenantResourceConfig.java"
Task: "T005 领域对象 TenantResourceConfig.java"
Task: "T006 ResourceType.beanPrefix + TenantResourceKey + 异常"
Task: "T007 TenantResourceIndex 不可变索引"

# US1 批:
Task: "T009 [US1] TenantResourceRegistrarIT 真实 DB 用例（先写，确认失败）"
Task: "T010 [US1] 三个 Provider 实现（互不依赖，可再拆并行）"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup（T001-T003，含 Testcontainers 基类）
2. 完成 Phase 2 Foundational（T004-T008）——**关键路径**，冒烟用例验证真实 DB 链路
3. 完成 Phase 3 US1（T009-T012）
4. **STOP and VALIDATE**: `./mvnw test` 中 US1 用例独立通过；观察注册日志
5. 此后每个故事为独立增量

### Incremental Delivery

1. Setup + Foundational → 真实 DB 基座骨架就绪（嵌入式 PG）
2. + US1 → 启动加载闭环（MVP）
3. + US2 → 业务可取资源（Router 闭环）
4. + US3 → 故障隔离 + 健康可见性
5. Polish → 手工端到端复核 + 文档

### Parallel Team Strategy

- 开发者 A: T004→T008（持久化链路 + 冒烟用例）
- 开发者 B: T005→T006→T007（领域/索引基础类型）→ T010（Provider）
- 开发者 C: T003（Testcontainers 基类）→ T009/T013/T015（测试先行）
- Foundational 合流后：A 做 T011/T012，B 做 T014/T016，C 做 T017/T018

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖；同文件任务（如 T015 与 T009 不同文件可并行，但均依赖 T003 基类）
- 验证标准：每个 Checkpoint 处 `./mvnw test` 全绿（前置：本地 PG 运行中）
- 测试数据约定（R12）：复用开发库 `uni_rag_db` + 每测试类全局唯一租户码（断言只针对本类租户码，测试数据留库无害可追溯；可选 `@Sql` 清理）
- 敏感信息红线：任何新增日志/health details 不得含 api-key/password（FR-008），T015 含专门断言
- T001 触碰的 `ConfigHygieneTest` 为既有失败用例，修复后应保持绿色不回退
