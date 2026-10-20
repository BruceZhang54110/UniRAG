---
description: "Task list for feature implementation: 文档摄取与双粒度切片"
---

# Tasks: 文档摄取与双粒度切片

**Input**: Design documents from `/specs/005-document-ingestion/`（plan.md / spec.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**Tests**: 包含测试任务。依据：spec FR-009（真实库集成测试 + 确定性嵌入模型替身）；沿用项目约定——测试先行（先写、确认失败、再实现）、真实本地开发库 `uni_rag_db` + 每测试类全局唯一租户码。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）
- 所有任务均含确切文件路径

## Path Conventions

单项目结构：`src/main/java/com/unirag/`、`src/test/java/com/unirag/`（与 plan.md 一致）

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: V4 迁移与测试替身，解锁后续所有工作

- [x] T001 [P] 新建迁移 `src/main/resources/db/migration/postgresql/V4__ingestion_schema.sql`：按 data-model.md 建 `ingestion_pipeline` / `document` / `document_chunk` / `ingestion_run` 四表（含 FK、索引、唯一约束 `(tenant_id, pipeline_key)` 与 `(tenant_id, doc_name)`），软删除列沿用 003 约定
- [x] T002 [P] 新建测试替身 `src/test/java/com/unirag/ingestion/DeterministicEmbeddingModel.java`：实现 langchain4j `EmbeddingModel`，文本 → 固定维度向量的确定性映射（如各 token 哈希槽位累加后归一化），支持 embedAll 分批；维度可配置（1024/768 等），使双粒度不同维度用例可复用

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 持久化层与摄取领域骨架——所有用户故事的前置

**⚠️ CRITICAL**: 本阶段完成前不可开始任何用户故事

- [x] T003 [P] 持久化层实体 + Mapper + Service（MyBatis-Plus，参照 `src/main/java/com/unirag/infrastructure/mp/` 既有模式）：`entity/{IngestionPipeline,Document,DocumentChunk,IngestionRun}.java`（字段与驼峰映射按 data-model.md；JSON 无需 TypeHandler，全为标量列）、对应 Mapper 接口与 XML、`service` 接口与 `impl`（含 `findEnabledByTenant`、`findByTenantAndName` 等 data-model 查询）
- [x] T004 [P] 新建 `src/main/java/com/unirag/ingestion/support/DocumentFingerprint.java`：`sha256(String content)` 静态方法返回 hex（research R4），JDK MessageDigest 实现
- [x] T005 [P] 新建 `src/main/java/com/unirag/ingestion/IngestionTokenizer.java`：封装 `OpenAiTokenCountEstimator`（langchain4j-open-ai 已依赖）的 token 计数（research R3），构造可传模型名，提供 `estimateTokens(String)`；类 Javadoc 注明中文为子词近似与替换路径
- [x] T006 [P] 新建 `src/main/java/com/unirag/ingestion/DocumentSplitterFactory.java`：`strategy` 字符串 → `DocumentSplitter` 映射（v1 仅 `RECURSIVE` → `DocumentSplitters.recursive(max, overlap, tokenizer)`），未知策略抛 `IllegalArgumentException`（信息含策略名）
- [x] T007 新建 `src/main/java/com/unirag/ingestion/PipelineExecutor.java`：单管线执行（research R6）——按管线配置经 `TenantResourceRouter` 解析 `EmbeddingModel` 与 `EmbeddingStore` Bean → "先清后写"（删旧 chunk 行 + `removeAll(旧 embedding_id)`）→ `split` 得片段并附加契约四键 metadata（doc_name/document_id/pipeline_key/chunk_index）→ `embedAll` → `addAll` 返回 embedding_id → 写 document_chunk 行 → 写 ingestion_run（SUCCESS/FAILED + 截断原因）；中途失败 best-effort removeAll 本 run 已写 id；**异常信息不含密钥**
- [x] T008 新建摄取入口三件套 `src/main/java/com/unirag/ingestion/IngestionService.java` / `IngestRequest.java` / `IngestOutcome.java`：实现 contracts §1 契约——参数校验（空内容/缺 tenant 抛 IllegalArgumentException）、`(tenant, docName)` JVM 内串行锁、指纹 no-op 短路（R4/R5）、逐管线调 PipelineExecutor、汇总文档状态（INGESTED/PARTIAL/FAILED/NOOP）与 chunk_count、version 语义；`@Service` 或经 `TenantResourceAutoConfiguration` 风格装配（与 003 一致）

**Checkpoint**: 领域骨架就绪；003/004 既有测试零回退（`./mvnw test` 全绿）

