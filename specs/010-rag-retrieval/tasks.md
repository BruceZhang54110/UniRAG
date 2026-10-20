---

description: "Task list for RAG 检索问答与全链路可观测"
---

# Tasks: RAG 检索问答与全链路可观测

**Input**: Design documents from `/specs/010-rag-retrieval/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: 本 feature **必须包含测试** —— spec FR-010 明确要求"全部行为 MUST 可在 `./mvnw test` 中以真实数据库集成测试验证"，SC-006 要求"全部自动化测试在无外部模型凭据环境可运行且全绿"。因此各 User Story 的测试小节**不是可选项**。

**Organization**: 任务按 user story 分组，每个 story 可独立实现与独立验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3）
- 每个任务均含精确文件路径

## Path Conventions

- 后端主代码：`src/main/java/com/unirag/`
- 后端测试：`src/test/java/com/unirag/`
- 资源与迁移：`src/main/resources/`
- 前端：`web/src/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 解除治理阻断、建立本期所需的表与配置

- [X] T001 解除 gitignore 对 `specs/` 与 `.specify/` 的排除（`.gitignore` 加反向放行 / 清理全局忽略），使本 feature 的 spec/plan/tasks/contracts 可进版本控制 —— 宪法"开发工作流与质量门"的硬性前置，不做则本 feature 全部文档产物无法提交
- [X] T002 [P] 新增 Flyway 迁移 `src/main/resources/db/migration/postgresql/V8__retrieval_observation.sql`（`retrieval_observation` 表 + `idx_obs_trace` / `idx_obs_time` / `idx_obs_stage` 三个索引，列定义见 data-model.md §1）
- [X] T003 [P] 新增观测配置属性类 `src/main/java/com/unirag/retrieval/observation/ObservationProperties.java`（`unirag.observability.enabled`、`retention` 默认 30 天、清理间隔），并在 `src/main/resources/application.yml` 增加对应配置段与注释

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 阶段流水线骨架、扩展机制、观测落库底座 —— 所有 user story 的共同前置

**⚠️ CRITICAL**: 本阶段完成前，任何 user story 都无法开始

- [X] T004 定义阶段与流水线契约：`Stage`、`StageName`、`StageContext`、`StageOutcome` 于 `src/main/java/com/unirag/retrieval/pipeline/`（签名见 contracts/pipeline-extension.md §2）
- [X] T005 [P] 定义两类扩展契约与失败策略：`ObservationExtension`、`EnhancementExtension`、`FailurePolicy` 于 `src/main/java/com/unirag/retrieval/pipeline/`（见 contracts/pipeline-extension.md §3，含默认策略表 §4）
- [X] T006 实现流水线编排 `DefaultPipeline` 于 `src/main/java/com/unirag/retrieval/pipeline/DefaultPipeline.java`：有序执行阶段；观察型在阶段前后各执行一次（看到的是阶段最终输入/输出）；增强型按 `order()` 升序执行且 `order()` 重复时按 Bean 名兜底排序；任一扩展异常按各自 `failurePolicy()` 处理且 MUST NOT 冒泡到 Controller
- [X] T007 [P] 新增观测持久层（复用既有 `infrastructure.mp` 三层，不新建持久化层）：`src/main/java/com/unirag/infrastructure/mp/entity/RetrievalObservation.java`、`mapper/RetrievalObservationMapper.java`、`service/RetrievalObservationService.java` 与 `service/impl/RetrievalObservationServiceImpl.java`
- [X] T008 [P] 新增领域模型于 `src/main/java/com/unirag/retrieval/model/`：`RetrievalRequest`、`RetrievedChunk`、`QaRequest`、`QaAnswer`、`StageResult`（字段见 data-model.md §5）
- [X] T009 实现观测记录器 `src/main/java/com/unirag/retrieval/observation/ObservationRecorder.java`：best-effort 写库（异常被捕获、降级为进程日志、并尝试记录降级事件本身），MUST NOT 影响主链路；`error_message` 截断 500 且 MUST NOT 含密钥
- [X] T010 [P] 实现指标 `src/main/java/com/unirag/retrieval/observation/ObservationMetrics.java`（Micrometer；指标与标签见 research.md R10，标签 MUST NOT 使用 docName/问题文本等高基数字段）
- [X] T011 把观测接为观察型扩展 `src/main/java/com/unirag/retrieval/observation/RecordingObservationExtension.java`（依赖 T005、T009；证明"接入观测无需改阶段实现"）
- [X] T012 在 `src/main/java/com/unirag/upload/support/GlobalExceptionHandler.java` 的 `statusOf` switch 中登记本期新增错误码：`GRANULARITY_NOT_FOUND`→400、`LLM_UNAVAILABLE`→502、`LLM_EMPTY_RESPONSE`→502（未登记会退化为 500）
- [X] T013 [P] 编写扩展机制契约测试 `src/test/java/com/unirag/retrieval/PipelineExtensionContractIT.java`，逐条覆盖 contracts/pipeline-extension.md §7 的 5 项可验证要点（用测试专用两阶段流水线）

