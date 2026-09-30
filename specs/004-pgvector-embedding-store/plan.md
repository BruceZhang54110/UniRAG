# Implementation Plan: PGVector 向量存储落地

**Branch**: `004-pgvector-embedding-store` | **Date**: 2026-09-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-pgvector-embedding-store/spec.md`

## Summary

在 003 多租户资源基座之上完成向量存储落地：Flyway V3 迁移统一启用 pgvector 扩展；`EmbeddingStoreResourceProvider` 补强表名白名单校验、加载期表存在性/维度校验、跨租户同名表冲突检测（Registrar 级 collisionKey 机制），使每个租户配置的向量存储在启动后即达到"可写入、可检索、维度正确、租户隔离"的就绪状态。范围严格限定建表 + 装配 + 集成验证，不含文档摄取与检索问答。

## Technical Context

**Language/Version**: Java 21（沿用 feature 001 骨架）

**Primary Dependencies**: Spring Boot 3.5.16、langchain4j 1.20.0、langchain4j-pgvector 1.20.0-beta30、MyBatis-Plus 3.5.17、Flyway（postgresql）、PostgreSQL 16 + pgvector

**Storage**: PostgreSQL（业务数据与向量数据统一于 `uni_rag_db`；pgvector 扩展 + 每租户资源一张动态向量表）

**Testing**: JUnit 5 + Spring Boot Test；真实本地开发库 `uni_rag_db` 集成测试（沿用 003 约定：每测试类全局唯一租户码）；确定性本地构造向量，无外部模型凭据

**Target Platform**: Linux/macOS 服务器，Spring Boot 可执行 jar

**Project Type**: web-service（后端服务，本期无新 HTTP 接口）

**Performance Goals**: 加载期校验为一次性元数据查询（pg_attribute），单次 <100ms；不引入运行时热点

**Constraints**: 应用数据库账号不持有超级用户权限（扩展创建归迁移、迁移账号需管理员预备）；日志/health 不得含密码（FR-008 红线延续）

**Scale/Scope**: 每租户 1..N 张向量表（N = 该租户 EMBEDDING_STORE 资源数，含粗细粒度等多表场景）；单表维度由配置决定（如 1024）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 为未填写的模板，未定义任何强制门（principles/gates 均为占位符）。视为无门限约束通过。Phase 1 后复核：本设计未引入新原则冲突（沿用 003 已确立的故障隔离、敏感信息红线、测试约定）。

## Project Structure

### Documentation (this feature)

```text
specs/004-pgvector-embedding-store/
├── plan.md              # 本文件
├── research.md          # Phase 0 决策记录
├── data-model.md        # Phase 1 数据模型
├── quickstart.md        # Phase 1 验证指南
├── contracts/           # Phase 1 契约（向量表保证语义）
└── tasks.md             # Phase 2 产物（speckit-tasks 生成，不在本命令产出）
```

### Source Code (repository root)

```text
src/main/resources/db/migration/postgresql/
└── V3__pgvector_extension.sql         # 新建：幂等启用 pgvector 扩展

src/main/java/com/unirag/tenant/resource/
├── TenantResourceProvider.java        # 修改：新增 default collisionKey(config)
└── TenantResourceRegistrar.java       # 修改：加载期冲突键去重（先加载者胜）

src/main/java/com/unirag/tenant/resource/provider/
├── EmbeddingStoreResourceProvider.java # 修改：skipCreateVectorExtension、表名白名单、维度/存在性校验接线
└── EmbeddingStoreTableVerifier.java   # 新建：一次性 JDBC 元数据校验（表存在 + atttypmod 维度）

src/test/java/com/unirag/tenant/resource/
├── TenantResourceRegistrarIT.java          # 既有（003），回归保持绿色
├── PgVectorEmbeddingStoreIT.java           # 新建：US2 双租户写入/检索隔离
├── EmbeddingStoreCollisionIT.java          # 新建：US3 跨租户同名表检出
└── EmbeddingStoreDimensionMismatchIT.java  # 新建：已存在表维度不匹配 → 构建失败隔离
```

**Structure Decision**: 单项目结构（沿用 001–003）。校验逻辑独立为 `EmbeddingStoreTableVerifier` 而非内联在 Provider，是为可测试性与单一职责；冲突检测放在 Registrar（类型无关的机制）+ Provider 提供冲突键（类型相关的取值规则），与 003 既有 SPI 风格一致。

## Complexity Tracking

无 Constitution 违规，无需记录。
