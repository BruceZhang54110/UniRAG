# Implementation Plan: RAG 检索问答与全链路可观测

**Branch**: `010-rag-retrieval` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-rag-retrieval/spec.md`

## Summary

交付 RAG 的"检索侧"闭环，共四块：

1. **检索服务与 HTTP 接口**（US1/P1）：用租户自己的嵌入模型把查询向量化，在指定或全部已启用管线的向量表里做相似度检索 + 元数据过滤，跨表按确定性策略归并取 topK，返回带来源元数据的片段。
2. **RAG 问答接口**（US2/P2）：检索片段 → 组装带引用标注的提示词 → 调租户自己的聊天模型生成回答 → 返回回答 + 引用清单；检索为空 / 模型不可达有明确降级语义。
3. **全链路可观测**（US3/P3）：阶段化观测记录统一落库（含 005 摄取侧），聚合指标经 Actuator 暴露。
4. **最小前端打通**（FR-013）：`/chat` 占位页换成可用的最简问答界面。

关键技术路线（来自 2026-10-14 澄清）：检索与问答由**本项目自研的显式阶段流水线**编排（阶段组件 + 观察型/增强型两组扩展契约），不使用 langchain4j 的 `AiServices`/`RetrievalAugmentor` 高层封装，只复用其底层组件（`EmbeddingStore`、`EmbeddingModel`、`OpenAiChatModel`）。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.16；langchain4j 1.20.0（含 `langchain4j-open-ai` 1.20.0、`langchain4j-pgvector` 1.20.0-beta30）；MyBatis-Plus 3.5.17；Flyway；Micrometer（`spring-boot-starter-actuator` 已引入）；Apache OpenDAL 0.50.6；前端 React 19 + Vite 7 + TypeScript + antd 6 + `@lobehub/ui` + react-router 7

**Storage**: PostgreSQL 16 + pgvector 0.8.1；观测记录经新增 Flyway 迁移落库；向量表为按租户动态创建的 `embeddings_*` 表（不受 Flyway 管理，见 004 契约 §4）

**Testing**: `./mvnw test`（含 `*IT` 集成测试，跑真实 PostgreSQL + pgvector）；检索用确定性嵌入替身、问答用替身聊天模型，不依赖外部凭据；前端 `npm run test`（vitest + `@testing-library/react`）

**Target Platform**: 服务端 Linux/macOS（JVM 21）；浏览器端

**Project Type**: web service（后端为主体）+ 轻量前端

**Performance Goals**: 纯检索 < 1 秒、问答 < 10 秒（本地替身模型环境，对应 SC-001）

**Constraints**: 不引入 OpenTelemetry 或外部日志/追踪系统；观测与追踪日志全部持久化到数据库观测表，且写库 MUST 为 best-effort（失败不得影响主链路）；观测记录/日志/错误响应 MUST NOT 含 api-key 或配置原文

**Scale/Scope**: 多租户；单租户可配置多条管线（粗/细双粒度）与多张向量表；新增 3 个 HTTP 端点（检索、问答、观测查询）+ 1 个前端页面

**Unknowns**: **已全部解决**（Phase 0 完成，详见 `research.md`）：
1. langchain4j `Filter` 的 SQL 翻译 —— 经实测确认为 `(metadata->>'key')::<由比较值的 Java 类决定的类型>`，数值键必须传 `Long`/`Integer`；`json` 列不可建直接索引但可建表达式索引（R5）。
2. 聊天模型最简调用 —— 1.20.0 中 `ChatLanguageModel`/`generate` 已移除，改用 `dev.langchain4j.model.chat.ChatModel#chat(ChatRequest)`，字段名 `maxOutputTokens`（R8）。
3. **一处 spec 假设被实测推翻并已回写修正**：分数范围**不是** `[-1,1]`，而是 `[0,1]`（`(1+cosine_similarity)/2`），见 R6 与 spec `Assumptions`。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 feature 的门禁 | 结论 |
|---|---|---|
| I. 架构优先，拒绝冗余堆砌 | 资源解析 MUST 复用 `TenantResourceRouter`；租户定位 MUST 复用 `TenantContext`（其 javadoc 已载明"006 的检索/问答接口复用本组件"）；成功响应 MUST 复用 `ApiResponse`，错误 MUST 走 `GlobalExceptionHandler`；阶段流水线风格 MUST 与既有 `PipelineExecutor` 一致，MUST NOT 另起一套编排。扩展点数量 MUST 由真实用例驱动（重排、日志追踪），MUST NOT 预先堆砌 | PASS |
| II. 规格驱动交付 | 本 plan 与后续 tasks.md 先行；FR-001~014 与 SC-001~006 均有可测判据；spec 中留给 plan 的未决项（归并策略、观测留存、问题文本留存、阈值默认值）在 research.md 中逐条落定 | PASS |
| III. 真实链路集成验证 | 检索/问答的 `*IT` MUST 跑真实 PG + pgvector；嵌入与聊天模型以确定性替身注入；MUST NOT mock 持久层；断言 MUST 覆盖向量表内容、阶段记录行、状态流转，而非仅调用 | PASS |
| IV. 多租户隔离不可妥协 | 检索/问答 MUST 仅在发起租户的资源与向量表内进行；跨租户泄漏用例通过率 MUST 为 100%（SC-002） | PASS |
| V. 敏感信息零泄漏 | 阶段记录与日志 MUST 只含租户、资源 key、表名、原因；MUST NOT 含 api-key 或配置原文；错误响应 MUST 复用既有的"只给可读原因"收敛策略 | PASS |

