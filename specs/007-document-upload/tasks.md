---
description: "Task list for 007-document-upload"
---

# Tasks: 文档上传与文件存储

**Input**: Design documents from `/specs/007-document-upload/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/upload-contract.md, quickstart.md

**Tests**: spec FR-008 要求全部行为 `./mvnw test` 真实库可验——测试任务已包含（先写后实现）。

**Organization**: 按用户故事分组；Phase 4 为 US1 的断点续传扩展（spec FR-010/SC-007），独立成阶段以保 MVP 先行。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3（映射 spec.md 用户故事）
- 路径均相对仓库根目录

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: OpenDAL 依赖接入与配置基座

- [x] T001 pom.xml 接入 `kr.motd.maven:os-maven-plugin:1.7.0` 构建扩展 + `org.apache.opendal:opendal` 主 artifact 与 `${os.detected.classifier}` classifier 双依赖，新增 `<opendal.version>` property（引入时在 Maven Central 核实最新稳定版并锁定；Javadoc 显示 0.50.x 系列）
- [x] T002 src/main/resources/application.yml 增加默认配置：`unirag.storage.*`（backend=postgresql、table=document_blob、连接串沿用 spring.datasource 同源参数）与 `unirag.upload.*`（max-file-size=50MB、chunk-size=5MB、one-shot-threshold=10MB、session-ttl=24h、janitor-interval=1h）
- [x] T003 [P] 配置绑定类 `src/main/java/com/unirag/storage/StorageProperties.java`（@ConfigurationProperties("unirag.storage")）与 `src/main/java/com/unirag/upload/UploadProperties.java`（@ConfigurationProperties("unirag.upload")），启用配置属性注册

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 存储基座、Web 基座、数据表——所有故事的前置

**⚠️ CRITICAL**: 本阶段完成前用户故事无法开始

- [x] T004 Flyway 迁移 `src/main/resources/db/migration/postgresql/V5__upload_storage_schema.sql`：按 data-model.md 建 `document_file` / `upload_session` / `upload_chunk` / `document_blob` 四表；`document_blob(path text PK, data bytea)` 列名对齐 OpenDAL postgresql 服务默认 key/value 字段；含 `(tenant_id, status, expires_at)` 索引
- [x] T005 [P] MyBatis-Plus 实体与 Mapper：新增 `DocumentFile`/`UploadSession`/`UploadChunk` 实体（`src/main/java/com/unirag/infrastructure/mp/entity/`）与对应 Mapper + Service（`mapper/`、`service/`+`service/impl/`），沿用 003/005 软删除与时间戳惯例
- [x] T006 存储自动配置 `src/main/java/com/unirag/storage/StorageAutoConfiguration.java`：按 `unirag.storage.backend`（postgresql|fs|s3）构建 OpenDAL `Operator` 并暴露 Blocking Operator Bean；postgresql 分支读取 StorageProperties 组装 connectionString/table/keyField/valueField；构建失败 fail-fast
- [x] T007 [P] Web 租户基座：`src/main/java/com/unirag/upload/support/TenantContext.java`（X-Tenant-Code 解析 + 未知租户判定）与 `WebConfig.java`（拦截器注册），缺失头 → 400、未知租户 → 404（contracts §1）
- [x] T008 [P] 统一响应/错误模型：`src/main/java/com/unirag/upload/support/ApiResponse.java`（`{data}` 包装）、`ApiException.java`（code/message/details）、`GlobalExceptionHandler.java`（实现 contracts §1 错误码全集；message 可读且不含密钥/config 原文，FR-010）
- [x] T009 [P] 文件校验器 `src/main/java/com/unirag/upload/FileTypeValidator.java`：扩展名白名单（.txt/.md/.markdown）+ UTF-8 REPORT 解码嗅探 + NUL 字节扫描 + 文件名归一化（去路径分隔符/`..`/截断 512/非 UTF-8 替换），research R6 口径

**Checkpoint**: Operator Bean 可用、四表就绪、Web 错误模型统一——故事实现可并行开始

---

## Phase 3: User Story 1 - 上传文档并自动完成摄取入库 (Priority: P1) 🎯 MVP

**Goal**: 小文件（≤10MB）一次性 multipart 上传 → OpenDAL 落 PG blob → 元数据入库 → 自动触发 005 摄取 → 返回文档标识与逐管线结果（spec FR-001/FR-002/FR-005 的一次性路径；SC-001）

**Independent Test**: `DocumentUploadIT` 全绿 + 手工 curl 上传后文档可下载、切片与向量行数一致（quickstart 场景 1/3）

### Tests for User Story 1 ⚠️

> **先写测试并确认失败，再实现**（005 惯例）

- [x] T010 [US1] 新建 `src/test/java/com/unirag/upload/DocumentUploadIT.java`（继承项目 IT 基类，租户码 `upUS1`，向量表命名 `embeddings_upus1`）：① POST /api/v1/documents 上传长 Markdown → 断言 200、响应含 docName/逐管线 outcome（SUCCESS/片段数）；② `document`/`document_chunk` 行数与向量表记录一致且可回链（005 口径）；③ `document_blob` 经 Operator 读回与上传字节一致；④ 同内容二次上传 NOOP（version 不变、行数不翻倍）；⑤ 同文档另一租户检索/访问不到（租户隔离）

### Implementation for User Story 1

- [x] T011 [P] [US1] `src/main/java/com/unirag/upload/DocumentFileService.java`：`document_file` 元数据落库/按 documentId 查询
- [x] T012 [US1] `src/main/java/com/unirag/upload/UploadService.java` 一次性上传路径：Content-Length 预检 + 接收终检（≤one-shot-threshold）→ FileTypeValidator 双重校验 → UTF-8 解码 → storage_path=`document_file/{tenantCode}/{docName}/v1`（research R7）→ Blocking Operator 写 blob → 单事务建 `document`（source="upload"）+`document_file` → 调用 005 `IngestionService.ingest(IngestRequest)` → 组装契约 §2 响应
- [x] T013 [US1] `src/main/java/com/unirag/upload/DocumentUploadController.java`：POST /api/v1/documents，multipart 参数绑定（file/docName/title）+ 校验 + 错误码映射（VALIDATION_FAILED/UNSUPPORTED_FILE_TYPE/FILE_TOO_LARGE）
- [x] T014 [P] [US1] 响应 DTO：`src/main/java/com/unirag/upload/dto/DocumentUploadResponse.java`（含 pipelines 逐管线 outcome 列表），IngestOutcome→DTO 映射

**Checkpoint**: US1（一次性路径）独立可用——上传后 30 秒内文档可被 005 口径验证为摄取就绪（SC-001；006 落地前以 IT 断言替代检索验证，quickstart 场景 4）

---

## Phase 4: US1 扩展 - 断点续传（分块上传，FR-010 / SC-007）

**Goal**: 大文件轻量固定分块三阶段（初始化→分块→完成拼装），断网仅补缺失块，单事务拼装+SHA-256 校验（research R4）

**Independent Test**: `ChunkedUploadResumeIT` 全绿（quickstart 场景 1 的分块部分）

### Tests for 断点续传 ⚠️

- [x] T015 [US1] 新建 `src/test/java/com/unirag/upload/ChunkedUploadResumeIT.java`（租户码 `upResume`）：① 初始化 8 块会话 → 只传 0-3 块 → complete → 409 UPLOAD_INCOMPLETE 且 `details.missingChunks=[4,5,6,7]`；② 补传 4-7 后 complete → 200，下载原文逐字节一致；③ 同 seq 重传幂等（块清单与最终内容以最后一次为准）；④ 篡改一块 → 409 CHECKSUM_MISMATCH；⑤ 跨租户访问会话 → 404 UPLOAD_NOT_FOUND

### Implementation for 断点续传

- [x] T016 [P] [US1] `src/main/java/com/unirag/upload/UploadSessionService.java`：会话创建（参数一致性校验：chunkSize/totalChunks/totalSize 派生关系与上限）、分块幂等写入（`(session_id, seq)` 覆盖语义）、已收块清单查询、单会话读取（租户归属校验）
- [x] T017 [US1] `src/main/java/com/unirag/upload/support/UploadAssembler.java`：complete 编排——单事务内按 seq 读全部块 → 全文 SHA-256 与 `content_sha256` 比对 → 拼装入 Blocking Operator → 建 `document`+`document_file` → 删暂存块；校验失败抛 409（UPLOAD_INCOMPLETE/CHECKSUM_MISMATCH）；事务提交后调用 IngestionService
- [x] T018 [US1] `src/main/java/com/unirag/upload/ChunkUploadController.java`：POST /api/v1/uploads（JSON 初始化）、PUT /api/v1/uploads/{uploadId}/chunks/{seq}（raw body，Content-Length ≤ chunkSize 且累计 ≤ totalSize）、POST /api/v1/uploads/{uploadId}/complete（响应同契约 §3）
- [x] T019 [P] [US1] 过期清理 `src/main/java/com/unirag/upload/UploadSessionJanitor.java`：@Scheduled（默认 1h）+ 启动即扫一次，删除 `expires_at < now()` 的 INIT 会话及其暂存块（session-ttl 默认 24h，可配置）

**Checkpoint**: 断点续传闭环——大文件中断续传仅补缺失块，最终原文逐字节一致（SC-007）

---

## Phase 5: User Story 2 - 下载原文与查询文档信息 (Priority: P2)

**Goal**: 原文下载（逐字节一致 + 正确 headers）、文档列表/详情（仅本租户、倒序、分页）（spec FR-004；SC-003/SC-005）

**Independent Test**: `DocumentDownloadIT` 全绿 + 手工 curl 下载 diff 字节一致（quickstart 场景 3-②）

### Tests for User Story 2 ⚠️

- [x] T020 [US2] 新建 `src/test/java/com/unirag/upload/DocumentDownloadIT.java`（租户码 `upDl`）：① 上传 → 下载 → 字节一致、`Content-Type`/`Content-Disposition filename*` 正确；② 跨租户下载他人文档 → 404 DOCUMENT_NOT_FOUND（不泄漏存在性）；③ 列表分页只含本租户、按创建时间倒序；④ 详情字段（status/chunkCount/fileSizeBytes/mimeType）与实际入库一致

### Implementation for User Story 2

- [x] T021 [P] [US2] `src/main/java/com/unirag/upload/DocumentQueryService.java`：列表（MyBatis-Plus 分页 + tenant 过滤 + 倒序）与详情（document JOIN document_file）查询
- [x] T022 [US2] `src/main/java/com/unirag/upload/DocumentQueryController.java`：GET /api/v1/documents（page/size）、GET /api/v1/documents/{docName}、GET /api/v1/documents/{docName}/file（Blocking Operator 读 blob，mime/归一化 filename 入 headers）

**Checkpoint**: US2 独立可用——上传是双向通道，下载/查询可验收

---

## Phase 6: User Story 3 - 重复上传语义与存储故障边界 (Priority: P3)

**Goal**: 重复上传 no-op/替换语义贯穿文件层（旧版本 blob 键清理）、入口拒绝零残留、存储失败无半截记录（spec FR-005/FR-006；SC-002/SC-004）

**Independent Test**: `UploadDedupFailureIT` 全绿

### Tests for User Story 3 ⚠️

- [x] T023 [US3] 新建 `src/test/java/com/unirag/upload/UploadDedupFailureIT.java`（租户码 `upDedup`）：① 改内容重传同名文档 → version 1→2、旧版本 blob 键已删、切片/向量按 005 替换语义更新；② 超一次性上传阈值、非白名单扩展名、伪装二进制（.md 后缀的 PNG 内容）→ 入口拒绝且库中无 document/document_file 残留；③ Mockito spy 模拟 OpenDAL 写入故障 → 上传失败但无半截记录、响应错误可读；④ `OutputCaptureExtension` 捕获存储故障日志并断言不含模拟异常细节、password/api-key。注：未单独构造 Content-Length 欺骗或 drop 表场景。

### Implementation for User Story 3

- [x] T024 [US3] 替换语义收尾（T012/T017 内补全）：重摄取成功后删除 `v{version-1}` 旧 blob 键（research R7；删除失败仅 WARN 不阻断，下次重摄取兜底）
- [x] T025 [US3] 故障一致性复核：blob 写入失败时无 `document`/`document_file` 残留；错误 message 统一走契约错误模型（无 config 原文/密钥）

**Checkpoint**: 全部用户故事独立可用且互不破坏

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 收尾与跨切面验证

- [x] T026 [P] contracts/upload-contract.md 与实现对齐走查（错误码、响应字段、header 语义逐项核对），已按实际分块长度校验和 OpenDAL 写入边界修正契约描述
- [x] T027 [P] application.yml 新增配置项补注释文档（用途/默认值/单位），security 相关键保持 env 占位
- [x] T028 全量验证：`./mvnw test` 全绿（52 tests，含 005/006 既有 IT 不回归）；`./mvnw spring-boot:run` 启动成功，`/actuator/health` 返回 UP，OpenDAL 原生库按 osx-aarch_64 classifier 正常加载
- [x] T029 更新 `specs/007-document-upload/spec.md` Status（Draft → Ready for review）与 README 功能清单简述

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup；T004 阻塞 T005（实体依赖表结构）；T006/T007/T008/T009 互相独立可并行；**阻塞全部故事**
- **Phase 3 (US1 一次性)**: 依赖 T004-T009；T010 先于 T011-T014（测试先行）
- **Phase 4 (断点续传)**: 依赖 Phase 3 完成（复用 UploadService 的摄取衔接与校验）；T015 先于 T016-T019
- **Phase 5 (US2)**: 依赖 Foundational + Phase 3（复用上传产出的数据做验证）；T020 先于 T021-T022；可与 Phase 4 并行（不同文件、不同测试类）
- **Phase 6 (US3)**: 依赖 Phase 3/4（验证对象为已实现的重复上传路径）；T023 先于 T024/T025
- **Polish (Phase 7)**: 依赖全部所需故事完成

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始，无其他故事依赖 —— **MVP**
- **US2 (P2)**: Foundational 后即可开始；测试数据经 US1 接口产生，实现本身不依赖 US1 代码
- **US3 (P3)**: 依赖 US1/断点续传的行为存在（是对其语义的验收加固）

### Within Each User Story

- 测试先写并确认失败（T010/T015/T020/T023）
- Service 先于 Controller
- 单故事完成后 `./mvnw test -Dtest=<Story>IT` 独立验证

### Parallel Opportunities

- Phase 2 内 T005/T006/T007/T008/T009 全并行（不同包/文件）
- Phase 3 内 T011/T014 并行；Phase 4 内 T016/T019 并行；Phase 5 内 T021 独立先行
- Phase 4 与 Phase 5 可并行（ChunkedUploadResumeIT vs DocumentDownloadIT，不同测试类与控制器）
- US1/US2/US3 测试任务可由不同开发者并行起草

---

## Parallel Example: Phase 2 并行启动

```bash
# Foundational 六个任务中 T005-T009 可同批启动（T004 先行或与 T005 配对）：
Task: "V5__upload_storage_schema.sql 四表迁移"
Task: "DocumentFile/UploadSession/UploadChunk 实体与 Mapper"
Task: "StorageAutoConfiguration（Operator Bean）"
Task: "TenantContext + WebConfig（X-Tenant-Code）"
Task: "ApiResponse/ApiException/GlobalExceptionHandler"
Task: "FileTypeValidator（白名单+嗅探+归一化）"
```

---

## Implementation Strategy

### MVP First（Phase 1→2→3，一次性上传）

1. Setup + Foundational（T001-T009）
2. US1 一次性路径（T010-T014），**STOP**：DocumentUploadIT 全绿 = 上传→存储→摄取闭环可用
3. 手工 curl 抽查（quickstart 场景 3-①②）后可对外演示

### Incremental Delivery

1. MVP（一次性上传）→ 验证
2. + 断点续传（T015-T019）→ ChunkedUploadResumeIT 绿（SC-007）
3. + 下载/查询（T020-T022）→ 上传双向通道完整
4. + 故障边界（T023-T025）→ 生产语义加固
5. Polish（T026-T029）→ 全量回归 + 文档收尾

### Parallel Team Strategy

- 开发者 A：Phase 3 + Phase 4（上传链路）
- 开发者 B：Phase 5（下载/查询，T004-T009 完成后即可起步，测试数据用 curl 造）
- 开发者 C：Phase 6 测试先行起草（T023）与契约走查（T026 前期）

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 测试类与租户码沿用项目惯例：`upUS1`/`upResume`/`upDl`/`upDedup`，向量表 `embeddings_<租户码小写>`
- 每个 Checkpoint 可独立 `./mvnw test` 验证后暂停
- 契约分歧一律以 `contracts/upload-contract.md` 为准修正实现
