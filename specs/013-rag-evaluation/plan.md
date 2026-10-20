# Implementation Plan: RAG 检索评测基线

**Branch**: `013-rag-evaluation` | **Date**: 2026-10-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-rag-evaluation/spec.md`

## Summary

在既有检索/摄取能力之上建立**离线、可复现的检索评测基线**，不改动生产运行时：

1. **数据集**：`eval/datasets/<name>/` 存放语料、查询、相关性标注、运行配置与可选阈值；标注按“文档 + 原文片段”表达，兼容切片参数变化。
2. **评测运行器**：`src/test/java/com/unirag/eval/` 中的纯 Java 组件负责校验数据、准备专用评测租户、摄取语料、逐查询调用既有 `RetrievalService`、计算指标、归因失败并输出报告；`*EvalIT` 只做薄启动器。
3. **执行模式**：`live`/`record` 优先复用数据库中已有租户的嵌入模型与重排模型，`record` 同时录制 `embeddings.json` 与 `rerank.json`；`replay` 只读两个夹具，无外部凭据且结果可重复；两者报告明确标识、不可混比。
4. **指标**：文档级与片段级 Recall@k、HitRate@k、MRR@k、nDCG@k、不可回答误答率、耗时 p50/p95；同时输出总体与分标签维度。
5. **诊断与比较**：复用 `QueryEmbeddingStage` + `VectorSearchStage` 取得逐表候选，区分切片缺失、未召回、归并截断、重排丢弃、阈值过滤；报告比较按数据集与关键配置判定可比性并标记退化。
6. **质量门隔离**：默认 `./mvnw test` 排除 `eval` 标签；评测通过 `-Peval` 按需触发，默认门禁不读取外部凭据。

关键技术决策见 [research.md](./research.md)；数据与接口细节见 [data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.16；langchain4j 1.20.0（`EmbeddingModel`、`EmbeddingStore`、`ScoringModel` 均为既有依赖）；MyBatis-Plus 3.5.17；Jackson（随 Spring Boot 提供）；JUnit 5（既有测试栈）。**不新增第三方依赖**。

**Storage**: PostgreSQL + pgvector（既有）；评测复用 `tenant`、`tenant_resource_config`、`ingestion_pipeline`、`document`、`document_chunk` 与租户向量表，**不新增表、不新增 Flyway 迁移**。数据集、冻结向量、基线报告与运行报告以文件形式落在 `eval/`。

**Testing**: `./mvnw test`（`*Test` + `*IT`，真实 PostgreSQL + pgvector）；评测纯逻辑用确定性输入做单元测试；端到端评测用 `@Tag("eval")` + `-Peval` 显式触发；默认质量门不调用外部模型。

**Target Platform**: 开发机与 CI（macOS/Linux，JVM 21）；本地 PostgreSQL + pgvector。

**Project Type**: 单体 Spring Boot 应用 + test scope 评测工具。

**Performance Goals**: 10 篇文档、30 条查询的 live 基线 ≤ 10 分钟（不含模型下载与环境准备，spec SC-001）；replay 运行 ≤ 3 分钟；报告生成 ≤ 5 秒；默认质量门通过率保持 100%，总耗时变化 ≤ 20%（spec SC-010）。

**Constraints**:
- 评测 MUST 在专用评测租户内执行，不得读写业务租户（spec FR-004、宪法 IV）。
- 默认质量门 MUST 保持确定性且不依赖外部模型凭据；live 模式是显式评测活动，不是测试门禁（spec FR-009/FR-017、宪法 III）。
- 模型凭据 MUST 只从环境变量读取，MUST NOT 进入数据集、配置、报告、日志或版本控制（spec FR-018、宪法 V）。
- 标注 MUST 按文档 + 原文片段，不得依赖片段序号（spec FR-002）。
- 评测 MUST 复用既有 `IngestionService`、`RetrievalService`、`QueryEmbeddingStage`、`VectorSearchStage`，不得复制检索逻辑（宪法 I）。
- 不新增生产端点、不新增数据库表、不新增第三方依赖。

**Scale/Scope**: 10–50 篇文档、30–100 条查询、1–2 条摄取管线、topK ≤ 50；冻结向量夹具约 100 条向量。

**Unknowns**: **已全部解决**（Phase 0 完成，详见 `research.md`）：
1. 评测代码落点 → test scope 包 `com.unirag.eval`，不新增模块、不进入生产 jar（R1）。
2. live/replay/record 的向量化边界 → 文件夹具按文本 SHA-256 索引，live 可录制（R2）。
3. 评测租户与资源装配 → 复用 `TenantResourceRegistrar`，使用真实 `TenantResourceRouter` 子类（仅覆盖嵌入模型），不使用 Mockito（R3）。
4. 阈值与排序分离 → 每查询两遍：诊断遍 `minScore=0/topK=50`，生产遍使用配置阈值与开关（R5）。
5. 失败归因 → 复用阶段直接取得逐表候选，结合 `document_chunk` 判定五类以上归因（R7）。
6. 报告与比较契约 → JSON + Markdown，版本化数据集哈希与配置快照（R8/R11）。
7. 默认门禁隔离 → surefire `excludedGroups=eval`，profile `eval` 覆盖（R9）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 的门禁 | 结论 |
|---|---|---|
| I. 架构优先，拒绝冗余堆砌 | 评测组件全部放 test scope；复用 `IngestionService`、`RetrievalService`、`QueryEmbeddingStage`、`VectorSearchStage`、`TenantResourceRegistrar`、既有测试装配模式；不复制检索/摄取逻辑，不新增模块与第三方依赖 | PASS |
| II. 规格驱动交付 | spec 013 已生成并通过质量清单；本 plan 与 research/data-model/contracts/quickstart 先行；20 条 FR、10 条 SC 均映射到组件或验证场景 | PASS |
| III. 真实链路集成验证 | 评测端到端使用真实 PostgreSQL + pgvector 与真实摄取/检索服务；默认质量门用确定性复现模式；live 模式是显式测量活动，不属于默认门禁；指标/校验/比较逻辑有独立单元测试 | PASS |
| IV. 多租户隔离不可妥协 | 每个数据集使用独立评测租户 `eval_<dataset>`；运行前清理评测租户数据；运行后断言非评测租户数据指纹不变 | PASS |
| V. 敏感信息零泄漏 | live 凭据只从 `EVAL_EMBEDDING_*` 环境变量读取并写入租户资源配置；报告/日志只记录模型标识与资源 key；测试断言凭据零出现 | PASS |

**初次门禁结论：全部通过，无违反项。**

**设计要求后复评（Phase 1 之后）**：

| 原则 | 复评结论 |
|---|---|
| I. 架构优先，拒绝冗余堆砌 | **PASS** —— 评测运行器只编排既有服务；诊断复用既有阶段；数据集/报告为文件契约，不引入持久化层；未新增依赖或生产代码 |
| II. 规格驱动交付 | **PASS** —— 每条 FR 落到具体组件；SC 对应 quickstart 场景与单元/集成测试；未决项在 research R1–R13 定案 |
| III. 真实链路集成验证 | **PASS** —— replay 模式在真实 PG 上确定性运行；live 模式按需、显式、可追溯；默认门禁零外部依赖 |
| IV. 多租户隔离不可妥协 | **PASS** —— 评测租户独立、清理幂等、非评测租户指纹断言；不读取业务租户数据 |
| V. 敏感信息零泄漏 | **PASS** —— 凭据仅环境变量 → 租户配置；报告不含凭据；错误与日志经脱敏；测试扫描关键字 |

**非门禁性已知事项**：仓库根 `.gitignore` 未排除 `specs/`，但用户全局 gitignore 排除了 `specs`，导致新增 spec 文件默认不被 Git 跟踪。实现前 MUST 在仓库 `.gitignore` 增加 `!specs/`（或等价规则），否则违反宪法“规格产物纳入版本控制”。详见 research R13。

## Project Structure

### Documentation (this feature)

```text
specs/013-rag-evaluation/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/
│   ├── dataset.md       # 数据集目录与文件契约
│   ├── run-cli.md       # 评测触发、模式、环境变量与退出语义
│   ├── report.md        # 报告结构、指标定义、归因与比较契约
│   └── replay-fixture.md# 冻结向量夹具契约
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成，本命令不产出）
```

### Source Code (repository root)

```text
eval/                                      # 新增：评测工件（非运行时代码）
├── datasets/
│   └── baseline/
│       ├── corpus/*.md                    # 冻结语料
│       ├── queries.jsonl                  # 查询 + 相关性标注
│       ├── config.json                    # 运行配置
│       └── thresholds.json                # 可选阈值
├── fixtures/
│   └── baseline/embeddings.json           # 冻结向量（replay 用）
├── baselines/
│   └── baseline.json                      # 已确认基线（版本控制）
└── reports/
    └── .gitkeep                           # 运行报告（本地/CI 产物）

src/test/java/com/unirag/eval/             # 新增：test scope 评测工具
├── RagEvaluationIT.java                   # 薄启动器：运行评测
├── ReportCompareIT.java                   # 薄启动器：报告比较
├── EvaluationRunner.java                  # 编排：校验 → 准备 → 摄取 → 检索 → 指标 → 报告
├── EvaluationOptions.java                 # 系统属性/环境变量解析
├── dataset/
│   ├── EvaluationDataset.java
│   ├── QueryCase.java
│   ├── RelevanceLabel.java
│   ├── DatasetLoader.java
│   └── DatasetValidator.java
├── embedding/
│   ├── ReplayEmbeddingModel.java          # 夹具 → Embedding
│   └── RecordingEmbeddingModel.java       # 真实模型 → 录制夹具
├── setup/
│   ├── EvalTenantProvisioner.java         # 租户/资源/管线装配与清理
│   └── EvaluationIndexPreparer.java       # 摄取与索引重建
├── retrieval/
│   ├── EvaluationRetriever.java           # 生产遍 + 诊断遍
│   └── TenantScope.java                   # 请求作用域租户上下文
├── metric/
│   ├── NormalizedText.java
│   ├── RelevanceMatcher.java
│   ├── RetrievalMetrics.java
│   └── FailureAttributor.java
└── report/
    ├── EvaluationReport.java
    ├── ReportWriter.java
    └── ReportComparator.java

pom.xml                                    # 仅新增 surefire excludedGroups 与 eval profile
```

**Structure Decision**：评测是开发/QA 活动，不是生产运行时能力，因此全部代码放 `src/test/java`，不进入生产 jar；数据集、夹具与报告放仓库根 `eval/`，便于版本控制与人工阅读。评测基类直接继承 `AbstractPostgresIntegrationTest`，使用真实的 `EvalTenantResourceRouter`（继承 `TenantResourceRouter`，仅覆盖嵌入模型）与真实 Store/摄取/检索服务，不使用 Mockito。评测运行器与 JUnit 启动器分离，未来若需要 CLI，只需替换启动器。不新增 Maven 模块，不新增生产源文件，不新增数据库表。

## Implementation Outline

1. **P1 数据契约与校验**：`DatasetLoader`/`DatasetValidator`/`NormalizedText`；`eval/datasets/baseline` 样例数据；单元测试覆盖重复标识、缺失文档、缺失片段、可回答语义冲突。
2. **P1 评测租户与索引**：`EvalTenantProvisioner` 插入租户、存储资源、管线并调用 `TenantResourceRegistrar.loadAndRegister()`；`EvaluationIndexPreparer` 清理并摄取；复用 `IngestionService`（同步）。
3. **P1 向量模式**：`ReplayEmbeddingModel`、`RecordingEmbeddingModel`、夹具读写；live 模式从环境变量构造模型资源，replay 模式不要求凭据。
4. **P1 检索与指标**：`EvaluationRetriever` 执行生产遍与诊断遍；`RelevanceMatcher` 按文档 + 片段匹配；`RetrievalMetrics` 计算文档级/片段级指标、不可回答误答率与耗时。
5. **P1 报告**：`EvaluationReport` + `ReportWriter` 输出 JSON 与 Markdown；记录数据集/配置快照；`RagEvaluationIT` 薄启动器。
6. **P2 失败归因**：`FailureAttributor` 使用诊断遍逐表命中与 `document_chunk` 判定：切片缺失、未召回、归并/截断、重排丢弃、阈值过滤、排名低于 topK。
7. **P2 比较与门槛**：`ReportComparator` 校验可比字段、输出差值与阈值判定；`ReportCompareIT` 薄启动器；基线报告保存到 `eval/baselines/`。
8. **P2 门禁隔离**：`pom.xml` 默认排除 `eval` 标签，`-Peval` 覆盖；确认默认 `./mvnw test` 行为与耗时不变。
9. **P3 数据集扩展与文档**：补充数据文件即生效；quickstart 记录首次建基线、日常 replay、比较与排障。

## Phase 0 / Phase 1 Outputs

- [research.md](./research.md)：R1–R13，技术未知项与取舍全部定案。
- [data-model.md](./data-model.md)：文件模型、运行状态、指标定义、归因枚举、校验规则与既有表映射。
- [contracts/dataset.md](./contracts/dataset.md)：语料、查询、配置、阈值格式与校验。
- [contracts/run-cli.md](./contracts/run-cli.md)：Maven 触发、模式、环境变量、报告路径与退出语义。
- [contracts/report.md](./contracts/report.md)：报告 JSON 结构、指标、归因、可比性与比较输出。
- [contracts/replay-fixture.md](./contracts/replay-fixture.md)：冻结向量格式、键规则、录制与缺失处理。
- [quickstart.md](./quickstart.md)：首次建基线、replay 回归、报告比较与排障。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违反项。本 feature 未新增第三方依赖、未新增生产源文件、未新增数据库表或迁移；唯一构建变更是 surefire 排除 `eval` 标签并新增按需 profile。评测逻辑放 test scope 是为避免把开发期测量能力装入生产 jar；未来若需要独立 CLI，可将 `com.unirag.eval` 平移至独立模块，运行器无需重写。

## Risks & Mitigations

| 风险 | 缓解 |
|---|---|
| replay 夹具与语料/切片变化后不一致 | 夹具按精确文本 SHA-256 索引；缺失向量立即失败并列出缺失项；语料/切片变化必须重新录制 |
| live 模式成本或延迟不可控 | 仅按需触发；限制 30–100 条查询；记录耗时；不进入默认质量门 |
| 评测污染业务租户 | 独立 `eval_<dataset>` 租户；运行前清理；运行前后非评测租户指纹断言 |
| 标注片段跨切片或匹配歧义 | 按归一化文本包含匹配；报告匹配口径；跨片段命中计为切片问题并保留原文 |
| 多管线重复片段导致指标虚高 | 文档级按文档去重、片段级保留重复并分别报告；报告记录口径 |
| 阈值过滤与排序混淆 | 两遍检索：诊断遍 `minScore=0`，生产遍使用配置阈值；归因区分阈值/排序/归并 |
| surefire 标签配置误伤既有测试 | 仅给评测启动器加 `@Tag("eval")`；新增 profile 覆盖排除项；验证默认 `./mvnw test` 通过率与耗时 |
| 凭据进入报告或日志 | 只从环境变量读取；报告只记录模型标识；测试扫描关键字；文档禁止示例密钥 |
| 全局 gitignore 导致 spec 未跟踪 | 实现前在仓库 `.gitignore` 增加 `!specs/`；以 `git check-ignore` 验证 |