**初次门禁结论：全部通过，无违反项。**

**设计要求后复评（Phase 1 之后）**：

| 原则 | 复评结论 |
|---|---|
| I. 架构优先，拒绝冗余堆砌 | **PASS** —— 复用面已确认：`TenantResourceRouter`（资源解析）、`TenantContext`（租户定位）、`ApiResponse`/`GlobalExceptionHandler`（信封）、既有 `LlmResourceProvider`（**不新增 Provider**）、既有 `infrastructure.mp` 三层（**不新建持久层**）、既有前端 `api/client.ts`。R3 选择"查询端 SQL 联合投影"而非改造 005 的 `ingestion_run`，正是为**避免改动已被测试覆盖的既有实现**。全 feature 未引入新第三方依赖 |
| II. 规格驱动交付 | **PASS** —— spec 中留给 plan 的四项（归并策略 R4、观测留存 R3、问题文本留存 R3、阈值默认 R7）已逐条落定；R6 发现并**修正了 spec 的一处事实性错误**（分数范围），spec 已回写 |
| III. 真实链路集成验证 | **PASS** —— 新增 R5 的实测依据来自真实库 `uni_rag_db`；`*IT` 方案不变（真实 PG + pgvector、确定性嵌入与替身聊天模型） |
| IV. 多租户隔离不可妥协 | **PASS** —— 数据模型三个索引均以 `tenant_id` 为前缀；观测查询与检索均租户内闭合 |
| V. 敏感信息零泄漏 | **PASS** —— `error_message` 限长 500、`query_digest` 限长 500 且 MUST NOT 含密钥；指标标签排除高基数与敏感字段（R10） |

**唯一非门禁性的已知限制**（不构成违反，已在 research 中记录）：向量表 metadata 无索引（`json` 列限制），过滤走顺序扫描；本期数据规模下可接受，触发条件与备选方案见 research R5。

**实现完成后复评（T045）**：

| 原则 | 复核结论 | 依据 |
|---|---|---|
| I. 架构优先，拒绝冗余堆砌 | **PASS** | 未新增第三方依赖；观测实体走既有 `infrastructure.mp` 三层；租户定位/资源解析/响应信封全部复用既有组件。实现期还主动**消除了一处重复**：把原先私藏在 `ObservationRecorder` 里的脱敏与根因摘要抽成 `Redaction`，因为出现了第 3 个消费方（向量检索、聊天生成），并删掉了 `VectorSearchStage` 中重复的 rootCause/truncate |
| III. 真实链路集成验证 | **PASS** | `./mvnw test` 由基线 57 个测试增至 **117 个，0 失败**，既有测试无一由绿变红；测试全部跑真实 PostgreSQL + pgvector，嵌入与聊天模型用确定性替身、无外部凭据。另做两次**变异实验**反证测试有效性（把 `IsEqual` 的 `Long` 换成 `shortValue` → 复现 research R5 预言的 `(metadata->>'document_id')::null` SQL 错误；移除脱敏 → 4 个用例转红） |
| V. 敏感信息零泄漏 | **PASS（附一处范围外风险）** | 观测表与日志中均查不到真实 api-key 与库密码（`like '%sk-tewjt%'` / `'%StrongPass123%'` 各 0 行），命中的仅是脱敏占位符 `***`。**范围外残留风险**：`src/main/java/com/unirag/ingestion/PipelineExecutor.java:105`（005 既有代码）会把摄取失败根因 message 原样写日志，只截断不脱敏；若某租户的嵌入模型异常信息携带密钥，可能进日志。本轮按范围未改动，**建议后续单独修** |

