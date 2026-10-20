---
description: "Task list for 查询改写（Query Rewriting）"
---

# Tasks: 查询改写（Query Rewriting）

**Input**: Design documents from `/specs/012-query-rewriting/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 本 feature **必须包含测试** —— spec FR-013 / SC-007 与宪法原则 III 要求真实数据库集成验证，且测试不得依赖外部模型凭据。测试小节不是可选项。

**Organization**: 任务按 user story 分组，每个 story 可独立实现与验证。共享的请求模型、资源、改写器与扩展放在 Foundational 阶段。

**集成测试文件说明**：为保持 US1/US2/US3 可独立测试与并行开发，计划中的单一 `QueryRewriteApiIT` 拆为 `QueryRewriteQaIT`、`QueryRewriteRetrievalIT`、`QueryRewriteObservationIT`、`QueryRewriteShadowIT` 四个类。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无未完成依赖）
- **[Story]**: 所属 user story（US1 / US2 / US3）
- 每个任务含精确文件路径

## Path Conventions

- 后端主代码：`src/main/java/com/unirag/`
- 后端测试：`src/test/java/com/unirag/`
- 配置：`src/main/resources/`
- 本 feature **无数据库迁移**，前端 `web/` 不改动

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 配置项、枚举与 Router 只读能力

- [X] T001 [P] 在 `src/main/resources/application.yml` 增加 `unirag.retrieval.query-rewrite` 配置段：`enabled`、`shadow`、`max-output-chars`、`max-output-ratio`、`max-input-chars`、`preview-chars`，默认值见 contracts/query-rewrite.md §6
- [X] T002 [P] 在 `src/main/java/com/unirag/tenant/resource/ResourceType.java` 增加 `QUERY_REWRITE_MODEL("queryRewriteModel")`
- [X] T003 [P] 在 `src/main/java/com/unirag/retrieval/pipeline/StageName.java` 增加 `QUERY_REWRITE`，并更新类注释说明它是“仅观测阶段、由 QUERY_EMBEDDING 的增强扩展执行”
- [X] T004 [P] 在 `src/main/java/com/unirag/tenant/resource/TenantResourceRouter.java` 增加只读方法 `Optional<String> primaryKey(String tenantCode, ResourceType type)`，委托 `TenantResourceIndex.primaryKey(...)`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 user story 共用的请求模型、改写资源、改写器、扩展与观测基础设施

**⚠️ CRITICAL**: US1/US2/US3 都不能在 Phase 2 完成前开始

- [X] T005 创建 `src/main/java/com/unirag/retrieval/query/QueryRewriteProperties.java`：`@ConfigurationProperties(prefix="unirag.retrieval.query-rewrite")`，校验 `maxOutputChars` 1..2000、`maxOutputRatio` 1.0..10.0 且有限、`maxInputChars` 1..2000、`previewChars` 16..500
- [X] T006 [P] 创建 `src/main/java/com/unirag/retrieval/query/QueryRewriteOutcome.java`：字段 `modelKey`、`modelSource`、`originalChars`、`retrievalChars`、`durationMs`、`status`、`reason`、`shadow`、`detail`；常量 `CONTEXT_KEY="query.rewrite.outcome"`
- [X] T007 [P] 创建 `src/main/java/com/unirag/retrieval/query/RewriteValidator.java`：按 contracts/query-rewrite.md §3.2 实现空值/多查询/多行/`max-output-chars`/`max-output-ratio`/显式约束丢失校验，返回 `Optional<String>` 原因码；约束提取覆盖数字/日期、成对引号短语、URL、反引号代码片段
- [X] T008 [P] 创建 `src/main/java/com/unirag/retrieval/query/LlmQueryTransformer.java`：实现 `dev.langchain4j.rag.query.transformer.QueryTransformer`，通过 `Supplier<ChatModel>` 取模型，使用 `rewrite-v1` 固定提示词（保留实体/数字/日期/引号/URL/语言、不回答、不编造、单行输出），返回 0/1 条 `Query`，暴露 `modelSource()`（`primary-llm` / `dedicated`）
- [X] T009 创建 `src/main/java/com/unirag/tenant/resource/provider/QueryRewriteModelResourceProvider.java`：实现 `TenantResourceProvider<QueryTransformer>`；连接字段三空 → 构造 `primary-llm` 模式（注入 `TenantResourceRouter`，Supplier 运行时解析租户主 `LLM`）；三全 → 构造专用 `OpenAiChatModel`；部分提供或 `primary-llm` 模式出现 `timeout` → 配置非法；校验 `timeout` `>0` 且 `≤PT1.5S`、`temperature` `[0,1]`、`max-tokens` `32..1000`（依赖 T002、T008）
- [X] T010 创建 `src/main/java/com/unirag/retrieval/query/QueryRewriteSupport.java`：`enabledFor(tenantCode)` = 全局开关开启且 `hasPrimary(QUERY_REWRITE_MODEL)`；`primaryKey(tenantCode)` 返回资源 key（依赖 T002、T005）
- [X] T011 在 `src/main/java/com/unirag/retrieval/model/RetrievalConstraints.java` 增加 `MAX_RETRIEVAL_QUERY_LENGTH=500` 与 `requireRetrievalQuery(String retrievalQuery, String originalQuery)`：空值取原问题；显式文本去空白、非空；与原问题不同时长度 ≤500
- [X] T012 扩展 `src/main/java/com/unirag/retrieval/model/RetrievalRequest.java`：增加 `boolean rewrite` 与 `String retrievalQuery`；保留 5 参/7 参构造器，新增 8 参/9 参构造器；增加 `withRewrite(boolean)` 与 `withRetrievalQuery(String)`，`withRerank` 保留新字段（依赖 T011）
- [X] T013 [P] 在 `src/main/java/com/unirag/retrieval/observation/ObservationMetrics.java` 增加 `recordQueryRewrite(tenantCode, status, duration)`：`unirag.query.rewrite.requests` 与 `unirag.query.rewrite.duration`
- [X] T014 创建 `src/main/java/com/unirag/retrieval/query/QueryRewriteEnhancement.java`：`EnhancementExtension`，`order=50`、`stages=QUERY_EMBEDDING`、`FAIL_OPEN`；`beforeStage` 在 `rewrite=true` 且原问题不超长时解析 `QUERY_REWRITE_MODEL`、调用 `transform`、校验、写 `QueryRewriteOutcome`、记录指标；成功且非 shadow 返回 `withRetrievalQuery`；失败/无效/资源缺失回退原输入；`afterStage` 原样返回（依赖 T005、T006、T007、T008、T010、T012、T013）
- [X] T015 修改 `src/main/java/com/unirag/retrieval/stage/QueryEmbeddingStage.java`：嵌入与日志使用 `request.retrievalQuery()`，保留 `request.query()` 供其他阶段（依赖 T012）
- [X] T016 创建 `src/main/java/com/unirag/retrieval/observation/QueryRewriteObservationExtension.java`：`stages=QUERY_EMBEDDING`、`FAIL_OPEN`，读取 outcome 落 `QUERY_REWRITE` 行；`target_key=modelKey`、`hit_count=originalChars`、`item_count=retrievalChars`、`error_message=reason`、`detail=outcome.detail`（依赖 T003、T006、T013）
- [X] T017 [P] 在 `src/test/java/com/unirag/retrieval/AbstractRetrievalIT.java` 增加测试辅助：`stubQueryTransformer(tenantCode, modelKey, rewrite)`（stub `hasPrimary`/`getPrimary`/`primaryKey`）、`stubQueryTransformerFailure`、可记录 `embed(String)` 入参的嵌入模型包装（依赖 T002、T012）
- [X] T018 编译检查：运行 `./mvnw -q -DskipTests compile` 并修复 `src/main/java/com/unirag/` 下 Phase 2 的编译错误

**Checkpoint**: Foundation ready — US1/US2/US3 可以开始

---

## Phase 3: User Story 1 - 口语化问题也能检索到正确片段，且回答仍围绕用户原问题 (Priority: P1) 🎯 MVP

**Goal**: 问答路径启用改写：检索用 `retrievalQuery`，提示词、回答、引用和重排仍用原问题；未配置/失败时行为不变。

**Independent Test**: 用确定性 `QueryTransformer` 替身，断言进入 `EmbeddingModel.embed(String)` 的是改写文本、捕获的 `ChatRequest` 中是原问题、`ScoringModel.scoreAll` 的 query 是原问题；无资源租户无改写调用。

### Tests for User Story 1

- [X] T019 [P] [US1] 创建 `src/test/java/com/unirag/retrieval/query/LlmQueryTransformerTest.java`：提示词包含原问题与约束、正常返回单条 Query、空响应返回空集合、`modelSource()` 取值、ChatModel 异常原样抛出
- [X] T020 [P] [US1] 创建 `src/test/java/com/unirag/retrieval/query/QueryRewriteEnhancementTest.java`：成功替换且 `query` 不变、`rewrite=false` 不调用、原问题超长跳过、异常/空/多查询/多行/超长/丢失数字等回退并写 DEGRADED、shadow 不改输入、指标记录

### Implementation for User Story 1

- [X] T021 [P] [US1] 扩展 `src/main/java/com/unirag/retrieval/model/QaRequest.java`：增加 `Boolean rewrite`，保留 5 参/6 参构造器，新增 7 参构造器
- [X] T022 [P] [US1] 扩展 `src/main/java/com/unirag/retrieval/web/AskRequest.java`：增加 `Boolean rewrite`，保留 5 参/6 参构造器，新增 7 参构造器，`toModel` 传递该值
- [X] T023 [US1] 修改 `src/main/java/com/unirag/retrieval/QaService.java`：注入 `QueryRewriteSupport`；按 `request.rewrite() == null || request.rewrite()` 求意图，结合 `enabledFor` 解析实际值；用 8 参 `RetrievalRequest` 同时传递 `rerank` 与 `rewrite`（依赖 T010、T012、T021、T022）
- [X] T024 [US1] 创建 `src/test/java/com/unirag/retrieval/QueryRewriteQaIT.java`（继承 `AbstractRetrievalIT`）：租户/向量表/管道装配、确定性改写替身、问答成功用例断言嵌入入参为改写文本、`ChatRequest` UserMessage 含原问题且不含改写文本、同时启用重排时 `scoreAll` 使用原问题（依赖 T016、T017、T023）
- [X] T025 [US1] 在 `src/test/java/com/unirag/retrieval/QueryRewriteQaIT.java` 增加：无资源租户行为不变、改写异常回退且问答 200、`primary-llm`/`dedicated` 的 `QUERY_REWRITE` 行 detail、请求 `rewrite=false` 不调用模型（依赖 T024）
- [X] T026 [US1] 运行 `./mvnw test -Dtest=LlmQueryTransformerTest,QueryRewriteEnhancementTest,QueryRewriteQaIT` 并修复 `src/main/java/com/unirag/retrieval/` 与 `src/test/java/com/unirag/retrieval/` 下的失败（依赖 T019、T020、T024、T025）

**Checkpoint**: US1 独立可用，`/api/v1/ask` 的价值闭环完成

---

## Phase 4: User Story 2 - 检索接口同样受益且可精确控制 (Priority: P2)

**Goal**: `/api/v1/retrieve` 支持改写与请求级关闭；响应结构、过滤语义与 `data.query` 保持原问题。

**Independent Test**: 同一查询在开启/关闭改写、有/无租户资源、带粒度/文档过滤下调用检索接口，断言嵌入入参、过滤范围、返回结构与 `data.query`。

### Implementation for User Story 2

- [X] T027 [P] [US2] 扩展 `src/main/java/com/unirag/retrieval/web/RetrieveRequest.java`：增加 `Boolean rewrite`，保留 5 参/6 参构造器，新增 7 参构造器；`toModel` 用 8 参 `RetrievalRequest` 传递 `rerank` 与 `rewrite`
- [X] T028 [US2] 修改 `src/main/java/com/unirag/retrieval/RetrievalService.java`：注入 `QueryRewriteSupport`，按 `request.rewrite() && enabledFor` 解析实际值，用 `withRewrite` 合入请求；保持既有 `withRerank` 语义（依赖 T010、T012、T027）

### Tests for User Story 2

- [X] T029 [US2] 创建 `src/test/java/com/unirag/retrieval/QueryRewriteRetrievalIT.java`（继承 `AbstractRetrievalIT`）：改写开启时嵌入入参为改写文本、`data.query` 为原问题；`rewrite=false` 不调用；无资源租户无 `QUERY_REWRITE` 行；粒度/单文档过滤仍生效（依赖 T017、T028）
- [X] T030 [US2] 运行 `./mvnw test -Dtest=QueryRewriteRetrievalIT` 并修复 `src/main/java/com/unirag/retrieval/` 与 `src/test/java/com/unirag/retrieval/` 下的失败（依赖 T029）

**Checkpoint**: US1 与 US2 均可独立工作

---

## Phase 5: User Story 3 - 改写可观测、可降级、可灰度评估 (Priority: P3)

**Goal**: 每次改写有独立观测行与指标；shadow 模式不改变返回；凭据零泄漏；跨租户隔离可验证。

**Independent Test**: 构造成功/超时/非法输出/无资源/shadow 场景，断言 `QUERY_REWRITE` 行字段、共享 trace、指标、返回结果与凭据零泄漏；A 的改写资源不被 B 使用。

### Tests for User Story 3

- [X] T031 [P] [US3] 创建 `src/test/java/com/unirag/retrieval/QueryRewriteShadowIT.java`：使用 `@TestPropertySource(properties="unirag.retrieval.query-rewrite.shadow=true")`；断言返回结果与关闭改写逐字段一致、`QUERY_REWRITE` 行 `detail` 含 `shadow=true`、shadow 下失败不影响主链路（依赖 T016、T017）
- [X] T032 [US3] 创建 `src/test/java/com/unirag/retrieval/QueryRewriteObservationIT.java`：启用改写的问答/检索各产生一行 `QUERY_REWRITE`，与同链路其他阶段共享 `trace_id`；`status`/`target_key`/`hit_count`/`item_count`/`error_message`/`detail` 符合契约；无资源/关闭时不写行；日志与观测中无 `api-key`；跨租户 A 的改写资源与主语言模型不被 B 使用；指标 `unirag.query.rewrite.requests`/`duration` 可见（依赖 T016、T017、T023、T028）
- [X] T033 [US3] 运行 `./mvnw test -Dtest=QueryRewriteShadowIT,QueryRewriteObservationIT` 并修复 `src/main/java/com/unirag/retrieval/` 与 `src/test/java/com/unirag/retrieval/` 下的失败（依赖 T031、T032）

**Checkpoint**: 全部 user stories 独立可用，具备灰度与排障能力

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 清理、文档与全量回归

- [X] T034 [P] 审查 `src/main/java/com/unirag/retrieval/query/`：确认 `rewrite`/`retrievalQuery` 的不变量注释准确、无重复校验逻辑、无未使用字段；核对 `src/main/java/com/unirag/retrieval/query/LlmQueryTransformer.java` 的提示词版本与脱敏
- [X] T035 [P] 更新 `specs/012-query-rewriting/quickstart.md`：补充 shadow 灰度顺序（配置 `{}` → shadow 观察 → 评估 → 关闭 shadow）与 `primary-llm`/`dedicated` 的手工验证说明
- [X] T036 运行完整 `./mvnw test`（`*Test` + `*IT`），修复 `src/main/java` 与 `src/test/java` 下的所有回归；确认无外部模型凭据依赖
- [X] T037 最终规格追溯：逐条核对 spec FR-001..FR-014 / SC-001..SC-008，确认无迁移、无前端改动、无新增依赖；在 `specs/012-query-rewriting/quickstart.md` 记录验证结果

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，T001–T004 可并行
- **Foundational (Phase 2)**: 依赖 Phase 1；完成 T018 后 US1/US2/US3 才能开始
- **US1 (Phase 3)**: 依赖 Phase 2；T019–T026
- **US2 (Phase 4)**: 依赖 Phase 2；T027–T030 可与 US1 并行（不同主代码文件与测试文件）
- **US3 (Phase 5)**: T031 依赖 Phase 2；T032/T033 依赖 US1 的 T023 与 US2 的 T028（端到端观测需要两条入口）
- **Polish (Phase 6)**: 依赖所有目标 story 完成

### User Story Dependencies

- **US1 (P1)**: 无其他 story 依赖；MVP
- **US2 (P2)**: 与 US1 共享 Foundational，但可独立实现/测试；不依赖 US1 的 `QaService` 改动
- **US3 (P3)**: 观测扩展在 Foundational；端到端观测测试依赖 US1/US2 已接通入口

### Within Each User Story

- 测试先于对应实现（T019/T020 → T021–T023；T029 依赖 T028 的实现接口；T031/T032 依赖 T016）
- 模型/请求字段 → 服务层 → 集成测试
- 每个 story 完成后运行该 story 的测试命令

---

## Parallel Execution Examples

### Phase 1

```bash
# T001–T004 可同时进行（不同文件）
```

### Phase 2

```bash
# 可并行：T006 QueryRewriteOutcome、T007 RewriteValidator、T008 LlmQueryTransformer、T013 ObservationMetrics、T017 测试辅助
# 顺序：T005 → T010 → T009 → T014 → T015 → T016 → T018
```

### User Story 1

```bash
# T019 与 T020 可并行（不同测试文件）
# T021 与 T022 可并行（不同请求模型）
# T023 完成后写 T024/T025，再跑 T026
```

### User Story 2

```bash
# T027 完成后做 T028，再写 T029，最后 T030
# 与 US1 的 T021–T023 可并行（不同文件）
```

### User Story 3

```bash
# T031 与 T032 可并行（不同测试文件），都完成后跑 T033
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 + Phase 2（共享基座）
2. 完成 US1（T019–T026）
3. **STOP and VALIDATE**：`./mvnw test -Dtest=LlmQueryTransformerTest,QueryRewriteEnhancementTest,QueryRewriteQaIT`
4. 此时 `/api/v1/ask` 已具备改写能力，可演示