**Checkpoint**: 流水线与扩展机制可用、观测可落库 —— user story 可以开始

---

## Phase 3: User Story 1 - 租户级向量检索服务 (Priority: P1) 🎯 MVP

**Goal**: 用租户自己的嵌入模型把查询向量化，在指定或全部已启用管线的向量表里做相似度检索 + 元数据过滤，跨表确定性归并取 topK，返回带来源元数据的片段。

**Independent Test**: 两租户各摄取若干文档后，用确定性嵌入替身发起检索，断言命中本租户片段、分数降序、来源元数据完整、粒度过滤生效、跨租户查不到对方内容、重复请求结果逐字一致。

### Tests for User Story 1

> **NOTE: 先写测试并确认其失败，再进入实现**

- [X] T014 [P] [US1] 编写 `src/test/java/com/unirag/retrieval/RetrievalServiceIT.java`：topK 生效、分数降序、来源元数据（docName/pipelineKey/chunkIndex/text）完整、**同输入结果逐字一致**（FR-003 确定性）、`documentId` 过滤生效（FR-002）
- [X] T015 [P] [US1] 编写 `src/test/java/com/unirag/retrieval/RetrievalTenantIsolationIT.java`：两租户相似主题文档，同一 query 分别检索，断言各自只返回本租户片段（SC-002，泄漏用例为 0）
- [X] T016 [P] [US1] 编写 `src/test/java/com/unirag/retrieval/RetrievalGranularityIT.java`：指定存在的粒度只检索对应表；指定不存在的粒度返回 `GRANULARITY_NOT_FOUND` 且 `details.available` 列出可用粒度（**不得**静默退化为全量）；租户无任何启用管线时返回空结果不报错
- [X] T017 [P] [US1] 编写 `src/test/java/com/unirag/retrieval/RetrievalApiIT.java`：HTTP 层契约 —— 空 query / 超长 query / `topK` 越界返回 `VALIDATION_FAILED`；缺 `X-Tenant-Code` 返回 `TENANT_CODE_REQUIRED`；响应信封为 `{"data": ...}`

### Implementation for User Story 1

- [X] T018 [US1] 实现 `src/main/java/com/unirag/retrieval/stage/QueryEmbeddingStage.java`：经 `TenantResourceRouter` 取该租户 `EMBEDDING_MODEL` 并把查询向量化（依赖 T004）
- [X] T019 [US1] 实现 `src/main/java/com/unirag/retrieval/stage/VectorSearchStage.java`：解析目标管线（指定粒度或全部已启用）→ 逐表经 Router 取 `EMBEDDING_STORE` → 构造 `EmbeddingSearchRequest`（`maxResults=topK`、`minScore`、元数据 `Filter`）。**过滤的数值键 MUST 传 `Long`/`Integer`**（见 research.md R5），单表失败按 FR-009 隔离记录并继续（依赖 T004、T018）
- [X] T020 [US1] 实现 `src/main/java/com/unirag/retrieval/stage/MergeStage.java`：按 `score` 降序、同分以 `(documentId, pipelineKey, chunkIndex)` 升序兜底，截断到全局 topK，**不做跨表去重**（research.md R4）（依赖 T019）
- [X] T021 [US1] 实现 `src/main/java/com/unirag/retrieval/RetrievalService.java`：编排三段流水线、生成 traceId、接入观测记录与指标，返回 `chunks` + `searchedTables` + `failedTables`（依赖 T006、T020）
- [X] T022 [US1] 实现 `src/main/java/com/unirag/retrieval/web/RetrievalController.java` 与请求/响应 DTO：`POST /api/v1/retrieve`，参数校验与错误码按 contracts/retrieval-api.md §2、§5（依赖 T012、T021）