## Project Structure

### Documentation (this feature)

```text
specs/010-rag-retrieval/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出（/speckit-plan）
├── data-model.md        # Phase 1 输出（/speckit-plan）
├── quickstart.md        # Phase 1 输出（/speckit-plan）
├── contracts/           # Phase 1 输出（/speckit-plan）
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成，本命令不产出）
```

### Source Code (repository root)

```text
src/main/java/com/unirag/
├── retrieval/                              # 本 feature 新增顶层包（与 ingestion/upload 平级）
│   ├── pipeline/                           # 阶段流水线与两类扩展契约
│   │   ├── Stage.java                      # 阶段接口（I → O）
│   │   ├── Pipeline.java                   # 有序阶段编排 + 扩展点执行
│   │   ├── StageContext.java               # 链路标识/租户/资源 key/共享数据
│   │   ├── ObservationExtension.java       # 观察型契约（只读、无序、数量不限）
│   │   ├── EnhancementExtension.java       # 增强型契约（可写、有序、数量受控）
│   │   └── FailurePolicy.java              # FAIL_OPEN / FAIL_CLOSED
│   ├── stage/                              # 五个核心阶段
│   │   ├── QueryEmbeddingStage.java        # 查询向量化
│   │   ├── VectorSearchStage.java          # 逐表检索（含单表故障隔离）
│   │   ├── MergeStage.java                 # 跨表确定性归并 + topK
│   │   ├── PromptAssemblyStage.java        # 提示词组装（带引用标注）
│   │   └── ChatGenerationStage.java        # 模型调用
│   ├── RetrievalService.java               # US1 编排入口
│   ├── QaService.java                      # US2 编排入口
│   ├── model/                              # 领域模型（请求要素/片段/回答）
│   └── observation/
│       ├── ObservationRecorder.java        # best-effort 落库 + 降级记录
│       └── ObservationMetrics.java         # Micrometer 计数器与计时器
├── retrieval/web/                          # HTTP 层
│   ├── RetrievalController.java            # POST /api/v1/retrieve
│   ├── QaController.java                    # POST /api/v1/ask
│   └── ObservationController.java          # GET  /api/v1/observations
└── infrastructure/mp/                      # 复用既有三层，只新增观测实体
    ├── entity/RetrievalObservation.java
    ├── mapper/RetrievalObservationMapper.java
    └── service/(impl/)RetrievalObservationService.java

src/main/resources/
├── db/migration/postgresql/V8__retrieval_observation.sql   # 新增（观测表）
└── mapper/RetrievalObservationMapper.xml                   # 观测统一查询入口 SQL

web/src/
├── api/retrieval.ts                        # 新增：检索/问答客户端（复用 api/client.ts）
└── features/chat/
    ├── ChatPage.tsx                        # 替换 ChatPlaceholderPage：最小问答界面
    └── __tests__/ChatPage.test.tsx          # 加载/空结果/失败三态
```

**Structure Decision**: 后端新增顶层包 `com.unirag.retrieval`，沿用既有"按能力分包"约定（`ingestion` / `upload` / `storage` / `tenant`）；观测实体与查询走既有 `infrastructure.mp` 三层，**不新建持久层**。前端只动 `web/src/features/chat/` 与 `web/src/api/`，复用既有 `client.ts`（响应解包）、`errors.ts`、`content/zh-CN.ts`（文案）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违反项。本 feature 未引入新的第三方依赖，未新建持久化层，未产出与既有能力重复的实现。
