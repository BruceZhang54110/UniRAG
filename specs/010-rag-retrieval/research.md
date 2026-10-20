# Phase 0 研究：RAG 检索问答与全链路可观测

**Feature**: `specs/010-rag-retrieval/` | **Date**: 2026-10-14

本文件落定 spec 中明确留给 plan 阶段的未决项，以及 `/speckit-plan` 的 Technical Context unknowns。
**所有 langchain4j 相关结论均经 jar 反编译 / sources 源码行号 / 本地真实库 `uni_rag_db` 实测三重验证。**

---

## R1. 编排形态：自研显式阶段流水线

**Decision**：检索与问答实现为五段显式流水线（`QUERY_EMBEDDING → VECTOR_SEARCH → MERGE → PROMPT_ASSEMBLY → CHAT_GENERATION`），阶段组件 + 两组扩展契约。**不使用** langchain4j 的 `AiServices` / `RetrievalAugmentor`。

**Rationale**：FR-006 要求**每个阶段**一条结构化记录。`AiServices` 是声明式黑盒，只能通过 `ChatModelListener` 观测到最后一段（模型调用），前四段它不知道我们分了几步 —— 直接无法满足 FR-006。自研流水线使五段全部可见可插拔，且与既有 `PipelineExecutor`（摄取侧）风格一致。

**Alternatives considered**：
- 纯 langchain4j 高层编排 —— 扩展点只有模型级与组件替换级，覆盖不了 FR-006 的阶段粒度。
- 混合（检索自研 + 生成用 `AiServices`）—— 会形成两套扩展机制，"加一个日志追踪"要写两遍，破坏统一可插拔。

---

## R2. 扩展契约与失败语义

**Decision**：见 `contracts/pipeline-extension.md`。观察型（`ObservationExtension`，只读、无序、数量不限、默认 `FAIL_OPEN`）与增强型（`EnhancementExtension`，可写、须 `order()`、默认按性质取策略）分开注册、分开执行。执行序保证：**观察型看到的永远是阶段的最终输入与最终输出**，因此其数据语义不因增强型的有无而改变。

**Rationale**：FR-011 已要求重排作为扩展点，而重排本质是**改写**片段列表，故纯只读契约不够；但把只读与可写混在一条链里，会让"日志里看到的是改写前还是改写后"变得不确定。分开即可消掉该类排序 bug（Servlet 的 Filter 与 Listener 即此模式）。

**Alternatives considered**：统一可读写单契约（排序语义脆弱）；只读契约 + 策略整体替换（无法表达"只改写不做替换"的中间能力，如过滤）。

---

## R3. 观测落库模型

**Decision**：

1. **单表承载**：新增 `retrieval_observation`（见 `data-model.md` §1），用 `kind`（`RETRIEVAL`/`QA`/`INGEST`）+ `stage` 区分，满足 FR-006 的"同一观测模型与查询入口"。
2. **"全部落库"的边界**：**观测与追踪事件**（阶段记录、降级事件、失败原因）全部写库，不依赖外部日志聚合或 OTel；**常规应用运行日志**（启动、配置、框架日志）仍走标准输出，不属于"观测与追踪日志"范畴。
3. **写入策略**：best-effort。写入异常被捕获并降级为进程日志，同时**降级事件本身也要尝试记录**（若连降级都写不进去，只留进程日志）。主链路结果 MUST NOT 因观测写库失败而改变。
4. **005 摄取侧纳入方式**：**查询入口用 SQL 联合投影** `retrieval_observation` 与既有 `ingestion_run`（`RetrievalObservationMapper.xml`），摄取侧代码**零改动**。理由：符合原则 I"接入观测不修改核心实现"；同时历史摄取记录与新增记录都能在同一入口查到（US3 场景 2）。
5. **留存**：新增配置 `unirag.observability.retention`（默认 **30 天**），定时任务按 `created_at` 物理删除过期行；清理量记入日志与指标。
6. **问题文本留存**：`query_digest` 默认留存（供排障），长度上限 500；MUST NOT 存储超出摘要所需的内容，MUST NOT 含密钥（FR-008）。

**Rationale**：单表 + 联合投影是满足"统一入口"的最低成本方案；摄取侧零改动避免了修改 005 已交付并已被测试覆盖的语义。