**Checkpoint**: US1 可独立验证 —— `POST /api/v1/retrieve` 可用，检索结果正确且租户隔离

---

## Phase 4: User Story 2 - RAG 问答接口 (Priority: P2)

**Goal**: 检索片段 → 组装带引用标注的提示词 → 调租户自己的聊天模型生成回答 → 返回回答与引用清单；检索为空 / 模型不可达有明确降级语义。含最小前端界面（FR-013）。

**Independent Test**: 用确定性检索结果 + 替身聊天模型提问，断言返回回答与引用清单、替身模型收到的提示词含检索片段与来源标注；检索为空时返回明确降级而非编造；模型失效时返回可读错误。前端 `/chat` 覆盖加载/空结果/失败三态。

### Tests for User Story 2

- [X] T023 [P] [US2] 编写 `src/test/java/com/unirag/retrieval/QaServiceIT.java`：替身聊天模型断言**收到的提示词包含检索片段与来源标注**；响应含 `answer` 与 `citations`；`citations` 顺序与提示词引用标注一致（依赖 T021 的检索结果）
- [X] T024 [P] [US2] 编写 `src/test/java/com/unirag/retrieval/QaDegradationIT.java`：检索为空 → `degraded=true`、`degradeReason=NO_CONTEXT`、`citations=[]` 且**不调用模型**；模型不可达 → `LLM_UNAVAILABLE`；模型返回空 → `LLM_EMPTY_RESPONSE`（SC-005）
- [X] T025 [P] [US2] 编写 `src/test/java/com/unirag/retrieval/QaTenantIsolationIT.java`：同一问题分别向两租户提问，回答与引用均不出本租户（SC-002）
- [X] T026 [P] [US2] 编写前端三态测试 `web/src/features/chat/__tests__/ChatPage.test.tsx`：加载态、空结果态（`degradeReason=NO_CONTEXT`）、失败态（依赖 T032）

### Implementation for User Story 2

- [X] T027 [US2] 实现 `src/main/java/com/unirag/retrieval/stage/PromptAssemblyStage.java`：把片段组装为带引用标注的提示词（含来源：docName/pipelineKey/chunkIndex），供 `citations` 回填顺序（依赖 T020）
- [X] T028 [US2] 实现 `src/main/java/com/unirag/retrieval/stage/ChatGenerationStage.java`：经 Router 取该租户 `OpenAiChatModel`，用 `chat(ChatRequest)` 生成（注意 1.20.0 无 `ChatLanguageModel`/`generate`，字段名 `maxOutputTokens`，见 research.md R8）（依赖 T027）
- [X] T029 [US2] 实现 `src/main/java/com/unirag/retrieval/QaService.java`：编排五段流水线 + 降级语义（NO_CONTEXT 时**不调用模型**）+ 观测（依赖 T028）
- [X] T030 [US2] 实现 `src/main/java/com/unirag/retrieval/web/QaController.java` 与 DTO：`POST /api/v1/ask`（依赖 T012、T029）
- [X] T031 [US2] 新增前端客户端 `web/src/api/retrieval.ts`：`ask()` 调 `POST /api/v1/ask`，复用 `web/src/api/client.ts` 的解包与 `errors.ts` 的错误模型（依赖 T030）
- [X] T032 [US2] 实现 `web/src/features/chat/ChatPage.tsx`：替换 `ChatPlaceholderPage.tsx`（提交问题、展示回答与引用片段、加载/空结果/失败三态），文案走 `web/src/content/zh-CN.ts`，并在 `web/src/app/router.tsx` 换绑路由（依赖 T031）

