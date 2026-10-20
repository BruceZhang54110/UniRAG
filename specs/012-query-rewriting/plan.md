# Implementation Plan: 查询改写（Query Rewriting）

**Branch**: `012-query-rewriting` | **Date**: 2026-10-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-query-rewriting/spec.md`

## Summary

在既有阶段流水线上引入**租户级、可灰度、可降级**的查询改写：在 `QUERY_EMBEDDING` 之前通过 `EnhancementExtension.beforeStage` 调用 LangChain4j `QueryTransformer`，把用户问题转成一条更适合语义检索的 `retrievalQuery`；`RetrievalRequest.query` 始终保持用户原问题，供 `PromptAssemblyStage`、`RerankEnhancement` 与响应回显使用。

改写能力以新的租户资源 `QUERY_REWRITE_MODEL` 显式开启；该资源**默认复用租户主语言模型（对话/查询大模型）**，配置为空对象即可，只有需要独立模型/凭据/超时时才配置专用连接。未配置该资源的租户零额外调用、行为逐字段不变。改写失败/输出非法时回退原问题（fail-open），以独立 `QUERY_REWRITE` 观测行和指标留痕。多轮压缩、多查询扩展、step-back 与 HyDE 不在本期范围（详见 [research.md](./research.md) R8）。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.5.16；langchain4j 1.20.0（`dev.langchain4j.rag.query.transformer.QueryTransformer`、`ChatModel`/`OpenAiChatModel`）；MyBatis-Plus；Micrometer；Flyway。**不新增依赖**。

**Storage**: PostgreSQL + pgvector。本 feature **无数据库迁移**：复用 `tenant_resource_config`（新 `resource_type=QUERY_REWRITE_MODEL`）与 `retrieval_observation`（`stage=QUERY_REWRITE`，`detail` 已存在）。

**Testing**: JUnit 5 + Mockito + MockMvc；集成测试使用真实本地 PostgreSQL（`AbstractPostgresIntegrationTest`），确定性 `QueryTransformer` / `EmbeddingModel` / `ChatModel` / `ScoringModel` 替身；`./mvnw test` 同时执行 `*Test` 与 `*IT`，不需要外部凭据。

**Target Platform**: JVM 服务端（macOS/Linux 开发与部署环境）。

**Project Type**: Web 服务（单 Spring Boot 后端 + 不改变的 Vite 前端）。

**Performance Goals**: 改写额外延迟 p95 ≤ 800ms；专用改写资源调用超时上限 1.5s，复用主语言模型时沿用该 LLM 的超时配置（部署建议 ≤1.5s）；启用改写后问答端到端 p95 ≤ 3.5s，纯检索 p95 ≤ 1.5s（不含冷启动）。

**Constraints**:

- 改写只替换 `retrievalQuery`，MUST NOT 改变 `query`、提示词、重排 query 或响应回显。
- 每个请求最多一条检索查询；不读取会话历史；不做多路检索。
- 改写资源默认复用租户主语言模型；专用模型是可选的覆盖，不是启用的前提。
- 改写失败/输出无效 MUST 回退原问题，MUST NOT 返回 5xx。
- 租户隔离：只使用发起租户的改写资源、主语言模型与凭据；日志/观测零密钥。
- 无资源/关闭时零额外模型调用、响应逐字段与改造前一致。

**Scale/Scope**: 租户级可选资源；一次检索/问答最多一次改写调用；前端零改动。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 说明 |
|---|---|---|
| I. 架构优先，拒绝冗余堆砌 | PASS | 复用 `EnhancementExtension`、`TenantResourceProvider`、`TenantResourceRouter`、`ObservationExtension`、`ObservationMetrics`；只新增一个 `QueryTransformer` 实现与一个资源类型，无重复实现。新资源类型作为租户级 opt-in，默认复用主语言模型，专用模型只是可选覆盖，理由见 Complexity Tracking。 |
| II. 规格驱动交付 | PASS | 设计逐条对应 spec FR-001..FR-014 / SC-001..SC-008；未决项无 `[NEEDS CLARIFICATION]`；任务将在 `/speckit-tasks` 中追溯到本计划。 |
| III. 真实链路集成验证 | PASS | `QueryRewriteQaIT` / `QueryRewriteRetrievalIT` / `QueryRewriteObservationIT` / `QueryRewriteShadowIT` 使用真实 PostgreSQL + pgvector，确定性替身注入；断言覆盖“嵌入收到改写文本”“提示词收到原问题”“观测行字段”“shadow 不改返回”“跨租户隔离”。 |
| IV. 多租户隔离不可妥协 | PASS | 改写资源经 `TenantContext` 的租户码解析；不引入共享模型/缓存；跨租户用例断言 A 的资源与结果不被 B 使用。 |
| V. 敏感信息零泄漏 | PASS | 资源配置仅经 Provider 解析；日志只含租户/资源 key/模型来源/状态；`Redaction` 收敛异常与摘要；`detail` 只存长度 + 脱敏短摘要；`primary-llm` 模式不在日志/观测中输出主模型凭据；测试断言不含 `api-key`。 |

**Post-Design Re-check（Phase 1 完成后）**：PASS。无新增表/迁移；无编译期循环依赖（Provider 组合 `LlmQueryTransformer` 并通过 `Supplier` 懒解析主语言模型，`LlmQueryTransformer` 只依赖 langchain4j，与 011 的 `RerankModelResourceProvider → BgeRerankerScoringModel` 结构一致）；前端零改动；无跨层调用。

## Project Structure

### Documentation (this feature)

```text
specs/012-query-rewriting/
├── plan.md                      # 本文件
├── research.md                  # Phase 0：技术决策与 LangChain4j 能力取舍
├── data-model.md                # Phase 1：请求/结果/资源/观测/配置模型
├── quickstart.md                # Phase 1：验证与手工联调指南
├── contracts/
│   ├── query-rewrite.md         # 资源、QueryTransformer、扩展、观测、指标、配置契约
│   └── retrieval-api-delta.md   # /retrieve、/ask 的 rewrite 字段增量
├── checklists/
│   └── requirements.md          # 规格质量检查
└── tasks.md                     # Phase 2 输出（/speckit-tasks 生成，本命令不创建）
```

### Source Code (repository root)

```text
src/main/java/com/unirag/
├── retrieval/
│   ├── model/
│   │   ├── RetrievalRequest.java          # +rewrite、+retrievalQuery、+withRewrite/withRetrievalQuery
│   │   ├── QaRequest.java                 # +Boolean rewrite
│   │   └── RetrievalConstraints.java      # +retrievalQuery 校验（≤500 且非空）
│   ├── web/
│   │   ├── RetrieveRequest.java           # +Boolean rewrite
│   │   └── AskRequest.java                # +Boolean rewrite
│   ├── query/                             # 新增包
│   │   ├── QueryRewriteEnhancement.java   # QUERY_EMBEDDING.beforeStage 扩展
│   │   ├── QueryRewriteSupport.java       # 全局开关 + 租户资源存在性
│   │   ├── QueryRewriteProperties.java    # @ConfigurationProperties
│   │   ├── QueryRewriteOutcome.java       # 上下文结果摘要
│   │   ├── RewriteValidator.java          # 确定性输出校验
│   │   └── LlmQueryTransformer.java       # QueryTransformer 单查询实现
│   ├── observation/
│   │   ├── QueryRewriteObservationExtension.java  # 新增
│   │   └── ObservationMetrics.java        # +recordQueryRewrite
│   ├── stage/
│   │   └── QueryEmbeddingStage.java       # 用 retrievalQuery 向量化
│   ├── RetrievalService.java              # 解析 rewrite 意图 + 租户资源
│   ├── QaService.java                     # 解析 rewrite 意图 + 租户资源
│   └── pipeline/
│       └── StageName.java                 # +QUERY_REWRITE（仅观测）
├── tenant/resource/
│   ├── ResourceType.java                  # +QUERY_REWRITE_MODEL("queryRewriteModel")
│   ├── TenantResourceRouter.java          # +primaryKey(tenant, type)
│   └── provider/
│       └── QueryRewriteModelResourceProvider.java  # 新增
src/main/resources/application.yml         # +unirag.retrieval.query-rewrite.*

