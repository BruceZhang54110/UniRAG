# Implementation Plan: 文档摄取与双粒度切片

**Branch**: `005-document-ingestion` | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-document-ingestion/spec.md`

## Summary

在 004 向量存储基座上交付文档摄取：新增 `ingestion_pipeline`/`document`/`document_chunk`/`ingestion_run` 四张业务表（Flyway V4），摄取服务按租户启用管线执行"切片→向量化→写入租户向量表"，每片段以向量 metadata 回链业务记录；内容指纹（SHA-256）驱动重复摄取 no-op/替换语义；管线间故障隔离、部分成功状态可查；切片策略字段化可插拔（v1 递归切分基线）。

## Technical Context

**Language/Version**: Java 21（沿用 001 骨架）

**Primary Dependencies**: Spring Boot 3.5.16、langchain4j 1.20.0（core/open-ai）、langchain4j-pgvector 1.20.0-beta30、MyBatis-Plus 3.5.17、Flyway、PostgreSQL 16 + pgvector

**Storage**: PostgreSQL `uni_rag_db`——业务表（V4）与向量表（004 动态表）同库；向量按租户资源独立表不变

**Testing**: JUnit 5 + Spring Boot Test；真实本地开发库集成测试（003 约定：每测试类全局唯一租户码）；确定性 `EmbeddingModel` 替身（固定映射文本→向量），无外部模型凭据

**Target Platform**: Linux/macOS 服务器，Spring Boot 可执行 jar

**Project Type**: web-service（本期无 HTTP 接口，摄取为应用内服务）

**Performance Goals**: 常规开发环境摄取 5000 字文档（细粒度 ≤10 片段，替身模型）< 10s；切片/入库为批量操作（embedAll 分批）

**Constraints**: 敏感配置不入日志（FR-010）；`./mvnw test` 全绿（003/004 既有用例零回退）；管线间故障隔离（005 FR-004）

**Scale/Scope**: 每租户 1..N 条管线、每文档每管线 1..M 片段；单表行级规模十万级内无需额外优化

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 为未填写的模板，无强制门。Phase 1 后复核：本设计沿用 003/004 已确立的故障隔离、敏感信息红线、测试约定，无新增冲突。

## Project Structure

### Documentation (this feature)

```text
specs/005-document-ingestion/
├── plan.md              # 本文件
├── research.md          # Phase 0 决策记录
├── data-model.md        # Phase 1 数据模型
├── quickstart.md        # Phase 1 验证指南
├── contracts/           # Phase 1 契约
└── tasks.md             # Phase 2 产物（speckit-tasks 生成）
```

### Source Code (repository root)

```text
src/main/resources/db/migration/postgresql/
└── V4__ingestion_schema.sql           # 新建：ingestion_pipeline / document / document_chunk / ingestion_run

src/main/java/com/unirag/ingestion/
├── IngestionService.java              # 新建：摄取入口（ingest(request) → IngestOutcome）
├── IngestRequest.java / IngestOutcome.java  # 新建：请求/结果模型
├── PipelineExecutor.java              # 新建：单管线执行（切分→向量化→写库→回链）
├── DocumentSplitterFactory.java       # 新建：strategy → DocumentSplitter 策略映射（v1 RECURSIVE）
├── IngestionTokenizer.java            # 新建：token 计量封装（OpenAiTokenCountEstimator）
└── support/DocumentFingerprint.java   # 新建：SHA-256 内容指纹

src/main/java/com/unirag/infrastructure/mp/
├── entity/{IngestionPipeline,Document,DocumentChunk,IngestionRun}.java  # 新建
├── mapper/...                         # 新建 4 个 Mapper + XML
└── service/...                        # 新建 4 个 Service

src/test/java/com/unirag/ingestion/
├── DeterministicEmbeddingModel.java   # 新建：测试替身（文本哈希→固定维度向量）
├── DocumentIngestionIT.java           # 新建：US1 单管线摄取闭环
├── DualGranularityIngestionIT.java    # 新建：US2 粗/细双管线
└── IngestionReplaceAndFailureIT.java  # 新建：US3 重摄取/故障隔离
```

**Structure Decision**: 新顶层包 `com.unirag.ingestion`（领域服务）+ `infrastructure.mp`（持久化，沿用 003 分层）；管线不走 003 资源体系——理由见 research.md R2。

## Complexity Tracking

无 Constitution 违规，无需记录。