**Checkpoint**: US1 与 US2 均可独立工作 —— `POST /api/v1/ask` 返回回答与引用，前端 `/chat` 可交互

---

## Phase 5: User Story 3 - 全链路可观测 (Priority: P3)

**Goal**: 一次调用的每个阶段都有结构化记录，以同一标识串联；摄取侧（005）记录在同一入口可查；聚合指标可被标准监控端点采集。

**Independent Test**: 构造一次完整问答后，断言各阶段记录齐备且同 trace；用统一入口能查到 005 的摄取记录；指标准确暴露；记录与日志中查不到任何密钥。

### Tests for User Story 3

- [X] T033 [P] [US3] 编写 `src/test/java/com/unirag/retrieval/ObservationChainIT.java`：一次问答产生完整阶段链（QUERY_EMBEDDING/VECTOR_SEARCH/MERGE/PROMPT_ASSEMBLY/CHAT_GENERATION），同 `traceId` 串联，断链用例为 0（SC-003）
- [X] T034 [P] [US3] 编写 `src/test/java/com/unirag/retrieval/ObservationUnifiedQueryIT.java`：统一入口能查到 005 摄取侧记录（按 `documentId` / `stage=INGEST` 过滤）（research.md R3）
- [X] T035 [P] [US3] 编写 `src/test/java/com/unirag/retrieval/ObservationRedactionIT.java`：观测记录与日志中不含 api-key / 密码 / 配置原文（FR-008、SC-006）
- [X] T036 [P] [US3] 编写 `src/test/java/com/unirag/retrieval/ObservationRetentionIT.java`：过期记录被清理任务删除、未过期保留；清理失败不影响主链路
- [X] T037 [P] [US3] 编写 `src/test/java/com/unirag/retrieval/ObservationMetricsIT.java`：检索量与延迟、问答量与延迟/失败率、降级计数可在 actuator 指标端点取到且含租户维度

### Implementation for User Story 3

- [X] T038 [US3] 实现观测统一查询 SQL `src/main/resources/mapper/RetrievalObservationMapper.xml`：以 `retrieval_observation` 为基准**联合投影**既有 `ingestion_run`，使摄取侧记录在同一入口可查（摄取侧代码零改动，research.md R3）（依赖 T007）
- [X] T039 [US3] 实现 `src/main/java/com/unirag/retrieval/web/ObservationController.java` 与查询 DTO：`GET /api/v1/observations`，过滤/分页按 contracts/retrieval-api.md §4，严格租户隔离（依赖 T012、T038）
- [X] T040 [US3] 实现留存清理定时任务 `src/main/java/com/unirag/retrieval/observation/ObservationRetentionJob.java`：按 `unirag.observability.retention` 物理删除过期行，清理量入日志与指标（依赖 T003、T007）
- [X] T041 [US3] 在 `src/main/resources/application.yml` 暴露所需 actuator 端点并验证指标命名，使 SC-004 的"1 分钟内定位失败阶段/原因/租户"成立（依赖 T010）

**Checkpoint**: 三个 user story 均可独立工作 —— 全链路可观测闭环

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: 跨 story 的收尾与验收

- [X] T042 [P] 按 `specs/010-rag-retrieval/quickstart.md` 场景 2、4 执行端到端手工验证（真实模型检索→问答→观测查询→跨租户隔离），记录实测结论
- [X] T043 [P] 更新 `README.md`：新增三个接口与观测配置说明，并写明扩展点接入方式（"加一个观察型扩展即可接入日志/追踪，无需改阶段实现"）
- [X] T044 全量校验：`./mvnw test`（含 `*IT`，真实 PostgreSQL + pgvector）与 `cd web && npm run test` 均绿灯；确认 `specs/` 产物已可被 git 跟踪（T001 的验收入口）
- [X] T045 复评宪法合规（原则 I 无冗余堆砌、III 真实链路验证、V 敏感信息零泄漏），在 `specs/010-rag-retrieval/plan.md` 的 Constitution Check 后补记复评结论

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始；**T001 是治理前置，应最先做**
- **Foundational (Phase 2)**: 依赖 Setup 完成 —— **阻塞全部 user story**
- **User Stories (Phase 3–5)**: 均依赖 Foundational 完成；之后可并行，或按 P1 → P2 → P3 顺序推进
- **Polish (Phase N)**: 依赖所需 user story 完成

