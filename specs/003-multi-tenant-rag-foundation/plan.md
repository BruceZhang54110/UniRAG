# Implementation Plan: 多租户 RAG 资源基座

**Branch**: `003-multi-tenant-rag-foundation` | **Date**: 2026-09-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-multi-tenant-rag-foundation/spec.md`

## Summary

为多租户 RAG 应用实现资源加载基座：启动时从 `tenant_resource_config` 表读取全部启用租户的资源配置（JSONB），通过可插拔的 `TenantResourceProvider<T>` SPI（Spring Bean 自动收集）为每条配置创建独立的模型/存储实例（langchain4j OpenAI 聊天模型、OpenAI 嵌入模型、PgVector 向量存储），以 `{resourceType}_{tenantCode}_{resourceKey}` 命名动态注册为 Spring Bean；业务侧经 `TenantResourceRouter` 按"租户 + 类型 + 标识"或主资源获取实例。单条配置失败仅记录并跳过，不阻塞其余加载（故障隔离），健康检查暴露加载/失败明细。技术决策详见 [research.md](./research.md)。

## Technical Context

**Language/Version**: Java 21（Spring Boot 3.5.16）

**Primary Dependencies**: Spring Framework 6（动态 Bean 注册）、langchain4j 1.20.0（open-ai）/ 1.20.0-beta30（pgvector）、MyBatis-Plus 3.5.17、Jackson（JSONB 配置解析）、Lombok、Spring Boot Actuator

**Storage**: PostgreSQL（`tenant`、`tenant_resource_config` 两张表已由 V2 Flyway 迁移建立，含 `tenant_code` 唯一索引与 `(tenant_id, resource_type, resource_key)` 部分唯一索引、`(tenant_id, resource_type) WHERE is_primary` 部分唯一索引）

**Testing**: JUnit 5 + Spring Boot Test；集成测试**直接复用本地开发库**（主配置 `application.yml` 指向的 `uni_rag_db`），无独立测试库、无额外测试组件、无 Docker；数据隔离依靠每测试类全局唯一租户码

**Target Platform**: Linux/macOS 服务器，JVM 21

**Project Type**: web-service（Spring Boot 单体，后续在其上构建 RAG 业务能力）

**Performance Goals**: 100 租户 × 3 资源 = 300 实例启动期加载；单条失败对整体启动时长影响 ≤ 10%（SC-001）

**Constraints**: 日志与接口不得输出 API Key 等敏感信息（FR-008）；单条资源配置失败不得影响其他租户（FR-006）；测试直接复用本地开发库（无独立测试库），依赖类级唯一租户码隔离；EMBEDDING_STORE 测试在未装 pgvector 扩展时须 `create-table: false`

**Scale/Scope**: ≤100 租户、每租户同类型 ≤10 个资源实例（本期目标规模，见 SC-001）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 仍为未填充的模板（无已批准的原则/门禁），视为无强制约束。**通过**（无违反项，Complexity Tracking 不适用）。

Phase 1 设计后复查：设计未新增项目/仓库/模式复杂度，全部复用现有骨架（Registrar/Provider SPI/Actuator），**仍通过**。

## Project Structure

### Documentation (this feature)

```text
specs/003-multi-tenant-rag-foundation/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（R1~R13 技术决策）
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/
│   ├── tenant-resource-router.md   # Router API 契约
│   └── resource-config-schemas.md  # 各资源类型 JSON 配置 Schema
├── checklists/
│   └── requirements.md  # 规格质量清单（/speckit-specify 产物）
└── tasks.md             # Phase 2 输出（/speckit-tasks，非本命令产物）
```

### Source Code (repository root)

```text
src/main/java/com/unirag/
├── UniRagApplication.java
├── tenant/resource/
│   ├── ResourceType.java                    # 已有：枚举，新增 beanPrefix()
│   ├── TenantResourceProvider.java          # 已有：SPI 接口
│   ├── TenantResourceConfig.java            # 已有：领域配置，补访问器/改用 JsonNode
│   ├── TenantResourceKey.java               # 新增：(tenantCode, ResourceType, resourceKey) 值对象
│   ├── TenantResourceNotFoundException.java # 新增：专用异常
│   ├── TenantResourceRegistrar.java         # 半成品→完成 run()：查询→构建→注册→索引
│   ├── TenantResourceRouter.java            # 新增：get / getPrimary
│   ├── TenantResourceIndex.java             # 新增：不可变注册索引（Registrar 建，Router 读）
│   ├── TenantResourceAutoConfiguration.java # 新增：装配 Registrar/Router/HealthIndicator
│   └── provider/
│       ├── LlmResourceProvider.java            # 新增：JSON → OpenAiChatModel
│       ├── EmbeddingModelResourceProvider.java # 新增：JSON → OpenAiEmbeddingModel
│       └── EmbeddingStoreResourceProvider.java # 新增：JSON → PgVectorEmbeddingStore
├── tenant/observability/
│   └── TenantResourceHealthIndicator.java   # 新增：actuator health 明细
└── infrastructure/mp/
    ├── entity/TenantResourceConfig.java     # 改造：camelCase + JacksonTypeHandler(autoResultMap)
    ├── service/TenantResourceConfigService.java           # 已有：findAllEnabled() 待实现
    ├── service/impl/TenantResourceConfigServiceImpl.java  # 实现联表查询
    └── mapper/TenantResourceConfigMapper.java             # 与 XML 同步修正 namespace 拼写

src/main/resources/mapper/TenantResourceConfigMapper.xml  # 修正 namespace + findAllEnabled SQL
src/main/resources/application.yml                        # type-aliases-package 修正；密码占位符化
src/test/java/com/unirag/AbstractPostgresIntegrationTest.java  # 新增：测试约定基类（唯一租户码；数据源=主配置）
src/test/java/com/unirag/tenant/resource/                 # 新增测试包（真实 DB）
```

**Structure Decision**: 单项目结构。所有新代码落在既有 `com.unirag.tenant.resource` SPI 包及其子包 `provider` 中，持久化层仅做最小修正（R6/R7/R13），不引入新模块/新依赖。

## Complexity Tracking

无 Constitution 违反项，本节不适用。
