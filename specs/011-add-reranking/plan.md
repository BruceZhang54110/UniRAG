# Implementation Plan: 检索结果重排序（Reranking）

**Branch**: `011-add-reranking` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-add-reranking/spec.md`

## Summary

在既有检索/问答阶段流水线上引入**可选重排序**：

1. **租户级重排序模型**：新增 `RERANK_MODEL` 资源与 Provider，首期主用 `BAAI/bge-reranker-v2-m3`，通过 `ScoringModel` 适配器调用租户配置的 `/rerank` 服务。
2. **扩大召回 + 精排**：启用重排时，向量检索先召回 `max(topK, recallTopK)` 条（默认 20），再由 `ScoringModel` 对“查询—片段”重新打分，按重排分数降序、可选阈值过滤、截断到调用方的 `topK`。
3. **流水线扩展**：重排实现为 `MERGE` 阶段的 `EnhancementExtension`（FAIL_OPEN），不新增核心阶段；失败自动回退原向量顺序。
4. **观测与灰度**：新增 `RERANK` 观测阶段、`detail` 明细列、重排指标与 shadow 模式；响应新增可空 `rerankScore`，`score` 仍为原向量分数。
5. **兼容性**：未配置重排序的租户、显式关闭重排的请求，响应与行为保持 010 契约不变。

关键技术决策见 [research.md](./research.md)；数据与接口细节见 [data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.16；langchain4j 1.20.0（`langchain4j-core` 已含 `ScoringModel`、`ReRankingContentAggregator`；`langchain4j-pgvector` 1.20.0-beta30）；MyBatis-Plus 3.5.17；Flyway；Micrometer；Spring `RestClient`（随 `spring-boot-starter-web` 提供，**不新增第三方依赖**）

**Storage**: PostgreSQL 16 + pgvector 0.8.1；`retrieval_observation` 新增可空 `detail VARCHAR(2000)`（Flyway V9）；`RERANK_MODEL` 复用 `tenant_resource_config` JSONB，不新建表

**Testing**: `./mvnw test`（`*Test` + `*IT`，真实 PostgreSQL + pgvector）；重排序用确定性 `ScoringModel` 替身；HTTP 适配器用本地桩，不访问外网、不使用真实 key

**Target Platform**: 服务端 Linux/macOS（JVM 21）；既有 Web 前端无需改动

**Project Type**: web service（后端）+ 轻量前端（本 feature 不动前端）

**Performance Goals**: 20–30 条候选下重排额外延迟 p95 ≤ 500ms；问答端到端 p95 ≤ 3s；纯检索在未启用重排时与 010 基线一致

**Constraints**:
- 重排失败 MUST fail-open，不得升级为检索/问答 HTTP 失败（spec FR-006）
- 未配置 `RERANK_MODEL` 的租户 MUST NOT 扩大召回或增加重排调用（spec SC-006）
- 重排凭据 MUST 只来自租户资源配置，MUST NOT 出现在日志、观测、错误信息、测试断言或版本控制中（宪法原则 V）
- MUST NOT 使用 `AiServices` / `DefaultRetrievalAugmentor` 高层编排（010 FR-012）
- 观测写库 MUST best-effort，不得影响主链路

**Scale/Scope**: 每租户一个 primary `RERANK_MODEL`；默认召回 20、最终 3–5（由请求 `topK` 决定）；一次检索/问答最多一次重排 HTTP 调用（按批次拆分）

**Unknowns**: **已全部解决**（Phase 0 完成，详见 `research.md`）：
1. LangChain4j 接入方式 → `ScoringModel.scoreAll`，同步路径；不使用 `ReRankingContentAggregator`（R1）。
2. BGE reranker 客户端 → 自定义 `BgeRerankerScoringModel`，首期适配 Cohere/SiliconFlow 风格 `/rerank`；TEI 等协议只替换适配器（R2）。
3. 流水线接入点 → `MERGE` 阶段的 `EnhancementExtension`，观测由 `RerankObservationExtension` 补足（R3）。
4. 召回/最终条数 → 内部 `recallTopK`，对外 `topK` 语义不变（R4）。
5. 分数暴露 → `score` 保持向量分数，新增可空 `rerankScore`；重排后数组顺序按 `rerankScore`（R5）。
6. 灰度与持久化 → shadow 模式 + `RERANK` 观测行 + `detail` 列（R6/R7）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 的门禁 | 结论 |
|---|---|---|
| I. 架构优先，拒绝冗余堆砌 | 复用既有 `EnhancementExtension`/`ObservationExtension`/`TenantResourceRouter`/`ObservationRecorder`/`ObservationMetrics`/MyBatis-Plus 三层；重排不新增核心阶段；`RerankOutcome` 只承载必要状态；不新增第三方依赖（`RestClient` 已随 Web starter 提供） | PASS |
| II. 规格驱动交付 | 本 plan 与 research/data-model/contracts/quickstart 先行；spec 的 12 条 FR、7 条 SC 均映射到设计；模型选择（BAAI/bge-reranker-v2-m3）已在 clarify 落定 | PASS |
| III. 真实链路集成验证 | `RerankApiIT` 跑真实 PG + pgvector；重排用确定性 `ScoringModel` 替身，HTTP 适配器用本地桩；MUST NOT 依赖外部 reranker key；断言覆盖 API 响应、观测行、租户隔离与降级 | PASS |
| IV. 多租户隔离不可妥协 | `RERANK_MODEL` 按租户配置；`RerankSupport`/`Router` 只解析发起租户资源；跨租户用例 MUST 100% 通过 | PASS |
| V. 敏感信息零泄漏 | 凭据只经租户配置；日志/观测只含租户、资源 key、状态、ID/分数；`detail` 不含片段原文；`Redaction` 统一收敛错误原因 | PASS |

**初次门禁结论：全部通过，无违反项。**

**设计要求后复评（Phase 1 之后）**：

| 原则 | 复评结论 |
|---|---|
| I. 架构优先，拒绝冗余堆砌 | **PASS** —— 新增 `RerankEnhancement` 与 `RerankObservationExtension` 分别承担“改写候选”和“记录结果”，职责不重叠；未新增核心阶段、未复制既有观测/资源解析逻辑；`RetrievedChunk` 用可空字段 + 兼容构造器扩展，避免大范围改动 |
| II. 规格驱动交付 | **PASS** —— 12 条 FR 均落到具体组件/契约；SC-001~007 对应测试与 quickstart 验证场景；未决项（提供商协议、召回默认值、shadow 明细）已在 research 中定案 |
| III. 真实链路集成验证 | **PASS** —— 测试矩阵覆盖阶段单测、适配器 HTTP 桩、真实 PG 的 API IT、观测行断言；既有 `*IT` 在 `hasPrimary=false` 时保持原行为 |
| IV. 多租户隔离不可妥协 | **PASS** —— 资源解析、观测行、API 均以 `tenant_id`/`tenantCode` 闭合；新增 `RERANK` 行沿用既有租户列与索引 |
| V. 敏感信息零泄漏 | **PASS** —— `detail` 只存 ID/分数/keep-drop；`RerankOutcome.reason` 经 `Redaction`；API key 不进入任何日志/响应/测试断言 |

**唯一非门禁性的已知限制**：首期只验证一种 reranker 协议（Cohere/SiliconFlow 风格 `/rerank`）；TEI 或其他协议需要新增适配器，但不影响主流水线与数据模型。已在 research R2 记录。

## Project Structure

### Documentation (this feature)

```text
specs/011-add-reranking/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/
│   ├── retrieval-api-delta.md   # /retrieve、/ask 的增量契约
│   └── rerank-model.md          # RERANK_MODEL 配置 + ScoringModel + 扩展/观测契约
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成，本命令不产出）
```

### Source Code (repository root)

```text
src/main/java/com/unirag/
├── retrieval/
│   ├── rerank/                              # 本 feature 新增
│   │   ├── RerankProperties.java            # unirag.retrieval.rerank.*
│   │   ├── RerankSupport.java               # enabledFor(tenant) = enabled && hasPrimary
│   │   ├── RerankEnhancement.java           # MERGE 阶段增强型扩展（FAIL_OPEN）
│   │   ├── RerankOutcome.java               # candidates/kept/status/reason/shadow/detail
│   │   └── BgeRerankerScoringModel.java     # ScoringModel 适配器（/rerank）
│   ├── observation/
│   │   ├── RerankObservationExtension.java  # 读 RerankOutcome → RERANK 行
│   │   ├── ObservationRecorder.java         # + record(ctx, result, detail) 重载
│   │   └── ObservationMetrics.java          # + recordRerank(...)
│   ├── pipeline/
│   │   └── StageName.java                   # + RERANK
│   ├── model/
│   │   ├── RetrievalRequest.java            # + recallTopK, rerank；保留 5 参构造器
│   │   ├── QaRequest.java                   # + 可选 rerank；保留 5 参构造器
│   │   └── RetrievedChunk.java              # + 可空 rerankScore；保留 7 参构造器
│   ├── stage/
│   │   ├── VectorSearchStage.java           # maxResults = request.recallTopK()
│   │   └── MergeStage.java                  # 归并上限 = request.recallTopK()
│   ├── RetrievalService.java                # 解析 rerank/recallTopK
│   ├── QaService.java                       # 解析 rerank/recallTopK
│   └── web/
│       ├── RetrieveRequest.java             # + 可选 rerank
│       ├── AskRequest.java                  # + 可选 rerank
│       ├── RetrievalController.java         # 透传 rerank
│       ├── QaController.java                # 透传 rerank
│       └── ChunkView.java                   # + 可空 rerankScore
├── tenant/resource/
│   ├── TenantResourceRouter.java            # + hasPrimary(tenant, type)
│   └── provider/
│       └── RerankModelResourceProvider.java # RERANK_MODEL → ScoringModel
└── infrastructure/mp/entity/
    └── RetrievalObservation.java            # + detail

