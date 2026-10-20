# Research: 文档摄取与双粒度切片

**Feature**: 文档摄取与双粒度切片 | **Date**: 2026-10-01 | **Plan**: [plan.md](./plan.md)

Phase 0 决策记录。技术事实均来自 langchain4j 1.20.0 本地 jar 核实与官方 RAG 教程（docs.langchain4j.dev/tutorials/rag，2026-10-01 抓取）。

## R1: 摄取编排——自编排而非 EmbeddingStoreIngestor 黑盒

**事实**：`EmbeddingStoreIngestor`（core jar 确认存在）封装"splitter→transformer→embedAll→store"，但 `IngestionResult` 只含 token 用量统计，**不返回写入的 embedding id**；而本 feature 要求每个片段能回链业务记录（FR-003）。

**Decision**: 自编排 PipelineExecutor：`DocumentSplitter.split` → TextSegment 附加契约 metadata → `EmbeddingModel.embedAll` → `EmbeddingStore.addAll(embeddings, segments)`（**返回 embedding id 列表**）→ 落 document_chunk 行。构件与默认值惯例复用官方摄取管线（300/30 为参照，生产默认 plan 任务定）。

**Alternatives considered**: 用 Ingestor 再反向查向量表拿 id——拒绝（竞态且依赖表扫描）；放弃回链——拒绝（违反 FR-003/SC-002）。

## R2: 管线配置的持久化形态——独立业务表，不走 003 资源体系

**Decision**: 新增 `ingestion_pipeline` 业务表（Flyway V4 + MyBatis-Plus），列：strategy、max_segment_tokens、overlap_tokens、embedding_store_key、embedding_model_key、enabled。

**Rationale**: 003 资源体系的语义是"可构建运行时实例的声明"（Provider→Bean）；摄取管线不产生 Bean，是摄取服务的业务配置，且切片参数需要结构化可查询（JSONB 不利于按参数检索/校验）。保持 003 类型语义的纯净，也为 006 检索侧"按管线 key 过滤"提供一等公民配置。

**Alternatives considered**: `resource_type = INGESTION_PIPELINE` 新类型——拒绝（无 Provider 可构建，会被 003 加载流程记 WARN 跳过，语义别扭）；纯内存配置——拒绝（重启丢失，违反可观测/可追溯）。

## R3: 切片策略——递归基线 + 可插拔字段化（含 2026 领域共识）

**事实**（本地 jar + 官方教程）：`DocumentSplitters.recursive(maxSize, maxOverlap, tokenizer)` 为层级递归（段落→句子→词），`DocumentSplitter` 是 SPI 接口；Easy RAG 默认 300 token/30 overlap。

**Decision**: 管线 `strategy` 字段（v1 仅 `RECURSIVE`），`DocumentSplitterFactory` 映射策略→实现；参数 maxSegmentSize/maxOverlap 存管线表。默认值：max 500 token / overlap 50 token（介于 easy-rag 300/30 与常见 512/64 之间，plan 后可用真实模型校准）。

**领域共识固化**（用户确认随 research 固化）：递归切分是 2026 主流**基线**而非最先进水平（语义切分、late chunking、contextual retrieval 各有收益与成本，基准结果不稳定）；**升级路径 = 检索质量指标不达标时新增策略实现**，不在本期预判实现。切片策略字段化使升级 = 新增 enum + Factory 分支，不动主流程。

**Tokenizer 选型**：`OpenAiTokenCountEstimator`（langchain4j-open-ai 已依赖，tiktoken 计量；对中文为子词近似，可接受）。中文专精 tokenizer（如字数为基）作为 `IngestionTokenizer` 封装内的可替换实现，本期不引入。

## R4: 文档指纹——SHA-256

**Decision**: `content_fingerprint = SHA-256(内容 UTF-8 字节)` hex 串，存 document 行。

**Rationale**: 同内容必同指纹（spec FR-006 要求）；SHA-256 冲突概率可忽略（满足 spec 假设）；JDK 内置无需新依赖。

**语义**: 同 (tenant, doc_name) 且指纹相同 → no-op（返回成功，数据不动，满足 US3"不翻倍"）；指纹不同 → 替换（见 R6）。

## R5: 并发与唯一性

**Decision**: 唯一约束 `document(tenant_id, doc_name)`（业务文档名租户内唯一）；`ingestion_pipeline(tenant_id, pipeline_key)` 唯一。应用内对 `(tenantCode, docName)` 串行化（JVM 内锁；单实例部署前提，多实例演进归后续）。

**Rationale**: 唯一约束是并发的最终裁决者（两个并发摄取同一文档时，后者在 upsert 边界等待/失败重试）；锁消除同实例内绝大多数竞态。spec FR-007"确定性收尾"由唯一约束 + 替换语义保证。

## R6: 替换语义与故障清理边界

**Decision**: 每条管线内"先清后写"：
1. 删除本 (document, pipeline) 旧 document_chunk 行，并按旧行的 embedding_id 调 `EmbeddingStore.removeAll(ids)` 删旧向量；
2. 切分新内容 → embedAll → addAll → 写新 chunk 行（含新 embedding_id）。

管线中途失败 → best-effort removeAll 本 run 已写向量，ingestion_run 记 FAILED + 原因；已成功的其他管线不受影响；文档状态汇总为 INGESTED（全部成功）/ PARTIAL（部分成功）/ FAILED（全部失败）。**管线之间无共享事务**（跨向量表/库无法单事务），幂等重试安全（重试回到"先清后写"）。

**进程重启恢复**：启动时对滞留 INGESTING 超过 5 分钟的 document/run 标记 FAILED（原因 "interrupted"），允许重新摄取（spec 边界情况）。

## R7: 向量 metadata 契约（与 006 的衔接）

**Decision**: 每个写入向量表的片段 metadata 固定四键：`doc_name`（文档名）、`document_id`（业务表 id，long）、`pipeline_key`（粒度标识）、`chunk_index`（片段序号）。**不放 tenant**——表即租户边界（004 设计），避免冗余与不一致面。

**Rationale**: 006 检索按文档/粒度过滤依赖此契约（PgVector COMBINED_JSON 模式支持 Filter API，004 契约 §3 已确认）；document_id 使检索结果可精确回链业务表。

## R8: 观测衔接（006 伏笔）

**Decision**: 005 的 `ingestion_run`（每文档×每管线一条：状态/片段数/原因/起止时间）已满足 US3 可查要求；006 做统一观测模型时，以 ingestion_run 为摄取侧阶段条目的来源，**不改 005 表结构**，在其上提供统一查询入口。

## 遗留未知项

无。所有 NEEDS CLARIFICATION 已解决。