---

## Phase 3: User Story 1 - 文档按租户配置切片入库 (Priority: P1) 🎯 MVP

**Goal**: 单管线摄取完整闭环：切片→向量化→写租户向量表→元数据回链（spec US1，FR-001/003，SC-002）

**Independent Test**: `DocumentIngestionIT`（真实库 + 确定性替身模型）

### Tests for User Story 1 ⚠️（先写，确认失败后再实现）

- [x] T009 [US1] 新建 `src/test/java/com/unirag/ingestion/DocumentIngestionIT.java`（继承 `AbstractPostgresIntegrationTest`，租户码 `ingUS1`/`ingPeerUS1`）：两租户各配 EMBEDDING_STORE（表 `embeddings_ingus1`/`embeddings_ingpeerus1`）+ EMBEDDING_MODEL 资源（测试替身经 `@MockBean`/条件装配替换——见 Notes）+ 一条管线（上限 500/重叠 50）→ 摄取 5000 字中文长文档（多段落）→ 断言——场景 1：document_chunk 行数 = 向量表行数；逐片 `embedding_id` 非空且向量表对应记录 metadata 恰含四键且值与本行一致（契约 §4 双向回链）；片段 token 数 ≤ 上限；场景 2：对方租户摄取同类文档后，检索本方表只命中本方片段；场景 3：短文档（<上限）产生恰好 ≥1 片；场景 4：摄取后 document.status=INGESTED、chunk_count 与实际一致、ingestion_run SUCCESS 一条

### Implementation for User Story 1

-（实现已在 Foundational T003–T008 完成；本故事验收驱动其正确性）

**Checkpoint**: US1 独立可用——单管线摄取闭环、回链双向一致、跨租户隔离（quickstart 场景 1 的 US1 部分）

---

## Phase 4: User Story 2 - 粗/细双粒度（多管线）同时摄取 (Priority: P2)

**Goal**: 一份文档一次调用、多条管线并行产出多表数据（spec US2，FR-001/004，SC-003）

**Independent Test**: `DualGranularityIngestionIT`（粗/细两表 + 一失败管线隔离）

### Tests for User Story 2 ⚠️

- [x] T010 [P] [US2] 新建 `src/test/java/com/unirag/ingestion/DualGranularityIngestionIT.java`（继承基类，租户码 `dualUS2`）：配 coarse（上限 500，表 `embeddings_dualus2_coarse`）与 fine（上限 150，表 `embeddings_dualus2_fine`）两条管线 → 摄取长文档 → 验收场景 1：两表均有数据；粗表片段数 < 细表片段数；粗表平均 token > 细表；两表 metadata 的 pipeline_key 各为其 key；一次调用 outcome.runs 含两条；验收场景 3：加第三条管线 `brokenUS2` 指向不存在的 EMBEDDING_STORE key → 摄取 → broken 管线 run FAILED（原因含 key）且 coarse/fine 照常 SUCCESS，文档 status=PARTIAL，三表状态互不干扰
- [x] T011 [P] [US2] 同文件追加双维度用例（独立 @Test，租户码 `dimUS2`）：coarse（维度 1024）与 fine（维度 768，两表 vector 维度不同）各配独立 EMBEDDING_MODEL 资源 key → 摄取 → 两表各自维度正确、数据互不串（验收场景 2，配合 T002 替身维度可配）

### Implementation for User Story 2

-（实现已在 Foundational 完成；本故事验收驱动其正确性）

**Checkpoint**: 双粒度一次调用就绪；单管线故障隔离验证（quickstart 场景 1 的 US2 部分）

---

## Phase 5: User Story 3 - 可观测、故障隔离与重复摄取语义 (Priority: P3)

**Goal**: no-op/替换/并发确定性收尾与执行可查（spec US3，FR-005/006/007，SC-004）

**Independent Test**: `IngestionReplaceAndFailureIT`

### Tests for User Story 3 ⚠️

