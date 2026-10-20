# Tasks: RAG 检索评测基线

**Input**: Design documents from `/specs/013-rag-evaluation/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 本 feature 是评测工具，且项目宪法 III 要求真实链路验证，因此包含测试任务。默认质量门（`./mvnw test`）不执行 `eval` 标签；评测端到端测试通过 `-Peval` 显式触发。

**Organization**: 任务按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1–US4）
- 所有任务均包含准确文件路径

## Path Conventions

- 评测代码：`src/test/java/com/unirag/eval/`
- 评测工件：`eval/`
- 构建配置：`pom.xml`、`.gitignore`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 建立评测工件目录、构建门禁与 Git 跟踪前置条件

- [X] T001 [P] 在 `.gitignore` 增加 `!specs/` 覆盖全局忽略规则，确保 `specs/013-rag-evaluation/` 可被 Git 跟踪；用 `git check-ignore -v specs/013-rag-evaluation/plan.md` 验证
- [X] T002 [P] 创建 `eval/` 工件目录与占位文件：`eval/datasets/.gitkeep`、`eval/fixtures/.gitkeep`、`eval/baselines/.gitkeep`、`eval/reports/.gitkeep`；在 `.gitignore` 忽略 `eval/reports/*` 但保留 `.gitkeep`
- [X] T003 [P] 在 `pom.xml` 的 surefire 配置增加 `<excludedGroups>eval</excludedGroups>`，并新增 `eval` profile：`<groups>eval</groups>` 且覆盖排除项

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 数据集模型、校验、租户装配、向量模式与匹配逻辑；所有用户故事共享

**⚠️ CRITICAL**: 本阶段完成前不得开始用户故事实现

- [X] T004 [P] 创建数据集记录类型：`src/test/java/com/unirag/eval/dataset/EvaluationDataset.java`、`QueryCase.java`、`RelevanceLabel.java`、`DatasetConfig.java`、`PipelineConfig.java`、`RetrievalConfig.java`、`MetricConfig.java`、`ThresholdConfig.java`
- [X] T005 [P] 创建文本归一化与哈希工具：`src/test/java/com/unirag/eval/metric/NormalizedText.java`、`src/test/java/com/unirag/eval/metric/Hashing.java`
- [X] T006 [P] 创建运行参数解析：`src/test/java/com/unirag/eval/EvaluationOptions.java`（解析 `eval.dataset`、`eval.mode`、`eval.reportDir`、`eval.baselineDir`、`eval.compareTo`、`eval.failOnThreshold`）
- [X] T007 [P] 创建向量模式：`src/test/java/com/unirag/eval/embedding/ReplayEmbeddingModel.java`、`RecordingEmbeddingModel.java`（精确文本 SHA-256 键、维度校验、缺失即失败）
- [X] T008 实现数据集加载：`src/test/java/com/unirag/eval/dataset/DatasetLoader.java`（读取 `eval/datasets/<name>/` 的语料、`queries.jsonl`、`config.json`、可选 `thresholds.json`；依赖 T004）
- [X] T009 实现数据集校验：`src/test/java/com/unirag/eval/dataset/DatasetValidator.java`（V-001–V-011；错误定位到查询/文档/字段；依赖 T004、T005、T008）
- [X] T010 实现评测租户装配：`src/test/java/com/unirag/eval/setup/EvalTenantProvisioner.java`（插入 tenant、`tenant_resource_config`、`ingestion_pipeline`，调用 `TenantResourceRegistrar.loadAndRegister()`；清理评测数据；依赖 T004、T007）
- [X] T011 实现租户作用域：`src/test/java/com/unirag/eval/retrieval/TenantScope.java`（复用 `AbstractRetrievalIT.inTenant` 的请求作用域模式；依赖既有 `TenantContext`）
- [X] T012 实现相关性匹配：`src/test/java/com/unirag/eval/metric/RelevanceMatcher.java`（文档 + 归一化 span 包含匹配；文档级与片段级判定；依赖 T005）
- [X] T013 实现索引准备：`src/test/java/com/unirag/eval/setup/EvaluationIndexPreparer.java`（清理评测租户后调用 `IngestionService.ingest`；处理内容指纹 NOOP 与切片参数变更重建；依赖 T010、T011）
- [X] T014 创建报告数据模型：`src/test/java/com/unirag/eval/report/EvaluationReport.java`（RunInfo、DatasetSnapshot、ConfigSnapshot、QueryResult、RetrievedItem、DiagnosticInfo、FailureCategory）
- [X] T015 [P] 编写数据集校验单元测试：`src/test/java/com/unirag/eval/dataset/DatasetValidatorTest.java`（重复标识、缺失文档、缺失 span、可回答语义冲突、维度/阈值非法；依赖 T009）
- [X] T016 [P] 编写归一化与匹配单元测试：`src/test/java/com/unirag/eval/metric/RelevanceMatcherTest.java`（空白折叠、大小写、中文片段、多标注、跨文档；依赖 T005、T012）

**Checkpoint**: 数据集、校验、租户装配、向量模式、匹配逻辑就绪，用户故事可开始

---

## Phase 3: User Story 1 - 一键复现检索基线 (Priority: P1) 🎯 MVP

**Goal**: 用冻结数据集一次触发完成摄取、检索、指标计算与报告输出，结果可重复

**Independent Test**: `./mvnw test -Peval -Dtest=RagEvaluationIT -Deval.dataset=baseline -Deval.mode=replay` 生成报告；连续运行两次 `overall` 指标完全一致

### Implementation for User Story 1

- [X] T017 [P] [US1] 创建 10 篇评测语料到 `eval/datasets/baseline/corpus/`（覆盖配置、部署、检索、权限、故障排查等主题，含精确标识符与表格）
- [X] T018 [P] [US1] 创建 `eval/datasets/baseline/queries.jsonl`：30 条查询，20 条可回答、5 条不可回答、5 条边界样本；每条按 `doc + span` 标注，覆盖 fact、exact-term、colloquial、cross-doc、unanswerable 标签
- [X] T019 [P] [US1] 创建 `eval/datasets/baseline/config.json`：1 条管线（`maxSegmentTokens=300`、`overlapTokens=50`）、`topK=5`、`recallTopK=20`、`minScore=0.2`、`rerank=false`、`rewrite=false`、`k=[1,3,5,10]`、`diagnosticTopK=50`
- [X] T020 [US1] 实现生产遍与诊断遍检索：`src/test/java/com/unirag/eval/retrieval/EvaluationRetriever.java`（生产遍调 `RetrievalService.retrieve`；诊断遍复用 `QueryEmbeddingStage` + `VectorSearchStage`，`minScore=0`、`topK=50`；依赖 T011、T012）
- [X] T021 [US1] 实现指标计算：`src/test/java/com/unirag/eval/metric/RetrievalMetrics.java`（文档级/片段级 Recall@k、HitRate、MRR、nDCG、不可回答误答率、p50/p95；依赖 T012）
- [X] T022 [US1] 实现报告写出：`src/test/java/com/unirag/eval/report/ReportWriter.java`（JSON + Markdown、配置快照、原子写入；依赖 T014）
- [X] T023 [US1] 实现评测编排：`src/test/java/com/unirag/eval/EvaluationRunner.java`（校验 → 准备 → 摄取 → 检索 → 指标 → 报告；依赖 T008–T013、T020–T022）
- [X] T024 [US1] 实现薄启动器：`src/test/java/com/unirag/eval/RagEvaluationIT.java`（`@Tag("eval")`，读取 `EvaluationOptions` 并调用 `EvaluationRunner`）
- [X] T025 [P] [US1] 编写指标单元测试：`src/test/java/com/unirag/eval/metric/RetrievalMetricsTest.java`（固定结果集验证 Recall/HitRate/MRR/nDCG/误答率；依赖 T021）
- [X] T026 [US1] 编写复现性测试：`src/test/java/com/unirag/eval/RagEvaluationDeterminismIT.java`（同一数据集连续运行两次，断言 `overall` 指标完全一致；依赖 T024）
- [X] T027 [US1] 生成首次权威基线：补充 5 篇干扰文档后，使用 `-Deval.mode=record -Deval.embeddingTenant=demo` 复用 `BAAI/bge-m3` 与 `BAAI/bge-reranker-v2-m3`，报告保存为 `eval/baselines/baseline.json`，replay 报告保存为 `eval/baselines/baseline-replay.json`

**Checkpoint**: US1 可独立运行并产出可重复报告，MVP 完成

---

## Phase 4: User Story 2 - 比较版本并阻止检索退化 (Priority: P2)

**Goal**: 比较两份报告，输出指标与分维度差值，拒绝不可比配置，按阈值标记退化

**Independent Test**: 用固定报告夹具比较，断言差值与手工计算一致；不可比报告被拒绝；阈值未通过被标记

### Implementation for User Story 2

- [X] T028 [US2] 实现报告比较核心：`src/test/java/com/unirag/eval/report/ReportComparator.java`（可比字段校验、总体/分标签差值、退化标记；依赖 T014）
- [X] T029 [US2] 在 `src/test/java/com/unirag/eval/report/ReportComparator.java` 中实现阈值判定：`min`/`max` 方向、`passed`/`regressed`、未配置阈值时 `passed=null`
- [X] T030 [P] [US2] 编写比较单元测试：`src/test/java/com/unirag/eval/report/ReportComparatorTest.java`（可比/不可比、正负差值、阈值通过/失败/未配置；依赖 T028、T029）
- [X] T031 [US2] 实现比较启动器：`src/test/java/com/unirag/eval/ReportCompareIT.java`（`@Tag("eval")`，读取 `eval.baseline`/`eval.candidate`，写出比较结果）
- [X] T032 [US2] 实现比较结果渲染：在 `src/test/java/com/unirag/eval/report/ReportWriter.java` 增加比较 JSON 与 Markdown 输出（差异表、退化项、不可比字段；依赖 T028、T029）
- [X] T033 [P] [US2] 添加报告夹具：`src/test/resources/eval/reports/baseline.json`、`candidate-comparable.json`、`candidate-incomparable.json`（依赖 T014）
- [X] T034 [US2] 验证阈值与退出语义：在 `src/test/java/com/unirag/eval/ReportCompareIT.java` 覆盖 `failOnThreshold=false/true`，断言报告与 JUnit 结果（依赖 T031、T029）

**Checkpoint**: US2 可在不改变 US1 的情况下独立比较报告

---

## Phase 5: User Story 3 - 定位失败发生在哪一层 (Priority: P3)

**Goal**: 对未命中查询给出可复核的失败归因：切片缺失、未召回、归并/截断、重排丢弃、阈值过滤、排名低于 topK

**Independent Test**: 构造四类确定性失败样本，断言报告归因与证据正确；成功样本不产生误报

### Implementation for User Story 3

- [X] T035 [US3] 实现失败归因：`src/test/java/com/unirag/eval/metric/FailureAttributor.java`（读取 `document_chunk`、诊断遍逐表命中、生产遍结果；输出 `CHUNKING_GAP`、`NOT_RECALLED`、`MERGE_OR_TOPK_DROPPED`、`RERANK_DROPPED`、`THRESHOLD_FILTERED`、`BELOW_TOPK`、`LABEL_INVALID`；依赖 T020、T012）
- [X] T036 [US3] 将归因接入编排与报告：修改 `src/test/java/com/unirag/eval/EvaluationRunner.java` 与 `report/EvaluationReport.java`，为每个失败查询写入 `failure` 与 `evidence`（依赖 T023、T035）
- [X] T037 [P] [US3] 编写归因单元测试：`src/test/java/com/unirag/eval/metric/FailureAttributorTest.java`（逐类构造证据，断言主归因与证据；成功样本不误报；依赖 T035）
- [X] T038 [P] [US3] 编写多管线指标测试：`src/test/java/com/unirag/eval/metric/MultiPipelineMetricsTest.java`（文档级去重、片段级保留、粗/细粒度重复片段口径；依赖 T021、T035）
- [X] T039 [US3] 添加归因端到端场景：`src/test/java/com/unirag/eval/RagEvaluationAttributionIT.java`（固定小型数据集与判定夹具，覆盖切片缺失、未召回、阈值过滤、低于 topK；依赖 T036、T037）
- [X] T040 [US3] 在 Markdown 报告中渲染失败明细与证据：修改 `src/test/java/com/unirag/eval/report/ReportWriter.java`（失败查询列表、gold、top-k、归因、证据；依赖 T022、T035）

**Checkpoint**: US3 可独立解释失败原因，不依赖 US2

---

## Phase 6: User Story 4 - 领域专家维护评测数据集 (Priority: P4)

**Goal**: 仅通过数据文件新增语料与查询即可参与评测，结构错误在摄取前被定位

**Independent Test**: 新增 `eval/datasets/sample/` 并运行评测，无需修改代码；构造非法数据集，断言错误定位到具体条目

### Implementation for User Story 4

- [X] T041 [P] [US4] 创建第二个数据集 `eval/datasets/sample/`：`corpus/`（2–3 篇小文档）、`queries.jsonl`（5 条查询）、`config.json`（独立评测租户与表名）
- [X] T042 [P] [US4] 编写数据集维护指南：`eval/datasets/README.md`（目录、字段、标注原则、版本规则、常见校验错误）
- [X] T043 [US4] 编写数据文件热加载测试：`src/test/java/com/unirag/eval/dataset/DatasetOnboardingTest.java`（在临时目录生成新语料/查询，加载并校验，证明无需改代码；依赖 T009）
- [X] T044 [US4] 扩展校验测试覆盖全部 V-001–V-011：修改 `src/test/java/com/unirag/eval/dataset/DatasetValidatorTest.java`，断言错误包含数据集名、查询 id、文档名与字段（依赖 T009）
- [X] T045 [US4] 验证新数据集可直接运行：以 `-Deval.dataset=sample -Deval.mode=replay` 执行 `src/test/java/com/unirag/eval/RagEvaluationIT.java`，确认 `eval/datasets/sample/` 的报告生成且无需代码变更（依赖 T024、T041）

**Checkpoint**: 数据集可独立扩展，校验错误可定位

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 文档、门禁、安全与交付前验证

- [X] T046 [P] 更新 `README.md`：增加 RAG 评测章节（两种模式、命令、报告位置、与默认质量门的区别）
- [X] T047 [P] 检查 `.gitignore`：确保 `eval/reports/*` 被忽略、`eval/baselines/`、`eval/fixtures/`、`eval/datasets/` 被跟踪、`!specs/` 生效
- [X] T048 运行 `./mvnw test`：确认既有测试全部通过、未执行任何 `@Tag("eval")` 类、未要求 `EVAL_EMBEDDING_*`，耗时变化 ≤ 20%（SC-010）
- [X] T049 运行 `quickstart.md` 的 replay 与比较命令：确认报告字段、指标键、归因与比较结果符合契约（SC-001–SC-008）
- [X] T050 [P] 添加凭据泄漏测试：`src/test/java/com/unirag/eval/report/ReportRedactionTest.java`（报告/日志/Markdown 不出现 `EVAL_EMBEDDING_API_KEY` 值或 `api-key` 配置原文）
- [X] T051 检查生产代码边界：确认除 `pom.xml`、`.gitignore`、`README.md` 外无 `src/main` 改动；确认 `src/test/java/com/unirag/eval/` 未引用生产包以外的私有实现
- [X] T052 验证 Git 跟踪：`git check-ignore -v specs/013-rag-evaluation/plan.md` 无输出；`git status --untracked-files=all` 可见 spec、plan、tasks 与 eval 工件
- [X] T053 同步 `quickstart.md` 与实际命令/路径：若实现调整了参数或报告字段，更新文档并复跑 T049

---

## Phase 8: Verification Closure

**Purpose**: 补齐 spec SC/FR 的逐条验收

- [X] T054 将复现性测试扩展为连续 3 次 replay，断言三次 `overall` 与数据集版本完全一致（SC-002）
- [X] T055 添加 `src/test/java/com/unirag/eval/embedding/ReplayModelTest.java`：覆盖缺失向量/分数、维度不一致、夹具版本非法、夹具文件缺失
- [X] T056 添加 `src/test/java/com/unirag/eval/EvaluationTenantIsolationIT.java`：运行前后非评测租户行数与指纹一致（SC-005）
- [X] T057 添加 `src/test/java/com/unirag/eval/RagEvaluationDualPipelineIT.java`：双管线双表端到端，断言 searchedTables=2、文档级去重与片段级保留（FR-015）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始
- **Foundational (Phase 2)**: 依赖 Setup；阻塞所有用户故事
- **US1 (Phase 3)**: 依赖 Foundational；MVP
- **US2 (Phase 4)**: 依赖 Foundational；报告夹具可独立实现，不强依赖 US1 的 Runner
- **US3 (Phase 5)**: 依赖 US1 的 `EvaluationRetriever` 与报告结构；归因单测可先用合成数据
- **US4 (Phase 6)**: 依赖 Foundational；数据文件扩展与校验可独立验证
- **Polish (Phase 7)**: 依赖所需用户故事完成

### User Story Dependencies

- **US1**: 无其他故事依赖
- **US2**: 依赖 `EvaluationReport` 数据模型（Foundational T014）与报告夹具；可在 US1 完成后立即开始
- **US3**: 依赖 US1 的 T020（诊断遍）与 T023（编排）；归因逻辑可先单测
- **US4**: 依赖 Foundational 的 T008/T009；可与 US1 并行

### Within Each User Story

- 数据集模型/加载/校验 → 租户/索引/向量 → 检索/指标 → 报告/编排 → 启动器 → 测试
- 每个故事完成后在其 Checkpoint 独立验证

### Parallel Opportunities

- Setup：T001、T002、T003 可并行
- Foundational：T004、T005、T006、T007、T014 可并行；T015、T016 可并行
- US1：T017、T018、T019 可并行；T020、T021、T022 可并行
- US2：T030、T033 可并行
- US3：T037、T038 可并行
- US4：T041、T042 可并行
- Polish：T046、T047、T050 可并行

---

## Parallel Example: User Story 1

```bash
# 数据集内容可并行编写
Task: "T017 创建 10 篇评测语料到 eval/datasets/baseline/corpus/"
Task: "T018 创建 30 条查询到 eval/datasets/baseline/queries.jsonl"
Task: "T019 创建运行配置到 eval/datasets/baseline/config.json"

# 代码组件可并行实现（不同文件）
Task: "T020 实现 EvaluationRetriever"
Task: "T021 实现 RetrievalMetrics"
Task: "T022 实现 ReportWriter"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1：Setup
2. 完成 Phase 2：Foundational
3. 完成 Phase 3：US1
4. **STOP and VALIDATE**：运行 replay，确认报告与指标可重复
5. 生成首次基线并保存到 `eval/baselines/baseline.json`

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. US1 → 可复现基线（MVP）
3. US2 → 版本比较与退化门禁
4. US3 → 失败归因
5. US4 → 数据集扩展
6. Polish → 文档、安全与门禁验证

### Parallel Team Strategy

- 一名开发者完成 Setup + Foundational
- 之后：
  - 开发者 A：US1（核心链路）
  - 开发者 B：US2（报告比较，使用夹具）
  - 开发者 C：US4（数据集扩展，使用 Foundational loader）
- US3 在 US1 的诊断遍与报告结构完成后开始

---

## Notes

- `[P]` 表示不同文件且无未完成依赖，可并行
- 所有用户故事任务均带 `[USn]`，Setup/Foundational/Polish 不带
- 评测端到端类必须标注 `@Tag("eval")`，默认质量门不得执行
- 每个故事在 Checkpoint 独立验证；未通过不得进入下一阶段
- 评测工件路径统一为 `eval/`；不得把数据集或报告写入 `src/main`
- 任何任务完成后均需保证 `./mvnw test` 不因评测代码而失败