src/test/java/com/unirag/retrieval/
├── query/
│   ├── QueryRewriteEnhancementTest.java   # 新增（单元）
│   └── LlmQueryTransformerTest.java       # 新增（单元）
├── QueryRewriteQaIT.java                  # 新增（US1，真实 DB）
├── QueryRewriteRetrievalIT.java           # 新增（US2，真实 DB）
├── QueryRewriteObservationIT.java         # 新增（US3，真实 DB）
├── QueryRewriteShadowIT.java              # 新增（US3 观察模式，真实 DB）
└── AbstractRetrievalIT.java               # +确定性 QueryTransformer 替身 helper
```

**Structure Decision**: 单 Spring Boot 项目；后端按既有 `retrieval/{model,stage,pipeline,observation,web}` 分层，新增 `retrieval/query` 承载改写策略与校验；租户资源沿用 `tenant/resource/provider`。前端 `web/` 不改动。

## Implementation Approach

1. **请求模型与 API 开关**：扩展 `RetrievalRequest`（`rewrite`、`retrievalQuery`、兼容构造器与副本方法）、`QaRequest`、`RetrieveRequest`、`AskRequest`；服务层把请求意图与租户资源解析成实际布尔值。
2. **租户资源与改写器**：新增 `QUERY_REWRITE_MODEL` 资源类型、Provider 与 `LlmQueryTransformer`；资源配置为空时运行时解析租户主 `LLM`（`primary-llm`），提供完整连接字段时构造专用 `OpenAiChatModel`（`dedicated`）并校验 timeout/temperature/max-tokens；`TenantResourceRouter` 增加只读 `primaryKey`。
3. **流水线扩展与校验**：`QueryRewriteEnhancement` 在 `QUERY_EMBEDDING.beforeStage` 执行；`RewriteValidator` 做空/多查询/多行/长度/约束丢失校验；成功替换 `retrievalQuery`，失败/观察模式回退原问题。
4. **观测与指标**：`StageName.QUERY_REWRITE`、`QueryRewriteOutcome`、`QueryRewriteObservationExtension`、`ObservationMetrics.recordQueryRewrite`；复用 `retrieval_observation.detail`。
5. **配置**：`QueryRewriteProperties` + `application.yml` 的 `unirag.retrieval.query-rewrite.*`；无数据库迁移。
6. **测试**：单元测试覆盖校验/回退/观察模式；集成测试覆盖嵌入输入、提示词原问题、重排原问题、观测行、无资源与跨租户。

## Complexity Tracking

| 新增复杂度 | 为什么需要 | 更简单的替代方案为何不够 |
|---|---|---|
| 新增租户资源类型 `QUERY_REWRITE_MODEL` | 作为租户级 opt-in；默认复用主语言模型（配置可为空），需要时可覆盖为独立模型/凭据/超时；既有租户行为完全不变 | 不建资源、只要租户有 `LLM` 就自动改写：所有问答租户默认行为都会改变，无法按租户灰度；强制专用模型：迫使租户重复录入已有凭据，违背“默认使用查询大模型” |
| `RetrievalRequest` 增加 `retrievalQuery` / `rewrite` | 明确区分“用户原问题”与“检索文本”，让提示词/重排/响应天然读取正确字段，并支持请求级开关 | 改写时直接覆盖 `query`、把原问题藏进 `StageContext`：隐式状态易取错，响应会回显改写文本，未来阶段也容易误用 |
| 新增 `LlmQueryTransformer` + `RewriteValidator` | 严格单查询输出与确定性校验；实现 LangChain4j `QueryTransformer` SPI，便于未来替换策略 | `ExpandingQueryTransformer(n=1)`：语义是扩展、提示词/解析弱、输出校验不足；直接在扩展里调 `ChatModel`：丢失 SPI 且校验与调用耦合 |
| 新增 `QUERY_REWRITE` 观测阶段 | 与 `RERANK` 一致，可独立查询/告警/灰度，不污染 `QUERY_EMBEDDING` 行 | 塞进 `QUERY_EMBEDDING.detail`：语义混杂、按 stage 查询困难；新建表：为一个可选优化引入新持久化模型，违背原则 I |
