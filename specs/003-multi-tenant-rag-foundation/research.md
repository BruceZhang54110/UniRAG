# Phase 0 Research: 多租户 RAG 资源基座

**Feature**: `specs/003-multi-tenant-rag-foundation/spec.md`
**Date**: 2026-09-16

本文件记录所有技术决策点。无遗留 NEEDS CLARIFICATION。

## R1. 插件 SPI 机制

- **Decision**: 采用 Spring Bean 自动收集机制——所有 `TenantResourceProvider<T>` 实现标注 `@Component`（或 `@Configuration` 内 `@Bean`）后，由 Spring 注入为 `List<TenantResourceProvider<?>>`，`TenantResourceRegistrar` 遍历该列表完成注册。这正是现有半成品 `TenantResourceRegistrar` 构造器的既定设计，也是 Spring 生态的惯用 SPI 模式。
- **Rationale**: Provider 自身常需注入其他 Bean（如 `ObjectMapper`、连接工厂），走 Spring 容器可获得完整 DI 生命周期；`ServiceLoader` 无法满足。
- **Alternatives considered**: `java.util.ServiceLoader`（拒绝：脱离 Spring 生命周期，无法 DI）；手工注册表Map（拒绝：违背自动发现，新增类型需改主流程，违反 SC-004）。

## R2. 资源实例的 Bean 命名

- **Decision**: `{resourceType}_{tenantCode}_{resourceKey}`，例如 `embeddingModel_acme_openai`。`ResourceType` 枚举增加 `beanPrefix()` 方法返回 camelCase 前缀（`EMBEDDING_MODEL → embeddingModel`、`EMBEDDING_STORE → embeddingStore`、`RERANK_MODEL → rerankModel`、`LLM → llm`）。tenantCode 与 resourceKey 中的非法字符（非 `[a-zA-Z0-9_-]`）统一替换为 `_`，防止 Bean 名冲突/非法（对应规格 Edge Case）。
- **Rationale**: 与目标架构图及 Clarification Q1 拍板一致；类型前缀使容器调试视图按类型分组。
- **Alternatives considered**: `tenant_{tenantId}_{type}_{key}`（拒绝：与 Q1 澄清结论冲突）；不透明 UUID（拒绝：排障困难）。

## R3. 租户标识字段

- **Decision**: Bean 命名与 `TenantResourceRouter` 的 `tenantId` 参数一律使用 `tenant.tenant_code`（对外业务标识，如 `acme`），而非主键 `tenant.id`。
- **Rationale**: 业务代码面向租户编码编程；DB 已对 `tenant_code` 建唯一索引（V2 迁移）；架构图中 `tenantA` 亦为编码形态。
- **Alternatives considered**: 数字主键（拒绝：业务不可读，跨环境不稳定）。

## R4. 动态 Bean 注册方式（FR-010 关键）

- **Decision**: `TenantResourceRegistrar` 在 `run()` 中**先同步调用 `provider.build(config)` 得到实例**，成功后再 `GenericApplicationContext.registerBean(beanName, beanType, () -> instance)` 注册为单例。创建失败的配置在 try/catch 中记录错误日志（tenant/type/key/原因）并跳过，绝不进入注册步骤。
- **Rationale**: `registerBean` 的 Supplier 默认延迟实例化，错误会推迟到首次 `getBean` 才暴露且难以隔离；先构建后注册保证"一条记录要么完整创建成功、要么完全不创建"（FR-010），且失败隔离发生在启动期（FR-006）。
- **Alternatives considered**: 直接传 Supplier 让容器惰性构建（拒绝：违背 FR-010/FR-006 的启动期失败隔离语义）。

## R5. TenantResourceRouter 实现

- **Decision**: `TenantResourceRouter` 为 `@Component`，依赖 `GenericApplicationContext` 与一个由 Registrar 构建并注入的**不可变注册索引**（`ConcurrentHashMap<TenantResourceKey, String beanName>`，`TenantResourceKey = (tenantCode, ResourceType, resourceKey)`）。`get(tenantCode, type, key, Class<T>)`：索引未命中 → 抛 `TenantResourceNotFoundException`（携带 tenantCode/type/key）；命中 → `applicationContext.getBean(beanName, type)`。`getPrimary(tenantCode, type, Class<T>)`：查 primary 索引；无 primary 且该 (tenant,type) 仅一条 → 回退该条；无 primary 且多条 → 抛 `TenantResourceNotFoundException`；重复 primary（DB 部分唯一索引之外的防御路径）取 id 最小者并已在加载期记错误日志（FR-011）。
- **Rationale**: 索引查 O(1)，Bean 解析走容器保证类型安全；异常语义与 Clarification Q2/Q3 一致。
- **Alternatives considered**: 每次遍历 `getBeanNamesForType`（拒绝：O(n) 且脆弱）；直接返回 new（拒绝：违背单例与隔离原则）。