- [x] T012 [P] [US3] 新建 `src/test/java/com/unirag/ingestion/IngestionReplaceAndFailureIT.java`（继承基类，租户码 `repUS3`）：① 摄取文档 → 同内容二次摄取 → 断言 status=NOOP、version 不变、chunk 行数与向量行数不翻倍（FR-006）；② 修改内容后重摄取 → version=1→2、旧 chunk 行与旧向量已删（向量表旧 embedding_id 不存在）、新片段生效；③ 两线程并发摄取同 (tenant, docName) 同内容 → 结束后行数确定（不翻倍）、仅一个 version 增量；④ 故障恢复：摄取中途失败（broken 表场景已覆盖于 US2；此处验证 run 记录可查——ingestion_run 含 FAILED 条目与原因，原因 ≤500 字符且无密钥串）
- [x] T013 [US3] 启动恢复任务：新建 `src/main/java/com/unirag/ingestion/StaleIngestionRecovery.java`（`ApplicationRunner`，低优先级 @Order）：启动时把滞留 INGESTING 超过 5 分钟的 document 与其未完结 run 标记 FAILED（原因 "interrupted"）（research R6）；IT 验证：JdbcTemplate 直接置一文档为 INGESTING 且 started_at 为 6 分钟前 → 启动恢复逻辑（或调用其方法）→ 状态 FAILED、原因可读

### Implementation for User Story 3

- [x] T014 [US3] T013 的实现在本阶段完成（唯一新增实现任务）

**Checkpoint**: 重摄取语义、并发确定性与启动恢复全部验证（quickstart 场景 3）

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 端到端验证与收尾

- [x] T015 全量 `./mvnw test`：25（003/004 既有）+ 本期新增用例全绿零回退；`grep -rn "api-key\|password" target/surefire-reports` 抽查无凭据泄漏（FR-010）
- [x] T016 [P] 按 `quickstart.md` 场景 2 手工端到端复核（spring-boot:run + 插管线配置 + 触发摄取 + 库内 SQL 抽查回链/粒度/状态），与自动化 IT 互为印证
- [x] T017 更新 `README.md`：摄取用法简述（管线配置 + IngestionService 调用示例 + contracts 链接），链接 specs/005 文档

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始（T001 ∥ T002）
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有用户故事**。T003–T006 互相并行；T007 依赖 T003/T005/T006；T008 依赖 T004/T007
- **User Stories (Phase 3-5)**: 全部依赖 Foundational；故事间无相互依赖（纯验收驱动）
- **Polish (Phase 6)**: 依赖 US1–US3 完成

### User Story Dependencies

- **US1/US2/US3**: 均可独立开始（实现已内聚于 Foundational）；测试 T009–T013 互相独立可并行起草

### Within Each User Story

- 测试先行（T009/T010/T011/T012 先于实现调整并确认失败）
- US3 含唯一新增实现任务（T013/T014 启动恢复）

### Parallel Opportunities

- T001 ∥ T002；T003 ∥ T004 ∥ T005 ∥ T006
- T009 ∥ T010 ∥ T011 ∥ T012（不同文件、不同租户码；T013 依附 T012 文件不同类可并行）

---

## Parallel Example: Foundational

```bash
Task: "T003 持久化层 4 实体/Mapper/Service"
Task: "T004 DocumentFingerprint"
Task: "T005 IngestionTokenizer"
Task: "T006 DocumentSplitterFactory"
# T007（PipelineExecutor）→ T008（IngestionService）随后串联
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 → Phase 2（T003–T008）→ T009 → **STOP and VALIDATE**：单管线闭环 + 回链验证通过

### Incremental Delivery

1. Setup + Foundational → 摄取骨架就绪
2. + T009 → 单管线闭环（MVP）
3. + T010/T011 → 双粒度（US2）
4. + T012–T014 → 重摄取/并发/恢复（US3）
5. Polish → 手工复核 + 文档

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- 验证标准：每个 Checkpoint 处 `./mvnw test` 全绿（前置：本地 PG 运行中、uni_rag_db 已装 pgvector、V4 迁移随上下文自动执行）
- 测试数据约定：复用开发库 + 每测试类全局唯一租户码（`ingUS1`/`ingPeerUS1`/`dualUS2`/`dimUS2`/`repUS3`）；向量表命名 `embeddings_<租户码小写>[_粒度]`
- **替身模型装配**：`DeterministicEmbeddingModel` 需替换/包装租户 EMBEDDING_MODEL 资源解析——方案：测试配置把 EMBEDDING_MODEL 资源的 `model-name` 设为特定哨兵值，`EmbeddingModelResourceProvider` 识别哨兵则构建替身（实现期定，保持生产路径零分支亦可接受：测试中直接 @MockBean TenantResourceRouter 按 key 返回替身——优先后者，不污染生产代码）
- 敏感信息红线：ingestion_run.error_message 与任何日志不得含 api-key/password（FR-010），T012 ④ 含断言
- 契约冻结：向量 metadata 四键（doc_name/document_id/pipeline_key/chunk_index）键名本期锁定（contracts §3），006 依赖
