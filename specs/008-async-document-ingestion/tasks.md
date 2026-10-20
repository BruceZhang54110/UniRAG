# Tasks: 异步文档摄取任务

**Input**: Design documents from `specs/008-async-document-ingestion/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/async-ingestion-api.md`, `quickstart.md`

**Organization**: 按 P1/P2 用户故事分阶段；集成测试任务位于其实现之前，使用真实 PostgreSQL/Flyway 验证持久化及并发行为。

## Phase 1: Setup

**Purpose**: 核实现有服务与测试边界，为异步功能的实现和验收做好准备。

- [x] T001 [P] 盘点上传收尾与一次性/分块入口的当前调用路径并记录适配点于 `specs/008-async-document-ingestion/research.md`
- [x] T002 [P] 核实 PostgreSQL 集成测试基类、Flyway 迁移约定和清理顺序于 `src/test/java/com/unirag/AbstractPostgresIntegrationTest.java`
- [x] T003 核实 Java 21、Spring Boot 3.5.16、MyBatis-Plus、Spring JDBC 与 PostgreSQL/Flyway 测试依赖于 `pom.xml`

---

## Phase 2: Foundational

**Purpose**: 建立所有故事共享的持久化模型、数据库约束、配置与版本承载能力；完成后故事可开始实现。

- [x] T004 [P] 新增 PostgreSQL Flyway 迁移，创建 `document_ingestion_job`、状态/租约/重试字段、检查约束、幂等唯一键及领取/租户索引于 `src/main/resources/db/migration/postgresql/V6__document_ingestion_jobs.sql`
- [x] T005 [P] 在同一迁移中扩展文档文件/版本快照与活动版本承载结构，确保新版本可与旧活动版本并存于 `src/main/resources/db/migration/postgresql/V6__document_ingestion_jobs.sql`
- [x] T006 [P] 在同一迁移中增加 `ingestion_run` 的 job/版本关联及按 job/pipeline 的幂等唯一约束于 `src/main/resources/db/migration/postgresql/V6__document_ingestion_jobs.sql`
- [x] T007 [P] 新增摄取 job 持久化实体及字段映射于 `src/main/java/com/unirag/infrastructure/mp/entity/DocumentIngestionJob.java`
- [x] T008 [P] 新增 job MyBatis-Plus Mapper 接口于 `src/main/java/com/unirag/infrastructure/mp/mapper/DocumentIngestionJobMapper.java`
- [x] T009 新增异步摄取配置属性，提供 maxAttempts=3、30 秒指数退避及可配置上限、租约时长和轮询参数于 `src/main/java/com/unirag/ingestion/AsyncIngestionProperties.java`
- [ ] T010 验证迁移在空数据库与现有数据库上的升级、约束和版本兼容于 `src/test/java/com/unirag/ingestion/AsyncIngestionSchemaIT.java`

**Checkpoint**: 共享 Schema/实体/配置就绪；US1、US2、US3 可按下列依赖实施。

---

## Phase 3: US1 - 上传后排队处理文档 (Priority: P1)

**Goal**: 原文和版本可靠持久化后创建或复用 job，上传请求返回 QUEUED 而不等待摄取。

**Independent Test**: 通过 MockMvc/真实 PostgreSQL 上传一次性及分块文件，确认 HTTP 202、文档和原文元数据及 job 均持久化、摄取尚未完成即返回、重复上传幂等，创建失败无孤立记录。

### Tests for User Story 1

- [ ] T011 [P] [US1] 添加真实 PostgreSQL/Flyway 上传集成测试，覆盖一次性上传异步接受、202 响应字段、原文/版本/job 原子可追踪及未同步调用摄取，于 `src/test/java/com/unirag/upload/AsyncDocumentUploadIT.java`
- [ ] T012 [P] [US1] 为分块上传收尾添加异步接受 MockMvc/集成测试，验证与一次性上传共享 QUEUED 契约且不等待摄取，于 `src/test/java/com/unirag/upload/AsyncChunkedUploadIT.java`
- [ ] T013 [P] [US1] 添加 PostgreSQL 幂等与持久化故障集成测试，覆盖同租户同版本同指纹复用 job、唯一约束竞争及失败回滚/补偿，于 `src/test/java/com/unirag/upload/AsyncUploadIdempotencyIT.java`

### Implementation for User Story 1