### Incremental Delivery

1. Setup + Foundational → 基座就绪
2. US1 → 问答改写可用（MVP）
3. US2 → 检索接口可用
4. US3 → 观测/灰度/降级可验证
5. Polish → 全量回归与文档

### Parallel Team Strategy

- Phase 1/2 由一人或多人协作完成（按 [P] 拆分）
- Phase 2 完成后：
  - 开发者 A：US1（问答路径）
  - 开发者 B：US2（检索路径）
  - US3 在 US1/US2 接通后由 A/B 任一方完成

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- `[US1]/[US2]/[US3]` 仅用于 user story 阶段任务
- 本 feature 无数据库迁移；`retrieval_observation.stage` 为 `VARCHAR(32)`，`QUERY_REWRITE` 可直接使用
- 默认 `primary-llm`：`QUERY_REWRITE_MODEL` 配置 `{}` 时复用租户主 `LLM` Bean，不新建 ChatModel Bean
- 所有测试使用确定性替身；`./mvnw test` 同时执行 `*Test` 与 `*IT`
- 每个 story 完成后按 Checkpoint 验证，再进入下一优先级
- 实现阶段额外补充 `QueryRewriteModelResourceProviderTest`，覆盖 `primary-llm` / `dedicated` / 非法配置与超时上限
- 最终验证：`./mvnw test` 共 190 个测试，0 失败