**Alternatives considered**：三张表（检索/问答/摄取各一）—— 三套查询、三套索引，违反"统一模型"；迁移 `ingestion_run` 进新表 —— 需改动 005 的代码与既有测试，风险高且无收益。

---

## R4. 跨表归并策略（确定性）

**Decision**：
1. 对每张参与检索的向量表，各取 `topK` 条候选（未指定粒度时 N 张表 → N×topK 候选）。
2. 合并后**按 `score` 降序**排序；**同分时按 `(documentId, pipelineKey, chunkIndex)` 升序**兜底。
3. 截断到全局 `topK`。
4. **不做跨表去重**：粗/细粒度的片段文本本就不同，"粗排定位、细排佐证"需要两者同时出现；结果中已带 `pipelineKey` 可区分。

**Rationale**：FR-003 要求"同输入同输出"。纯按分数排序在分数相同时依赖数据库返回顺序，不稳定；加一个全序兜底键即可保证确定性。

**Alternatives considered**：按 `(documentId, chunkIndex)` 分组去重，同组取最高分 —— 会丢掉细粒度佐证，且"同组"定义在跨粒度时并不成立。此能力更适合作为**增强型扩展**在后续提供（正好验证扩展点设计）。

---

## R5. 元数据过滤：langchain4j `Filter` 的真实 SQL 行为 ⭐

**Decision**：过滤一律使用**带类型转换**的 `Filter`（`IsEqualTo` / `IsGreaterThan` / `IsLessThan` 等），**数值键必须传 `Long` 或 `Integer`**（如 `new IsEqualTo("document_id", 620L)`）。

**已实测的事实（`PgVectorEmbeddingStore` → `JSONMetadataHandler` → `JSONFilterMapper`）**：

| 项 | 实测结论 |
|---|---|
| 谓词形态 | `(metadata->>'key')::<type>`（**带**强制转换）；类型由**比较值的 Java 类**决定，不由存储值决定 |
| 类型映射 | `Integer→int`、`Long→bigint`、`Float→float`、`Double→float8`、`String→text`、`UUID→uuid`、`Boolean→boolean` |
| 存储值实际类型 | langchain4j 写入时把 metadata 的**所有值转成 JSON 字符串**（`Utils.toStringValueMap`）。因 `->>` 本身输出 text，`::bigint` 转换照常生效，数值等值与范围比较**均正确** |
| `IsIn` / `IsNotIn` | **不带转换**，纯文本比较（`metadata->>'k' in ('1','2')`）。本项目值恰好存为字符串，等值语义恰好正确，但**不可依赖它做数值范围** |
| 不支持的 Filter | pgvector **支持全部 12 种内置 Filter**（含范围、In/NotIn、ContainsString、And/Or/Not）；`UnsupportedFeatureException` 不会触发 |
| 值类型踩坑 | 传 `Short` / `Byte` / `BigDecimal` / 枚举等不在类型映射表里的类型 → 拼出 `(metadata->>'k')::null` → **SQL 语法错误** |
| 脏数据风险 | 任一行该键为非数字时，`::int` 会让**整条查询报错**（而非跳过该行） |
| 索引 | `json` 列**不能建任何直接索引**（btree/GIN 均报无默认操作符类）；GIN 需 `(metadata::jsonb)`；**表达式索引可以建**（如 `(((metadata->>'document_id')::bigint))`）。langchain4j 明确拒绝对 json 建索引（`JSONMetadataHandler`：`"Indexes are not allowed for JSON metadata, use JSONB instead"`） |
| 当前索引状况 | 因未传 `indexes` 配置，**所有 `embeddings_*` 表目前零 metadata 索引** → 过滤走顺序扫描 |

**对策（写入侧保证）**：本项目所有向量写入都经 `PipelineExecutor`，写入的五个键类型固定（`doc_name`/`pipeline_key` 为字符串，`document_id`/`chunk_index`/`document_version_id` 由代码以数值写入并经 langchain4j 字符串化）。**只要过滤时用匹配的 `Long`/`Integer`，`::bigint`/`::int` 转换不会遇到非数字值。** 该不变量由检索侧 `*IT` 覆盖（含"过滤后不报错"断言）。