- [x] T014 [P] [US1] 新增上传接受响应 DTO，包含 documentId、documentName、documentVersion、jobId、QUEUED、UPLOADED、createdAt 和 statusUrl，于 `src/main/java/com/unirag/upload/dto/AsyncDocumentUploadResponse.java`
- [x] T015 [US1] 新增 job 创建/复用持久化服务，事务内关联租户、版本、指纹并处理唯一键冲突，于 `src/main/java/com/unirag/infrastructure/mp/service/DocumentIngestionJobService.java`
- [x] T016 [US1] 修改上传收尾，在保存版本原文元数据后创建或复用 job，并移除请求线程中的同步摄取调用，于 `src/main/java/com/unirag/upload/UploadService.java`
- [x] T017 [US1] 修改上传控制器使一次性上传返回 HTTP 202 与异步接受响应，于 `src/main/java/com/unirag/upload/DocumentUploadController.java`
- [x] T018 [US1] 修改分块上传完成入口调用统一异步收尾并返回相同 202 契约，于 `src/main/java/com/unirag/upload/ChunkUploadController.java`
- [x] T019 [US1] 调整上传收尾事务及 OpenDAL 补偿，确保原文、版本元数据和 job 创建失败时不确认成功且无孤儿记录，于 `src/main/java/com/unirag/upload/UploadService.java`

**Checkpoint**: 上传成功只代表 job 已接受；文档尚不可被误报为 READY。

---

## Phase 4: US2 - 查看摄取进度与可检索状态 (Priority: P1)

**Goal**: 为当前租户提供安全状态查询，区分排队、处理中、重试、就绪和最终失败。

**Independent Test**: MockMvc 查询 QUEUED/PROCESSING/RETRYING/READY/FAILED，核验字段与包装；跨租户和不存在 job 均为相同 404；响应不包含凭据或原始异常。

### Tests for User Story 2

- [ ] T020 [P] [US2] 添加 MockMvc API 契约集成测试，覆盖任务状态响应字段、ApiResponse 包装及所有公开状态，于 `src/test/java/com/unirag/upload/DocumentIngestionJobQueryIT.java`
- [ ] T021 [P] [US2] 添加真实 PostgreSQL 租户隔离测试，验证跨租户与不存在任务统一 NOT_FOUND，且查询 SQL 使用 TenantContext 于 `src/test/java/com/unirag/upload/DocumentIngestionJobTenantIsolationIT.java`
- [ ] T022 [P] [US2] 添加失败信息安全测试，验证查询响应不泄漏 API key、数据库密码、连接串、异常堆栈或未筛选配置，于 `src/test/java/com/unirag/upload/DocumentIngestionJobSensitiveInfoIT.java`

### Implementation for User Story 2

- [x] T023 [P] [US2] 新增任务状态查询响应 DTO，固定状态、阶段、重试时间、时间戳和安全 error 字段于 `src/main/java/com/unirag/upload/dto/DocumentIngestionJobStatusResponse.java`
- [x] T024 [US2] 在 job 持久化服务增加按 job/document/tenant 查询及统一不可见语义，于 `src/main/java/com/unirag/infrastructure/mp/service/DocumentIngestionJobService.java`
- [x] T025 [US2] 新增状态查询应用服务，映射内部实体为契约响应并仅输出安全错误摘要，于 `src/main/java/com/unirag/upload/DocumentIngestionJobQueryService.java`
- [x] T026 [US2] 新增 GET `/api/v1/documents/{documentId}/ingestion-jobs/{jobId}` 控制器，使用 TenantContext 并返回 200/统一 404，于 `src/main/java/com/unirag/upload/DocumentIngestionJobController.java`
- [x] T027 [US2] 为统一未找到错误添加稳定错误码映射且保持异常细节收敛，于 `src/main/java/com/unirag/upload/support/GlobalExceptionHandler.java`

**Checkpoint**: 上传方可依据稳定查询契约区分文件已存储与版本可检索。

---

## Phase 5: US3 - 自动重试并安全替换文档版本 (Priority: P2)

**Goal**: worker 以 PostgreSQL 原子领取和租约执行 job，复用现有 IngestionService；支持可恢复重试、进程恢复、幂等执行，并在新版本 READY 后才切换活动版本。

**Independent Test**: 真实 PostgreSQL 并发 worker 对同一队列领取不重复；故障注入验证退避/耗尽、过期租约恢复和 token 旧写保护、重投无重复 run/chunk/vector；替换失败旧版本仍可检索，成功后才切换和清理。

### Tests for User Story 3