## R6. 启用配置的读取

- **Decision**: `TenantResourceConfigService.findAllEnabled()` 实现为单条 SQL：查询 `deleted=false AND enabled=true` 且所属租户 `deleted=false AND enabled=true` 的配置，按 `id ASC` 排序（保证 primary 冲突时"取 id 最小者"的确定性）。SQL 以 `EXISTS` 关联 `tenant` 表，写在 `TenantResourceConfigMapper.xml`（同时修正该文件现有的 namespace 拼写错误 `infratrccture`）。
- **Rationale**: 一次查询避免 N+1；id 升序使重复 primary 的取舍可重现。
- **Alternatives considered**: MyBatis-Plus Wrapper 两次查询（租户列表→配置列表）（拒绝：多次往返且排序语义不直观）。

## R7. JSONB 配置体映射

- **Decision**: MP 实体 `TenantResourceConfig.config` 使用 `JsonNode` 承载，标注 `@TableName(value = "tenant_resource_config", autoResultMap = true)` + `@TableField(typeHandler = JacksonTypeHandler.class)`。实体同时改为 camelCase 字段 + `@TableField` 显式列映射（当前实体的 snake_case 字段名依赖全局 `map-underscore-to-camel-case`，与新映射方式混用易错）。
- **Rationale**: 结果映射由 typeHandler 自动完成 JSONB→JsonNode；各 Provider 用 Jackson 解析自己的 schema。
- **Alternatives considered**: 映射为 String/`PGobject` 后在 Registrar 手工 parse（拒绝：职责错位，每个消费方重复解析）；`autoResultMap` 缺省下 JacksonTypeHandler 不生效的坑已在决策中规避。

## R8. 各资源类型的 Provider 与配置 Schema

- **Decision**: 本期实现 3 个 Provider，位于 `com.unirag.tenant.resource.provider`：
  - `LlmResourceProvider`：JSON `{base-url, api-key, model-name, temperature?, max-tokens?, timeout?, log-requests?, log-responses?}` → `dev.langchain4j.model.openai.OpenAiChatModel`
  - `EmbeddingModelResourceProvider`：JSON `{base-url, api-key, model-name, max-segments-per-batch?, timeout?}` → `dev.langchain4j.model.openai.OpenAiEmbeddingModel`
  - `EmbeddingStoreResourceProvider`：JSON `{host, port, database, user, password, table, dimension, create-table?}` → `dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore`（builder API 与现有 `EmbeddingStoreConfig` 中注释代码一致）
  - `RERANK_MODEL`：枚举值保留但本期无 Provider——遇到该类型配置时记录告警并跳过（规格 Edge Case 既定行为），满足 FR-002 的"允许后续扩展"。
- **Rationale**: 3 个类型覆盖 RAG 最小闭环；langchain4j-open-ai / langchain4j-pgvector 已在 pom 中。
- **Alternatives considered**: 本期连 RERANK 一起实现（拒绝：spec 只要求"允许扩展"，YAGNI）；自建 HTTP 客户端（拒绝：重复造轮子）。

## R9. 租户领域对象与资源领域对象的分离

- **Decision**: `com.unirag.tenant.resource.TenantResourceConfig`（领域，含 `JsonNode config`，当前缺访问器）与 `com.unirag.infrastructure.mp.entity.TenantResourceConfig`（持久化）分离保留；新增 mapper 层转换（或 MapStruct 手工静态转换器），Registrar 只依赖领域对象与 Service 接口，不触碰 MP 实体。
- **Rationale**: 保持领域/持久化解耦；规格 Key Entities 亦如此建模。
- **Alternatives considered**: 复用 MP 实体穿透到 Registrar（拒绝：持久化细节泄漏到领域）。

## R10. 健康检查与可观测性（FR-006/FR-009）

- **Decision**: 新增 `TenantResourceHealthIndicator implements HealthIndicator`（actuator 已有），details 暴露 `{status, loadedCount, failedCount, failures: [{tenantCode, resourceType, resourceKey, error}]}`；存在失败时 status 仍为 `UP`（基座已就绪、故障已被隔离并可见），失败明细供 SC-003 的"1 分钟定位"。敏感字段绝不进入 details（FR-008）。
- **Rationale**: `/actuator/health` 已在 001 特性交付且被 HealthEndpointTest 契约测试锁定，复用即可。
- **Alternatives considered**: 失败时整体 DOWN（拒绝：单租户配置错误拖垮全平台健康信号，违背故障隔离初衷）。

