# Implementation Plan: 异步文档摄取任务

**Branch**: `008-async-document-ingestion` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-async-document-ingestion/spec.md`

## Summary

将现有同步上传收尾改为持久化异步任务：保存原文、文档版本元数据和 `document_ingestion_job` 后立即以 `202 Accepted` 返回 `QUEUED`；worker 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取任务，复用 `IngestionService`，以租约、有限重试和指数退避支持进程恢复。新增任务状态查询 API、Flyway 迁移、job/worker 持久化模型及集成测试；新版本只有达到可检索 `READY` 后才切换活动版本并清理旧版本。前端页面不在本期实现。

## Technical Context

**Language/Version**: Java 21；Spring Boot 3.5.16

**Primary Dependencies**: Spring Web、Spring JDBC、MyBatis-Plus 3.5.17、Flyway、PostgreSQL JDBC、Apache OpenDAL 0.50.6、现有 LangChain4j 摄取管线

**Storage**: PostgreSQL（job 队列、文档/文件元数据、摄取记录；Flyway 管理迁移）与 OpenDAL（版本化原文 blob）；不引入独立消息代理

**Testing**: JUnit 5 / Spring Boot Test / MockMvc；复用现有三个 PostgreSQL/上传/摄取集成测试基类

**Target Platform**: Spring Boot REST 服务，部署于可访问 PostgreSQL 和 OpenDAL 的服务器

**Project Type**: 多租户 Spring Boot Web service

**Performance Goals**: 合格上传在 2 秒内返回任务标识和 `QUEUED`（不含传输）；重启后未完成任务在 1 分钟内重新调度

**Constraints**: 上传不等待摄取；租户隔离；同一 job 同时最多一个 worker；默认最大尝试 3 次（含首次）、指数退避基数 30 秒且有上限；错误不泄漏凭据；PostgreSQL 承担队列

**Scale/Scope**: 轻量进程内 worker + PostgreSQL 持久队列；覆盖上传、查询、重试、租约恢复、幂等和版本切换，不含前端、取消、批量导入、OCR 或独立消息代理

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

当前 `.specify/memory/constitution.md` 是模板占位内容，原则、门禁、治理规则和版本均未有效定义。因此不存在可执行项目门禁，也没有可判定违规；本计划仍遵循已有技术栈、真实 PostgreSQL 集成测试和最小化架构约束。

**Phase 0 gate**: PASS — 研究已解决重试、READY、API、SKIP LOCKED、租约、幂等和版本切换未知点。

**Phase 1 re-check**: PASS — 仅增加本功能所需 Flyway 迁移、job API 和 worker，复用现有上传与摄取服务；边界由实现阶段集成测试验证。

## Project Structure

### Documentation (this feature)

```text
specs/008-async-document-ingestion/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/async-ingestion-api.md
```

本阶段不创建 `tasks.md`；由后续任务生成阶段负责。

### Source Code (repository root)

```text
src/
├── main/
│   ├── java/com/unirag/
│   │   ├── upload/                  # 上传 controller/service、DTO、响应包装和租户上下文
│   │   ├── ingestion/               # IngestionService、job worker/recovery 和状态
│   │   ├── infrastructure/mp/
│   │   │   ├── entity/              # Document、DocumentFile、IngestionRun、job/版本实体
│   │   │   ├── mapper/              # MyBatis-Plus/XML mapper
│   │   │   └── service/             # 持久化服务
│   │   └── storage/                 # OpenDAL 配置
│   └── resources/
│       ├── db/migration/postgresql/ # V1-V5 及新增异步任务迁移
│       └── mapper/                  # MyBatis XML
└── test/java/com/unirag/
    ├── AbstractPostgresIntegrationTest.java
    ├── upload/                      # AbstractUploadIT 和上传测试
    └── ingestion/                   # AbstractIngestionIT 和摄取测试
```

**Structure Decision**: 保持现有单 Maven Spring Boot 后端结构。job API 放入现有上传/API 包，worker 与任务状态协调放入 `ingestion`，实体/Mapper 和迁移放入现有基础设施目录；不新增 frontend、独立 service 或消息代理项目。

## Complexity Tracking

无。constitution 为模板占位内容，不存在有效门禁违规或需豁免的复杂度。

