---
description: "Task list for 检索结果重排序（Reranking）"
---

# Tasks: 检索结果重排序（Reranking）

**Input**: Design documents from `/specs/011-add-reranking/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: 本 feature **必须包含测试** —— spec FR-010 要求全部行为可在 `./mvnw test` 中以真实数据库集成测试验证，SC-006 要求无外部模型凭据环境全绿。测试小节不是可选项。

**Organization**: 任务按 user story 分组，每个 story 可独立验证；Foundational 阶段先打通共享模型、资源、扩展与观测。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3）
- 每个任务含精确文件路径

## Path Conventions

- 后端主代码：`src/main/java/com/unirag/`
- 后端测试：`src/test/java/com/unirag/`
- 资源与迁移：`src/main/resources/`
- 前端：`web/src/`（本 feature 不改动）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 建立迁移、配置与共享枚举

- [X] T001 [P] 新增 Flyway `src/main/resources/db/migration/postgresql/V9__retrieval_observation_detail.sql`：`ALTER TABLE retrieval_observation ADD COLUMN IF NOT EXISTS detail VARCHAR(2000);`（见 data-model.md §2）
- [X] T002 [P] 在 `src/main/resources/application.yml` 增加 `unirag.retrieval.rerank.*` 配置段（enabled/shadow/recall-top-k/min-score，默认值见 contracts/rerank-model.md §6）
- [X] T003 [P] 新增 `src/main/java/com/unirag/retrieval/rerank/RerankProperties.java`：`@ConfigurationProperties(prefix="unirag.retrieval.rerank")`，校验 `recall-top-k` 1..50、`min-score` 非有限值启动失败
- [X] T004 [P] 在 `src/main/java/com/unirag/retrieval/pipeline/StageName.java` 增加 `RERANK`；新增 `src/main/java/com/unirag/retrieval/rerank/RerankOutcome.java`（candidates/kept/topScore/durationMs/status/reason/shadow/detail）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 共享模型、租户资源、ScoringModel 适配器、流水线扩展与观测底座

**⚠️ CRITICAL**: 本阶段完成前，US1/US2/US3 都无法验证

- [X] T005 扩展 `src/main/java/com/unirag/retrieval/model/RetrievedChunk.java`：新增可空 `Double rerankScore` + 7 参兼容构造器 + `withRerankScore(double)`；更新 `src/main/java/com/unirag/retrieval/web/ChunkView.java` 增加可空 `rerankScore`
- [X] T006 [P] 扩展 `src/main/java/com/unirag/retrieval/model/RetrievalRequest.java`：新增 `int recallTopK`、`boolean rerank`、7 参规范构造器、5 参兼容构造器（`recallTopK=topK`、`rerank=false`）、`withRerank(boolean,int)`；`src/main/java/com/unirag/retrieval/model/RetrievalConstraints.java` 增加 `recallTopK` 校验
- [X] T007 [P] 扩展 `src/main/java/com/unirag/retrieval/model/QaRequest.java`：新增可空 `Boolean rerank` + 5 参兼容构造器；扩展 `src/main/java/com/unirag/retrieval/web/RetrieveRequest.java`、`AskRequest.java` 增加可空 `Boolean rerank` + 5 参兼容构造器
- [X] T008 [P] 在 `src/main/java/com/unirag/tenant/resource/TenantResourceRouter.java` 增加 `hasPrimary(String, ResourceType)`；新增 `src/main/java/com/unirag/retrieval/rerank/RerankSupport.java`（`enabled && hasPrimary`）
- [X] T009 新增 `src/main/java/com/unirag/retrieval/rerank/BgeRerankerScoringModel.java`：实现 `ScoringModel.scoreAll`，调用 `/rerank`、按 `index` 回填、分批、超时、缺失/越界/非有限分数抛异常
- [X] T010 [P] 新增 `src/main/java/com/unirag/tenant/resource/provider/RerankModelResourceProvider.java`：`RERANK_MODEL` → `ScoringModel`，配置 `base-url`/`api-key`/`model-name`/`timeout`/`max-documents-per-batch`
- [X] T011 新增 `src/main/java/com/unirag/retrieval/rerank/RerankEnhancement.java`：`MERGE` 阶段 `EnhancementExtension`（FAIL_OPEN），排序/阈值/截断，失败/无模型回退到 `topK`，写 `ctx.attributes` 的 `RerankOutcome`
- [X] T012 [P] 扩展 `src/main/java/com/unirag/infrastructure/mp/entity/RetrievalObservation.java` 增加 `detail`；`ObservationRecorder.java` 增加 `record(ctx, result, detail)` 重载与 `detail` 脱敏/截断；新增 `src/main/java/com/unirag/retrieval/observation/RerankObservationExtension.java`
- [X] T013 [P] 扩展 `src/main/java/com/unirag/retrieval/observation/ObservationMetrics.java`：新增 `unirag.rerank.requests` 与 `unirag.rerank.duration`
- [X] T014 更新 `src/main/java/com/unirag/retrieval/stage/VectorSearchStage.java`（`maxResults=recallTopK`）与 `MergeStage.java`（归并上限=recallTopK）
- [X] T015 更新 `src/main/java/com/unirag/retrieval/RetrievalService.java`、`QaService.java`：解析 `rerank`/`recallTopK` 并构造请求；更新 `RetrievalController.java`/`QaController.java` 透传请求字段

**Checkpoint**: 共享链路可编译，启用重排的租户可走通；未配置租户行为不变

---

## Phase 3: User Story 1 - 问答只引用真正相关的片段 (Priority: P1) 🎯 MVP

**Goal**: 启用重排后，问答引用排除弱相关片段，相关片段保留并排在前面。

**Independent Test**: 替身 `ScoringModel` 下，`RerankApiIT` 断言弱相关文档不进引用、相关文档保留；未配置租户响应不变。

### Tests for User Story 1

- [X] T016 [P] [US1] 新增 `src/test/java/com/unirag/retrieval/rerank/RerankEnhancementTest.java`：排序/截断/阈值/shadow/失败回退/无模型跳过/确定性兜底
- [X] T017 [P] [US1] 新增 `src/test/java/com/unirag/retrieval/RerankApiIT.java` 的问答用例：替身 `ScoringModel` 下 `/ask` 引用只含重排保留片段；无模型租户行为不变

### Implementation for User Story 1

- [X] T018 [US1] 在 `RerankApiIT` 中补 `@MockBean TenantResourceRouter` 替身装配：`hasPrimary=true`、`getPrimary(RERANK_MODEL)` 返回 `ScoringModel` 替身（复用 `AbstractRetrievalIT` 新增 `stubRerankModel`）
- [X] T019 [US1] 验证 `RerankEnhancement` 与 `RerankObservationExtension` 在 QA 链路中的执行顺序与 fallback 截断（依赖 T011/T012/T015）

**Checkpoint**: US1 可独立验证——问答引用质量由替身重排驱动

---

## Phase 4: User Story 2 - 检索接口返回重排后的结果 (Priority: P2)

**Goal**: `/retrieve` 支持 `rerank` 开关；启用时按 `rerankScore` 排序并截断，响应新增可空 `rerankScore`。

**Independent Test**: `RerankApiIT` 断言 `/retrieve` 顺序、`topK`、`rerankScore`、显式关闭、未配置租户行为不变。

### Tests for User Story 2

- [X] T020 [P] [US2] 在 `RerankApiIT` 增加 `/retrieve` 用例：`rerank=true` 顺序/字段、`rerank=false` 原顺序、未配置租户无 `RERANK` 行
- [X] T021 [P] [US2] 新增 `src/test/java/com/unirag/retrieval/rerank/BgeRerankerScoringModelTest.java`：本地 HTTP 桩覆盖 `index` 回填、分批、缺失分数、非 2xx、超时

### Implementation for User Story 2

- [X] T022 [US2] 更新 `src/main/java/com/unirag/retrieval/web/RetrieveResponse.java` / `ChunkView.java` 映射 `rerankScore`；确保未重排时为 `null`
- [X] T023 [US2] 更新 `RetrievalController`/`RetrieveRequest.toModel` 透传 `rerank`；`RetrievalService` 解析实际值（依赖 T006/T008/T015）

**Checkpoint**: US2 可独立验证——检索接口按重排结果返回

---

## Phase 5: User Story 3 - 运维可观测且可安全降级 (Priority: P3)

**Goal**: `RERANK` 观测行、`detail`、指标、shadow、失败降级与凭据零泄漏。

**Independent Test**: `RerankApiIT` 断言 `RERANK` 行字段、shadow 响应不变、失败回退、无 key/原文；指标端点可采集。

### Tests for User Story 3

- [X] T024 [P] [US3] 在 `RerankApiIT` 增加观测用例：`SUCCESS` 行 `hit_count/item_count/detail`、`shadow` 行 `target_key=shadow`、超时 `DEGRADED` 且主链路 200
- [X] T025 [P] [US3] 租户隔离：复用既有 `RetrievalTenantIsolationIT`/`QaTenantIsolationIT`，并在 `RerankApiIT` 覆盖无重排租户不产生 `RERANK` 行；双 rerank 租户专用用例未新增（路由按 `tenantCode` 解析，数据隔离由既有用例守门）

### Implementation for User Story 3

- [X] T026 [US3] 完成 `RerankObservationExtension` 的 `detail` 组装与 `ObservationMetrics.recordRerank` 调用；确保 `unirag.observability.enabled=false` 时不落库但指标仍可用
- [X] T027 [US3] 检查日志/错误路径：`RerankEnhancement`/适配器不得输出 key、Authorization、片段原文；失败原因经 `Redaction`

**Checkpoint**: US3 可独立验证——重排可观测、可灰度、可降级

---

## Phase 6: Polish & Cross-Cutting

- [X] T028 更新 `specs/011-add-reranking/contracts/*` 与 `quickstart.md` 中与实际实现不一致的细节（如类名/字段/命令）
- [X] T029 运行分层目标与全量测试：`UNIRAG_RETRIEVAL_LOG_LEVEL=info ./mvnw -q test` 通过；默认 DEBUG 下既有 `ObservationRedactionIT` 仍受工作区旧日志影响（非本 feature 引入）
- [X] T030 运行 `./mvnw -q -DskipTests compile`（通过）；本 feature 未改前端，`npm run test` 按计划跳过

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1/US2/US3（可并行）→ Phase 6
- T005/T006/T007 先于 T011/T015；T008/T009/T010 先于 T011；T012/T013 依赖 T004/T011
- 同一文件的任务必须串行：`RerankEnhancement`、`RerankObservationExtension`、`RerankApiIT`、`application.yml`
- 测试可先写并确认失败，再实现对应生产代码

## Implementation Strategy

1. 先完成 Phase 1 + Phase 2，使链路可编译且未配置租户行为不变。
2. 先交付 US1（问答引用质量），再 US2（检索接口），最后 US3（观测/灰度）。
3. 每个 checkpoint 运行对应测试；Phase 6 跑全量 `./mvnw test`。
4. 既有测试在没有 `RERANK_MODEL` 时必须保持绿色（`hasPrimary=false`）。