- [x] T028 [P] [US3] 添加真实 PostgreSQL `FOR UPDATE SKIP LOCKED` 多 worker 并发领取测试，证明同一 job 最多一个领取者且不同 worker 可领取不同 job，于 `src/test/java/com/unirag/ingestion/DocumentIngestionJobClaimIT.java`
- [x] T029 [P] [US3] 添加真实 PostgreSQL worker 集成测试，覆盖暂时失败的指数退避、到期重试、最大次数后 FAILED 及失败后恢复 READY，于 `src/test/java/com/unirag/ingestion/DocumentIngestionWorkerUS3IT.java`
- [x] T030 [P] [US3] 添加租约恢复集成测试，模拟处理中 worker 中断、租约过期、重启恢复与过期 lease token 不能覆盖新领取结果，于 `src/test/java/com/unirag/ingestion/DocumentIngestionJobClaimIT.java`
- [x] T031 [P] [US3] 添加 worker 幂等执行测试，验证重复领取/执行不产生重复 ingestion_run、切片或向量，于 `src/test/java/com/unirag/ingestion/DocumentIngestionWorkerUS3IT.java`
- [x] T032 [P] [US3] 添加真实 PostgreSQL 版本安全切换测试，验证新版本处理中/失败时旧版本可检索，只有新版本 READY 后才切换并清理旧版，于 `src/test/java/com/unirag/ingestion/DocumentIngestionWorkerUS3IT.java`
- [ ] T033 [P] [US3] 添加 worker 最终失败及日志/状态敏感信息集成测试，确认错误摘要清洗且不记录凭据或原始异常细节，于 `src/test/java/com/unirag/ingestion/DocumentIngestionSensitiveInfoIT.java`

### Implementation for User Story 3

- [x] T034 [US3] 新增基于 Spring JDBC 的 job 领取 DAO，在事务内用 `FOR UPDATE SKIP LOCKED` 选择到期 QUEUED/RETRYING job、写入 token/lease 并增加 attempt_count，于 `src/main/java/com/unirag/ingestion/DocumentIngestionJobClaimRepository.java`
- [x] T035 [US3] 在 job 服务实现带 tenant/job/status/lease token 条件的成功、失败和重试状态迁移，于 `src/main/java/com/unirag/infrastructure/mp/service/DocumentIngestionJobService.java`
- [x] T036 [US3] 新增重试策略服务，依据快照最大次数及 30 秒指数退避上限判定 RETRYING/FAILED 并清洗错误码/摘要，于 `src/main/java/com/unirag/ingestion/DocumentIngestionRetryPolicy.java`
- [x] T037 [US3] 新增 worker 协调服务，按领取结果复用 IngestionService 执行既有管线并以 job/version 关联摄取记录，于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorker.java`
- [x] T038 [US3] 新增调度配置与周期性领取入口，使用配置控制轮询/批量，不引入外部消息代理，于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorkerScheduler.java`
- [x] T039 [US3] 新增租约恢复服务，将过期 PROCESSING 任务恢复为可调度状态且保留有效尝试语义，于 `src/main/java/com/unirag/ingestion/DocumentIngestionJobClaimRepository.java`
- [x] T040 [US3] 将租约恢复接入应用启动和周期扫描并避免启动恢复与领取竞争，于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorkerScheduler.java`
- [x] T041 [US3] 为 IngestionService 增加 job/version 关联与重复执行保护，确保重试复用管线但不重复创建有效 run/chunk/vector，于 `src/main/java/com/unirag/ingestion/IngestionService.java`
- [x] T042 [US3] 在成功终结流程中仅当全部启用管线满足 READY 条件时原子切换活动版本并安排旧版本停用/清理，于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorker.java`
- [x] T043 [US3] 在失败及重试流程中保留旧活动版本、原文和向量，并拒绝过期 token 或旧版本结果覆盖新活动版本，于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorker.java`

**Checkpoint**: 队列具备持久领取、重试与崩溃恢复；版本切换仅在 READY 后发生。

---

## Final Polish: Cross-Cutting Concerns

**Purpose**: 在完整交付上验证安全、回归、性能及 quickstart 验收路径。

- [x] T044 [P] 审核异步任务新增日志和 API 错误映射，确保只记录稳定标识/错误码且不暴露异常细节于 `src/main/java/com/unirag/ingestion/DocumentIngestionWorker.java`
- [ ] T045 [P] 扩展配置卫生测试，验证异步 worker 配置不记录或序列化敏感值于 `src/test/java/com/unirag/ConfigHygieneTest.java`
- [x] T046 执行 quickstart 所列端到端场景及标准测试命令，修正兼容性回归于 `specs/008-async-document-ingestion/quickstart.md`
- [x] T047 验证最终变更无空白错误、完整任务标记和迁移从现有 PostgreSQL 数据升级成功于 `specs/008-async-document-ingestion/tasks.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 无前置依赖；核对当前工程和测试约定。
- **Phase 2 Foundational**: 依赖 Setup；迁移、共享实体和配置完成后才开始用户故事实现。US1/US2 测试可在 Foundation 后先行编写；US3 测试依赖 job 表及版本模型的测试基座。
- **Phase 3 US1**: 依赖 Foundation；提供 job 创建及上传入口，是运行端到端 worker 的前置条件。
- **Phase 4 US2**: 依赖 Foundation；状态查询可独立于 US1 的上传控制器开发，但端到端查询已创建 job 需要 US1 或测试数据准备器。
- **Phase 5 US3**: 依赖 Foundation 和 US1 的 job 创建/版本快照语义；worker 可与 US2 并行实现，但完整验收需 US2 查询 API。
- **Final Polish**: 依赖所有目标故事完成。