**索引决策**：本期**不建** metadata 索引。理由：向量表是按租户动态创建的（004 契约 §4 明确不归 Flyway 管理），在其上建表达式索引需要扩展建表路径或引入新的运维约定；而本期数据规模（单租户粗/细两表）下扫描代价可接受。**记为已知限制**，若后续出现性能问题，可选方案是改 `MetadataStorageMode` 为 `COLUMN_PER_KEY` 或改用 jsonb。

**Alternatives considered**：改用 `COLUMN_PER_KEY`（每键一列，可建常规索引）—— 需要改 004 已交付的 store 构建方式与既有表结构，超出本期范围。改用 HYBRID 模式 —— 见 R9。

---

## R6. 分数语义（**修正 spec 的错误假设**）⭐

**Decision**：`score` 的取值范围是 **[0, 1]**，语义为 `(1 + cosine_similarity) / 2`。

**实测依据**（`PgVectorEmbeddingStore`，VECTOR 模式）：

```sql
SELECT (2 - (embedding <=> :vec)) / 2 AS score, ...
FROM <table>
WHERE round(cast(float8 (embedding <=> :vec) as numeric), 8) <= round(2 - 2 * :minScore, 8) [AND <filter>]
ORDER BY embedding <=> :vec LIMIT :maxResults;
```

- 距离算子硬编码为**余弦 `<=>`**，类 javadoc 明写 "Only cosine similarity is used. Only ivfflat index is used."，**无配置项**（源码中无 `vector_l2_ops` / `vector_ip_ops` / `MetricType`）。
- 自身匹配实测 `distance=0 → score=1`；另一条 `distance=0.6783949 → score=0.6608025`，恰为 `(2-0.6784)/2`。
- 因此 `minScore` 被约束在 `[0,1]`（与 `EmbeddingSearchRequest` 构造器的 `ensureBetween(minScore, 0, 1)` 自洽）。

**对 spec 的修正（已同步执行）**：spec `Assumptions` 原写"余弦相似度语义沿用 004 实测（**score ∈ [-1,1]**，阈值默认取 0）"，**与实测不符**，已改为 `[0,1]` 且说明换算关系。

**对实现的直接影响**：`minScore` **不是** cosine similarity 本身。调用方填 `0.7` 等价于余弦相似度 `0.4`。该换算 MUST 在 API 契约与前端提示中写明，避免使用者误配。

---

## R7. `topK` 与 `minScore` 的生效位置

**Decision**：`topK` 直接映射 `maxResults`；`minScore` 直接映射 `minScore`。二者**都在 SQL 内生效**（`LIMIT` 与 `WHERE`），Java 侧只做结果遍历，**没有后置过滤**。

**语义后果（写入契约）**：
1. `minScore` 是 `WHERE` 谓词，在 `ORDER BY` / `LIMIT` **之前**生效 → 返回条数**可能少于 `topK`**，这是正确行为，不是缺陷。
2. 不存在"先取 topK 再按阈值裁剪"导致的"高分块被低分块挤掉"问题。
3. **默认阈值取 `0.70`**，由 `unirag.retrieval.default-min-score` 配置（环境变量 `UNIRAG_DEFAULT_MIN_SCORE`），调用方显式传入时以其为准。校准依据（2026-10-18）：demo 租户使用 BAAI/bge-m3 的 46 次真实检索中，不相关查询的最高 `top_score` 为 `0.6958`（"测试"），相关查询的最低 `top_score` 为 `0.7075`（"拿铁是如何制作的"），`0.70` 落在分界间隙；该样本下零结果率为 10/46（21.7%），零结果均来自语料外或无意义查询。样本规模与模型单一，故保留配置覆盖；集成测试使用确定性嵌入模型（分数约 0.55），将默认值覆盖为 `0.0` 以沿用原断言。

---

## R8. 聊天模型调用

**Decision**：使用 `dev.langchain4j.model.chat.ChatModel` 的 `chat(ChatRequest)`：

```java
ChatResponse resp = chatModel.chat(ChatRequest.builder()
        .messages(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(prompt)))
        .temperature(0.2)
        .maxOutputTokens(maxTokens)
        .build());
String answer = resp.aiMessage().text();
```