### User Story Dependencies

- **US1 (P1)**: Foundational 后可开始，不依赖其他 story
- **US2 (P2)**: Foundational 后可开始；复用 US1 的检索结果，但测试用确定性检索数据独立驱动，**可独立验收**
- **US3 (P3)**: Foundational 后可开始；其阶段链断言需要 US1/US2 的阶段存在，但观测落库与查询入口本身可独立验证

### Within Each User Story

- 测试 MUST 先写并确认失败，再进入实现
- 模型 → 服务 → 端点
- 核心实现先于集成
- 前一个 story 完成后再进入下一个优先级

### Parallel Opportunities

- Setup 中标 [P] 的任务可并行（T002、T003）
- Foundational 中标 [P] 的任务可并行（T005、T007、T008、T010、T013）
- 每个 story 的**测试任务**（均标 [P]）可并行编写
- Foundational 完成后，US1 / US2 / US3 可由不同人并行推进

---

## Parallel Example: User Story 1

```bash
# 并行编写 US1 的全部测试（不同文件）：
Task: "RetrievalServiceIT in src/test/java/com/unirag/retrieval/RetrievalServiceIT.java"
Task: "RetrievalTenantIsolationIT in src/test/java/com/unirag/retrieval/RetrievalTenantIsolationIT.java"
Task: "RetrievalGranularityIT in src/test/java/com/unirag/retrieval/RetrievalGranularityIT.java"
Task: "RetrievalApiIT in src/test/java/com/unirag/retrieval/RetrievalApiIT.java"

# 阶段实现为串行链（有依赖）：QueryEmbeddingStage → VectorSearchStage → MergeStage
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup（**T001 优先**）
2. 完成 Phase 2: Foundational（阻塞全部 story）
3. 完成 Phase 3: User Story 1
4. **STOP and VALIDATE**: 独立验证检索（`/retrieve` + 隔离用例）
5. 可交付 MVP

### Incremental Delivery

1. Setup + Foundational → 底座就绪
2. US1 → 独立验证 → 交付（MVP！检索可用）
3. US2 → 独立验证 → 交付（问答 + 前端可用）
4. US3 → 独立验证 → 交付（可观测闭环）
5. 每个 story 独立增值，不破坏前序 story

### Parallel Team Strategy

1. 团队共同完成 Setup + Foundational
2. Foundational 完成后分派：
   - 开发者 A：US1（检索）
   - 开发者 B：US2（问答 + 前端）
   - 开发者 C：US3（可观测）
3. 各 story 独立完成并集成

---

## Notes

- [P] = 不同文件、无依赖
- [Story] 标签用于追溯；Setup / Foundational / Polish 阶段**不带** story 标签
- 每个 story 应可独立完成与独立验证
- 先确认测试失败，再实现
- 每个任务或逻辑分组后提交一次
- 检查点处停下独立验证 story

### 本 feature 的三个易错点（实现时务必对照）

1. **分数范围是 `[0,1]`，不是 `[-1,1]`** —— `minScore=0.7` 等价于余弦相似度 `0.4`（research.md R6）
2. **元数据过滤的数值键必须传 `Long`/`Integer`** —— 传 `String`/`BigDecimal`/`Short` 会拼出 `::null` 导致 SQL 语法错误（research.md R5）
3. **1.20.0 没有 `ChatLanguageModel` 与 `generate()`** —— 用 `dev.langchain4j.model.chat.ChatModel#chat(ChatRequest)`，字段名 `maxOutputTokens`（research.md R8）