### User Story Dependencies

- **US1 (P1)**: Foundation 后可独立实现，MVP 的唯一必需故事。
- **US2 (P1)**: Foundation 后可独立实现；与 US1 共享 job schema/service，建议在 job 创建服务接口确定后并行。
- **US3 (P2)**: Foundation 后开始测试/worker 核心；依赖 US1 创建的版本/job 数据进入端到端流程。与 US2 可并行开发，最终集成依赖 US1。

### Parallel Opportunities

- Setup: T001 与 T002 可并行；T003 独立核对依赖。
- Foundation: T004、T005、T006 同属一个迁移文件，应由同一任务执行并统一验证；T007/T008 可并行开发；T009 可并行；T010 在迁移可用后执行。
- US1: T011、T012、T013 可并行编写测试；T014 可与 T011-T013 并行；修改同一 `UploadService.java` 的 T016/T019 应合并顺序实施，不并行。
- US2: T020、T021、T022 及 T023 可并行；T024 完成后 T025/T026 按服务依赖推进。
- US3: T028-T033 可并行编写/运行；T034/T036 可在不同文件并行；T037 依赖 claim/status/retry API；T038/T039 可分别实现后由 T040 集成；版本切换/保护 T042/T043 应在 T041 的 job/version 执行语义明确后完成。

### Parallel Execution Examples

**User Story 1**

```text
并行：T011 AsyncDocumentUploadIT、T012 AsyncChunkedUploadIT、T013 AsyncUploadIdempotencyIT、T014 AsyncDocumentUploadResponse
串行：T015 job 创建服务 -> T016 UploadService 异步收尾 -> T017/T018 控制器适配 -> T019 事务/补偿核验
```

**User Story 2**

```text
并行：T020 查询契约测试、T021 租户隔离测试、T022 敏感信息测试、T023 响应 DTO
串行：T024 tenant-scoped 查询 -> T025 映射服务 -> T026 查询控制器 -> T027 统一错误码
```

**User Story 3**

```text
并行：T028 SKIP LOCKED 并发测试、T029 重试测试、T030 租约恢复测试、T031 幂等测试、T032 版本切换测试、T033 敏感信息测试
并行实现：T034 Spring JDBC claim repository 与 T036 重试策略；T038 调度入口与 T039 恢复服务
串行集成：T035 状态迁移 -> T037 worker 复用 IngestionService -> T040 恢复接线 -> T041 执行幂等 -> T042/T043 版本保护和切换
```

## Implementation Strategy

### MVP First (US1)

1. 完成 Phase 1 Setup 和 Phase 2 Foundational，确保 PostgreSQL/Flyway schema 与异步 job 基础可用。
2. 完成 Phase 3 US1：上传保存原文/版本并创建或复用 job，立即返回 202/QUEUED。
3. 以真实 PostgreSQL/Flyway 和 MockMvc 独立验证上传原子性、幂等、一次性及分块上传接受语义；先交付可排队 MVP，不将 QUEUED 表述为 READY。
4. 再交付 US2 查询能力，最后交付 US3 worker、自动恢复、重试及安全版本切换。

### Incremental Delivery

1. Foundation -> US1：可可靠接受上传并持久排队。
2. US1 + US2：调用方可查看处理状态和安全失败信息。
3. US1 + US2 + US3：任务自动摄取、失败重试、重启恢复，并在新版本 READY 后安全切换。

### Tests and Verification

所有测试任务针对真实 PostgreSQL/Flyway 与 Spring Boot/MockMvc 测试基类；按 quickstart 的 Maven Wrapper 命令执行，不用 H2 替代 PostgreSQL 锁语义，也不跳过集成测试。