## R11. 敏感信息处理（FR-008）

- **Decision**: Provider 解析配置时从不打印 config 原文；所有错误日志仅含 tenantCode/type/key/异常 message（langchain4j 构建器自身不做网络调用，不会泄漏）；`TenantResourceHealthIndicator` details 仅含错误摘要。
- **Rationale**: 配置体可能含 API Key。
- **Alternatives considered**: 日志中对 config 做脱敏后输出（拒绝：维护脱敏名单成本高，直接不打印更可靠）。

## R12. 测试策略（2026-09-16 四次修订：直接复用本地开发库）

- **Decision**: 集成测试**直接复用应用主配置指向的本地 PostgreSQL**（`application.yml` 的 `uni_rag_db`，localhost:5432），不建独立测试库、不引入任何测试组件。
  - 实现方式：删除 `src/test/resources/application.yml` 中的 `DataSourceAutoConfiguration` 排除（测试上下文即使用主配置数据源）；`AbstractPostgresIntegrationTest` 仅承担测试约定基类职责（全局唯一租户码、可选 `@Sql` 清理），无需 `@DynamicPropertySource`。
  - 数据隔离策略：每个测试类使用**全局唯一租户码**（如 `acmeUS1`）——断言只针对本类租户码，测试数据留在开发库中无害且可追溯；如需清理可类后 `@Sql` 删除。
  - Flyway 迁移随测试上下文启动自动执行（与开发环境同库同迁移，天然一致）。
  - **pgvector 说明**：本地 PG 已安装 pgvector 扩展（0.8.1，需一次性超级用户执行 `CREATE EXTENSION vector`），EMBEDDING_STORE 测试走完整建表路径；未安装的库测试配置退化为 `"create-table": false`（构建期不执行 DDL）。
  - 测试路径：JdbcTemplate 插数 → `TenantResourceRegistrar.loadAndRegister()`（R14）→ 断言 Bean/命名/参数；JSONB 映射、联表 SQL、Flyway 迁移全部走真实 PG 验证。
- **Rationale**: 用户明确指示不建独立测试库；开发库本就是应用运行所需，测试复用它零配置、零前置、与真实运行环境完全一致。
- **Alternatives considered**: 独立测试库 `uni_rag_test_db`（拒绝：用户指示无需，增加建库前置与配置分支）；zonky embedded-postgres（拒绝：本地已有 PG，额外依赖无必要）；H2（拒绝：非真实 PG，JSONB 行为不符）。
- **影响声明**: 测试数据（租户/配置行）会保留在开发库中，以全局唯一租户码区分，不影响应用功能；若未来需要可在基类加 `@Sql` 清理。

## R14. Registrar 可重复执行（幂等重载，支撑真实 DB 测试）

- **Decision**: `TenantResourceRegistrar` 的核心逻辑抽为 public 方法 `loadAndRegister()`，`run()` 仅委托调用。重复调用时跳过已注册的 beanName（DEBUG 日志），不抛异常、不重复注册。测试因此在启动后插数、再显式调用即可生效。
- **Rationale**: ApplicationRunner 只在上下文启动时执行一次，晚于测试插数；幂等重载同时为未来热更新/刷新奠定接口形态（本期不实现刷新）。
- **Alternatives considered**: 测试内重建独立 ApplicationContext（拒绝：掩盖装配问题、成本高）；`@Sql` 在启动前插数（拒绝：@Sql 执行晚于 ApplicationRunner，无解）。

## R13. 已识别的存量问题（随本特性顺手修复）

- `TenantResourceConfigMapper.xml` / `TenantMapper.xml` namespace 拼写错误 `infratrccture.mapper`（应为 `infrastructure.mp.mapper`）——会导致 MyBatis 绑定失败。
- `ConfigHygieneTest` 当前引用 `unirag.ai.models` 配置且断言 datasource 密码为占位符，与现有 `application.yml` 不符（现存失败用例，属于 001/002 号特性遗留）——本次不修复，仅标注；application.yml 中的明文数据库密码应改为 `${...}` 占位符（与本特性 FR-008 精神一致，建议最小改动顺手处理）。
- `application.yml` 中 `mybatis-plus.type-aliases-package: com.example.entity` 为脚手架残留，应改为 `com.unirag.infrastructure.mp.entity`。