src/main/resources/
├── db/migration/postgresql/V9__retrieval_observation_detail.sql
└── application.yml                          # unirag.retrieval.rerank.* 示例/默认值

src/test/java/com/unirag/retrieval/
├── rerank/
│   ├── RerankEnhancementTest.java
│   └── BgeRerankerScoringModelTest.java
├── RerankApiIT.java                         # API + 观测 + 租户隔离
└── AbstractRetrievalIT.java                 # + stubRerankModel 辅助方法

web/src/                                      # 不改动；现有 normalizeCitation 会忽略 rerankScore
```

**Structure Decision**：复用既有 `com.unirag.retrieval` 分层：`rerank/` 放策略与适配器，`observation/` 放观测扩展，`pipeline/` 只加阶段枚举，`web/` 只加可选字段与透传；资源 Provider 放既有 `tenant/resource/provider/`。不新建持久化层，不新建前端页面。

## Phase 0 / Phase 1 Outputs

- [research.md](./research.md)：R1–R10，全部技术未知项已决策。
- [data-model.md](./data-model.md)：领域字段、`detail` 列、观测行、校验规则。
- [contracts/retrieval-api-delta.md](./contracts/retrieval-api-delta.md)：`/retrieve`、`/ask` 增量请求/响应与失败语义。
- [contracts/rerank-model.md](./contracts/rerank-model.md)：`RERANK_MODEL` 配置、`ScoringModel` 适配器、扩展/观测/指标契约。
- [quickstart.md](./quickstart.md)：自动化与手工验证场景。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违反项。本 feature 未新增第三方依赖，未新增核心流水线阶段，未新建持久化表；`detail` 列是为 FR-011 的 shadow 顺序持久化而增加的单个可空列，避免独立高基数表。

## Risks & Mitigations

| 风险 | 缓解 |
|---|---|
| 重排服务延迟高，拖慢问答 | 3s 超时 + FAIL_OPEN + `unirag.rerank.duration` 指标；召回上限 50 |
| 重排服务返回分数顺序与输入不一致 | 适配器按 `index` 回填；数量/越界/非有限值直接失败回退 |
| 重排误杀相关片段 | 默认 `minScore=0.5` 为保守起点，可配置/关闭；shadow 模式先评估；labels 评测集（SC-001） |
| 未配置租户被扩大召回 | `RerankSupport.enabledFor` 先查 `hasPrimary`；未配置时 `recallTopK=topK` |
| 观测 `detail` 膨胀或泄密 | 只存 ID/分数/keep-drop，2000 字符截断；不含原文/key |
| 既有调用方按 `score` 校验顺序 | 契约显式声明重排后顺序按 `rerankScore`；`score` 语义不变 |
| 提供商协议差异 | 适配器隔离；首期只支持一种 `/rerank` 协议，新增协议不改流水线 |
