# Implementation Plan: 文档上传与文件存储

**Branch**: `007-document-upload` | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-document-upload/spec.md`

## Summary

在 005（摄取核心）与 006（检索问答）之间补上"上传侧"最后一块：交付文档上传/下载/查询 HTTP 接口（项目首个 Web 层），通过 Apache OpenDAL（Java 绑定）统一存储抽象，v1 后端使用 OpenDAL 的 **postgresql 服务**（key→bytea 表）存原文——零新增中间件、配置可切换 fs/S3 后端；小文件（≤10MB）一次性 multipart 上传，大文件走轻量固定分块三阶段协议（初始化→分块→完成拼装），拼装后复用 005 `IngestionService` 自动完成切片向量化，闭合"上传→存储→摄取→可检索"全链路。

## Technical Context

**Language/Version**: Java 21（沿用项目 pom）

**Primary Dependencies**: Spring Boot 3 Web/JDBC（已有）、MyBatis-Plus（已有）、Apache OpenDAL Java 绑定 `org.apache.opendal:opendal`（新增，平台 classifier 由 os-maven-plugin 探测）、Flyway（已有）

**Storage**: PostgreSQL `uni_rag_db`——原文 blob 经 OpenDAL postgresql 服务存 `document_blob(path, data bytea)`；分块暂存/会话/文件元数据为普通业务表（Flyway V5）

**Testing**: JUnit 5 + Spring Boot Test + TestRestTemplate（已有 surefire 约定）；真实 PG 集成测试，确定性嵌入替身（005 惯例）

**Target Platform**: 单实例 JVM（Linux/macOS 容器或裸跑；OpenDAL 原生库需 linux-x86_64/aarch64、osx-aarch_64 等 classifier）

**Project Type**: web-service（本项目首个 HTTP 接口 feature，Web 层基座在此落地）

**Performance Goals**: 10MB 小文件一次上传（含摄取）p95 < 30s（SC-001）；下载吞吐接近内网 PG 带宽，p95 < 5s（50MB）；分块续传仅重传缺失块（SC-007）

**Constraints**: 轻量级——除 OpenDAL 客户端库外零新增部署组件；单文件默认 50MB 上限、块默认 5MB、小文件阈值默认 10MB（均可配置）；日志无密钥（FR-010 红线）

**Scale/Scope**: 轻量级多租户部署；文件总量 GB~几十 GB 级（TOAST/备份在该量级无压力，见 research R8）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

项目 constitution 为未填充模板，无显式治理条款。采用 spec 红线作为准入门禁：

| 门禁 | 来源 | 状态 |
|---|---|---|
| 轻量级、零新增中间件 | spec Clarifications | ✅ OpenDAL 仅客户端库；存储后端复用已有 PG |
| 全部行为 `./mvnw test` 真实库可验 | FR-008 | ✅ 集成测试设计遵循 005 惯例 |
| 日志/输出无密钥 | FR-010 | ✅ 错误模型与 005 一致（原因可读、不含 config 原文） |
| 复用 005 摄取语义、不重复实现 | FR-007 | ✅ 拼装后直接调用 `IngestionService` |

Phase 1 设计完成后复核：无新增违规。

## Project Structure

### Documentation (this feature)

```text
specs/007-document-upload/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/upload-contract.md
└── tasks.md             # Phase 2（speckit-tasks 生成，不在本期）
```

### Source Code (repository root)

```text
src/main/java/com/unirag/
├── storage/                    # 新增：OpenDAL 存储基座
│   ├── StorageProperties.java      # unirag.storage.* 配置绑定
│   ├── StorageAutoConfiguration.java # 按 backend 构建 Operator Bean
│   └── package-info.java
├── upload/                     # 新增：上传侧 Web 层与业务
│   ├── DocumentUploadController.java   # 一次性上传（小文件）
│   ├── ChunkUploadController.java      # 分块三阶段接口
│   ├── DocumentQueryController.java    # 列表/详情/下载
│   ├── UploadService.java              # 会话/分块/拼装编排
│   ├── DocumentFileService.java        # 文档+文件元数据落库
│   ├── FileTypeValidator.java          # 扩展名+内容双重校验（FR-003）
│   └── support/
│       ├── TenantContext.java          # X-Tenant-Code 解析（Web 基座）
│       └── UploadAssembler.java        # 分块→拼装→摄取
└── ingestion/ ... (005 已有，不动)

src/test/java/com/unirag/upload/        # 新增集成测试（真实库）
├── DocumentUploadIT.java        # US1 一次性上传+摄取闭环
├── ChunkedUploadResumeIT.java   # US1/断点续传 分块/断网续传/缺失块
├── DocumentDownloadIT.java      # US2 下载逐字节一致/租户隔离
└── UploadDedupFailureIT.java    # US3 重复上传/入口拒绝/存储失败无残留
```

**Structure Decision**: 单项目结构。`storage` 包是横切基座（未来 006 及后续 feature 复用），`upload` 包为本 feature 业务域；Web 层（租户解析、错误模型）在本 feature 首次落地，006 直接复用 `TenantContext` 约定。

## Complexity Tracking

> 无违规需记录。OpenDAL 引入为 spec 明确要求的依赖，替代方案（手写存储 SPI + 多种实现）被否的原因见 research R1。