**关键版本事实（与 spec 的用词需对齐）**：1.20.0 里 **`ChatLanguageModel` 与 `generate(...)` 已不存在**，接口为 `dev.langchain4j.model.chat.ChatModel`，`OpenAiChatModel implements ChatModel`。`Builder` 上的字段名是 **`maxOutputTokens`**（模型级 builder 上则是 `maxTokens`/`maxCompletionTokens`；项目既有 `LlmResourceProvider` 用的是模型级 `maxTokens`，保持不变）。

**复用既有资产**：租户 `LLM` 资源已由既有 `LlmResourceProvider`（`ResourceType.LLM`）构造为 `OpenAiChatModel`（含 `base-url`/`api-key`/`model-name`/`temperature`/`max-tokens`/`timeout`），检索侧经 `TenantResourceRouter.get(tenantCode, ResourceType.LLM, key, OpenAiChatModel.class)` 取用即可，**不新增 Provider**。

**Alternatives considered**：`chat(String)` 单消息重载 —— 无法带 system 提示与 temperature/maxOutputTokens，不满足"组装带引用来源的提示词"要求。

---

## R9. 混合检索（HYBRID）与重排：本期不做

**Decision**：本期只用 `SearchMode.VECTOR`。HYBRID（RRF 融合全文检索）需 `EmbeddingSearchRequest.query()` 非空且在数据库侧做中文分词配置，成本与本期目标不匹配；重排（`RERANK_MODEL`）仅保留扩展点（FR-011），不实现。

**Rationale**：与 spec 的"明确不在本期范围"一致。二者都以**增强型扩展**的形式预留 —— 接入时不需要改动任何阶段实现（正是 FR-012 要证明的性质）。

---

## R10. 指标（FR-007）

**Decision**：基于既有 Micrometer（`spring-boot-starter-actuator` 已引入），经 `ObservationMetrics` 记录：

| 指标 | 类型 | 标签 |
|---|---|---|
| `unirag.retrieval.requests` | Counter | `tenant`, `status` |
| `unirag.retrieval.duration` | Timer | `tenant` |
| `unirag.qa.requests` | Counter | `tenant`, `status`（含 `degraded`） |
| `unirag.qa.duration` | Timer | `tenant` |
| `unirag.retrieval.table.failures` | Counter | `tenant` |

**约束**：标签 MUST NOT 使用 `docName` / 问题文本等高基数字段（避免指标爆炸），也 MUST NOT 含密钥（FR-008）。

---

## R11. 前端最小接入（FR-013）

**Decision**：`web/src/features/chat/ChatPlaceholderPage.tsx` 替换为 `ChatPage.tsx`：

- 复用 `web/src/api/client.ts`（响应解包 `{data}` 与错误 `{error}`）、`errors.ts`、`content/zh-CN.ts`（文案），**不新增 HTTP 客户端抽象**。
- 新增 `web/src/api/retrieval.ts` 暴露 `ask()`（调 `POST /api/v1/ask`）。
- 三态：`loading` / `empty`（`degraded && degradeReason === "NO_CONTEXT"`）/ `error`；正常态展示 `answer` + `citations` 列表（文档标识、片段序号、原文）。
- 单测用 `@testing-library/react` + 既有 `web/src/test/render.tsx`，覆盖三态（对应 spec US2 的验收场景 2/3）。

**不在本期**：会话历史、引用来源跳转、样式与无障碍深化。

---

## 待办与已知限制

| 项 | 状态 |
|---|---|
| 向量表 metadata 无索引，过滤走顺序扫描 | **已知限制**，本期接受；触发条件与备选方案见 R5 |
| `json` 列上 `::int`/`::bigint` 转换对脏数据敏感 | **由写入侧不变量保证**（所有写入经 `PipelineExecutor`），由 `*IT` 断言覆盖 |
| OpenTelemetry 接入 | 不做；观察型扩展即预留挂载点（spec 澄清 Q3） |
| 重排 / 混合检索 | 不做；以增强型扩展预留（FR-011） |
| 对话记忆持久化 | 不做（spec 明确不在本期） |
| `specs/` 与 `.specify/` 当前被 gitignore 排除 | **需在实现前修复**，否则本 feature 的 spec/plan/tasks 无法进版本控制（违反宪法"开发工作流与质量门"） |
