# Phase 0 Research: RAG 检索评测基线

**Feature**: `013-rag-evaluation` | **Date**: 2026-10-19

本文件记录评测基线的关键技术决策、理由与被否方案。所有 `Technical Context` 中的未知项已在此定案。

---

## R1. 评测代码放在哪里

**Decision**：全部评测代码放 `src/test/java/com/unirag/eval/`，使用 `@Tag("eval")`；JUnit 类只做薄启动器，评测逻辑放在普通类中。不新增 Maven 模块，不改生产源文件。

**Rationale**：
- 评测是开发/QA 活动，不是生产运行时能力；放 test scope 可避免评测代码与数据集进入生产 jar；
- 复用现有 Spring 上下文、真实 PostgreSQL、`IngestionService`、`RetrievalService`、测试装配模式，落地成本最低；
- 运行器与启动器分离后，未来若需要独立 CLI，可将 `com.unirag.eval` 平移到独立模块，逻辑无需重写；
- 符合宪法 I：不为单一用例新增模块，不复制既有能力。

**Alternatives considered**：
- 放 `src/main/java` + CLI：会把评测逻辑与文件格式装入生产包，且需要新的启动入口；
- 新建 `eval` Maven 模块：增加构建复杂度与依赖管理成本，当前规模不划算；
- 外部脚本调用 HTTP：无法直接取得逐表候选，难以做失败归因，且状态清理困难。

---

## R2. 向量化的三种模式

**Decision**：提供 `live`、`record`、`replay` 三种模式：
- `live`：调用真实嵌入模型，生成权威基线；模型来源优先复用数据库里已有租户的
  `EMBEDDING_MODEL`（`-Deval.embeddingTenant`），也支持 `EVAL_EMBEDDING_*` 环境变量；
- `record`：在 `live` 基础上把语料与查询的向量写入冻结夹具；
- `replay`：只读冻结夹具，不访问任何外部模型，结果可重复。

复用数据库模型时不复制、不打印配置内容，只解析 Registrar 已注册的 Bean；实际输出维度通过一次
真实嵌入探测校验。

夹具按 `SHA-256(UTF-8 精确文本)` → `float[]` 索引，并记录模型标识与维度。

启用重排时，record 另写 `rerank.json`（key = `SHA-256(query + NUL + segment text)`）；
replay 同时读取嵌入与重排两个夹具，不调用外部嵌入或重排服务。

**Rationale**：
- spec 同时要求“可复现、无凭据”和“真实模型权威基线”，单一模式无法同时满足；
- 精确文本哈希避免归一化导致“看起来相同、实际不同”的错配；
- 维度写入夹具，可在摄取前发现与向量表维度不一致；
- 缺失向量立即失败，不静默回退到其他模式。

**Alternatives considered**：
- 只用确定性哈希向量：无法衡量语义效果，失去评测意义；
- 本地 ONNX 嵌入模型：需要新增依赖与模型文件，超出“依赖最小集”；
- 把向量存数据库：需要新增表/迁移，且与“文件工件可版本控制”的目标不符；
- 不做 replay：CI 与日常回归将依赖外部凭据，违反宪法 III。

---

## R3. 评测租户与资源装配

**Decision**：
- 每个数据集使用独立评测租户 `eval_<dataset>` 与独立向量表 `embeddings_eval_<dataset>`；
- 运行前清理评测租户的 `document_chunk`、`ingestion_run`、`document`、向量表行；
- 插入 `tenant_resource_config`（`EMBEDDING_STORE`、live/record 时的 `EMBEDDING_MODEL`）与 `ingestion_pipeline`，调用 `TenantResourceRegistrar.loadAndRegister()`；
- 评测基类直接继承 `AbstractPostgresIntegrationTest`，不继承带 `@MockBean TenantResourceRouter` 的 `AbstractIngestionIT`；
- 评测基类使用真实的 `EvalTenantResourceRouter`（继承 `TenantResourceRouter`）：存储、改写仍走父类真实索引与 Bean 解析；嵌入模型与重排模型在评测运行器明确覆盖时返回源租户模型或 replay/recording 包装，全程不使用 Mockito；
- `rerank=true` 时复用源租户的 primary `RERANK_MODEL`；`rewrite=true` 暂不支持，显式拒绝。

**Rationale**：
- 与 `AbstractIngestionIT` 的既有模式一致，已验证可用；
- 能在不修改生产代码的前提下注入 replay/recording 模型；
- 评测租户独立，避免与业务租户或既有测试租户冲突；
- 清理幂等，重复运行不累积脏数据。

**Alternatives considered**：
- 全部使用真实 Router：无法注入 replay 模型；
- 每次运行新建随机租户：会在 Spring 容器中累积 Bean，且难以复用；
- 独立数据库：隔离更彻底，但需要额外运维与迁移，当前不必要。

**已知限制**：Registrar 对已注册 Bean 幂等跳过，因此同一 JVM 内修改模型连接配置不会重建 Bean；评测配置应保持稳定，模型地址变更时重启测试 JVM 或更换租户 key。

---

## R4. 检索调用与诊断

**Decision**：
- 生产遍：直接调用 `RetrievalService.retrieve(RetrievalRequest)`，请求参数来自数据集配置；在 `TenantContext` 已初始化且存在请求作用域的环境中执行；
- 诊断遍：直接调用 `QueryEmbeddingStage.execute(...)` 与 `VectorSearchStage.execute(...)`，使用 `minScore=0`、`topK=50`（`RetrievalConstraints.MAX_TOP_K`）、关闭重排，取得逐表命中；
- 不为评测复制任何检索、归并或过滤逻辑。

**Rationale**：
- 生产遍保证结果代表真实链路；
- 诊断遍复用同一阶段的实现，只是放宽阈值与条数，不引入第二套检索逻辑；
- `VectorSearchResult` 已包含逐表 `TableHits`，足以判断“是否被某张表召回”；
- `TargetResolver` 与 `QueryEmbeddingStage` 负责目标表解析与按模型向量化，诊断无需重复。

**Alternatives considered**：
- 只使用生产遍：无法区分阈值过滤、排序截断与归并丢失；
- 自行实现 JDBC 向量查询：会复制过滤、维度与租户逻辑，违反宪法 I；
- 读取 `retrieval_observation`：观测只记录最终结果，缺少逐表候选。

---

## R5. 两遍检索：排序与阈值分离

**Decision**：每条可回答查询执行两遍：
- **诊断遍**：`minScore=0`、`recallTopK=topK=50`、`rerank=false`，保留配置中的改写开关；
- **生产遍**：使用数据集配置的 `topK`、`recallTopK`、`minScore`、改写与重排开关。

Ranking 类指标取生产遍的最终结果；阈值相关归因对比诊断遍与生产遍；耗时只统计生产遍。

**Rationale**：
- `VectorSearchStage` 在 SQL 内应用 `minScore`，归并前过滤与归并后过滤在不同表之间不等价；用两遍才能准确区分“没有召回”和“召回后被阈值过滤”；
- 诊断遍使用 `topK=50` 可以观察排名靠后的候选，判断是否只是排名低于最终 `topK`；
- 评测规模小（30–100 条），额外一次查询可接受；replay 模式下没有模型网络开销。

**Alternatives considered**：
- 单遍 `minScore=0` + 结果后过滤：在多表归并场景下会错误地把“被低分候选挤出 topK”算作阈值问题；
- 只跑生产遍：无法生成失败归因；
- 每条查询跑多组阈值：成本高且无法覆盖任意配置。

**性能备注**：若 live 模式耗时超过目标，可对查询向量做请求级缓存（同一文本只嵌入一次）；该优化不改变契约，列入实现阶段的可选优化。

---

## R6. 相关性匹配与指标定义

**Decision**：
- 文本归一化：NFKC、去首尾空白、内部连续空白折叠为单空格、拉丁字母转小写；
- 标注匹配：`docName` 相等且归一化后的片段文本包含归一化后的 `span`；
- **文档级 Recall@k**：top-k 中出现的唯一 gold 文档数 / gold 文档总数；
- **片段级 Recall@k**：被 top-k 中任一相关片段覆盖的 gold 标注数 / gold 标注总数（一个标注只计一次）；
- **HitRate@k**：任一 gold 标注被覆盖记为 1，否则 0；
- **MRR@k**：第一个相关片段的倒数排名，无相关结果为 0；
- **nDCG@k**：二元增益，理想 DCG 按 `min(gold 标注数, k)` 计算；
- **不可回答误答率**：不可回答查询在生产遍返回至少一个片段的比例；
- **耗时**：生产遍 `RetrievalService.retrieve` 的 p50/p95。

**Rationale**：
- 以原文片段而非片段序号标注，切片参数变化不会使数据集失效；
- 文档级与片段级分开，可区分“文档对了但片段不对”；
- 二元增益避免引入未经校准的等级评分；
- 不可回答查询单独统计，防止系统倾向“总有答案”。

**Alternatives considered**：
- 按 `chunkIndex` 标注：切片参数一变即全部失效；
- 按 token 重叠比例判定：阈值主观，容易误判；
- LLM-as-judge 判定相关性：本期明确不做，且成本与可重复性不匹配。

---

## R7. 失败归因

**Decision**：对每个未命中的可回答查询，按以下顺序判定，首个成立即作为主归因，并保留全部观察证据：

| 归因 | 判定依据 |
|---|---|
| `CHUNKING_GAP` | 评测租户的 `document_chunk` 中没有任何片段（任一管线）包含归一化 gold span |
| `NOT_RECALLED` | 片段存在，但诊断遍的逐表命中中不存在包含 gold span 的片段 |
| `MERGE_OR_TOPK_DROPPED` | 逐表命中中存在，但诊断遍归并后的 top-50 中不存在 |
| `RERANK_DROPPED` | 诊断遍存在，生产遍启用重排后最终结果不存在 |
| `THRESHOLD_FILTERED` | 诊断遍存在且分数低于生产配置 `minScore`，生产遍因此缺失 |
| `BELOW_TOPK` | 诊断遍存在且分数不低于阈值，但排名高于生产 `topK` |
| `LABEL_INVALID` | 运行期无法在文档中找到标注片段；正常应在校验阶段拦截 |

**Rationale**：
- 每个判定只依赖可观察数据（chunk 表、逐表命中、合并结果、最终结果），不做猜测；
- 能直接回答“该优化切片、嵌入、归并还是阈值”；
- 复用生产阶段，归因逻辑与真实行为一致。

**Alternatives considered**：
- 只给“未命中”而不分类：无法指导下一步；
- 用启发式规则猜测：容易把切片问题误判为需要混合检索；
- 人工逐条分类：成本高，且无法在报告中自动汇总。

**边界**：同一 gold span 可能出现在多个管线或文档中，归因以“存在至少一个可命中片段”为准；报告保留候选片段列表，便于人工复核。

---

## R8. 数据集、夹具与报告格式

**Decision**：
- 数据集：`eval/datasets/<name>/`，语料 `corpus/*.md|*.txt`，查询 `queries.jsonl`，配置 `config.json`，阈值 `thresholds.json`；
- 冻结向量：`eval/fixtures/<name>/embeddings.json`；
- 已确认基线：`eval/baselines/<name>.json`（版本控制）；
- 运行报告：`eval/reports/<run-id>.json` + `.md`（本地/CI 产物，目录保留 `.gitkeep`）；
- 全部使用 Jackson（既有依赖），不引入 YAML/CSV 解析库。

**Rationale**：
- JSONL 对逐条查询的增删改友好，Git diff 清晰；
- JSON 报告便于程序比较，Markdown 报告便于人工阅读；
- 基线与运行报告分离，避免每次运行都产生版本控制噪音；
- 不新增解析依赖，符合“依赖最小集”。

**Alternatives considered**：
- CSV：嵌套标注表达困难，转义易错；
- YAML：需要额外解析配置或引入依赖；
- 数据库存储报告：需要新表与迁移，且不利于代码评审时查看差异。

---

## R9. 默认质量门隔离

**Decision**：
- 默认 surefire 配置增加 `<excludedGroups>eval</excludedGroups>`；
- 新增 Maven profile `eval`，设置 `<groups>eval</groups>` 并覆盖排除项；
- 评测启动器命名为 `*EvalIT` 并标注 `@Tag("eval")`；
- 运行命令：`./mvnw test -Peval -Dtest=RagEvaluationIT ...`。

**Rationale**：
- 默认 `./mvnw test` 不执行评测、不调用外部模型、不要求凭据；
- `-Peval` 显式触发，符合 spec FR-017；
- 复用现有 surefire 与 `*IT` 约定，不新增 Failsafe 或模块。

**Alternatives considered**：
- 按类名排除：容易误伤或遗漏；
- 新增 Failsafe：引入第二套测试生命周期，当前不必要；
- 环境变量开关：配置分散，不如 Maven profile 明确。

**验证**：实现后运行默认 `./mvnw test`，确认既有测试通过率 100%、耗时变化 ≤ 20%；再运行 `-Peval` 确认评测执行。

---

## R10. 凭据与敏感信息

**Decision**：
- live/record 模式只从环境变量读取：`EVAL_EMBEDDING_BASE_URL`、`EVAL_EMBEDDING_API_KEY`、`EVAL_EMBEDDING_MODEL`、`EVAL_EMBEDDING_DIMENSION`；
- 环境变量只用于构造内存中的租户资源配置，不写入数据集、配置、报告或版本控制；
- 报告只记录模型标识、维度与资源 key；
- 日志与错误原因经既有 `Redaction` 或等价逻辑收敛；
- 测试断言报告/日志中不出现凭据值。

**Rationale**：符合宪法 V；避免把评测工件变成凭据泄漏渠道。

**Alternatives considered**：
- 写入 `tenant_resource_config`：生产运行需要，但评测不应把凭据落库到开发库；
- 写入 `.env`/配置文件：容易被提交；
- 复用主应用配置：评测与生产耦合，且难以区分来源。

---

## R11. 报告比较与阈值

**Decision**：
- 可比字段：数据集版本、语料哈希、查询集哈希、运行模式、模型标识、向量维度、管线切片参数、`topK`、`recallTopK`、`minScore`、改写/重排开关；
- 任一字段不同 → 拒绝给出效果结论，列出差异字段，仅展示配置差异；
- 可比时输出每个指标及分标签维度的绝对值、绝对差值、相对变化；
- 阈值支持 `min`（越大越好）与 `max`（越小越好）；首版基线未配置阈值时不做通过/失败判定。

**Rationale**：spec FR-012/FR-013；避免把配置变化误读为效果变化。

**Alternatives considered**：
- 无条件比较：会得出误导性结论；
- 只用总体指标：掩盖分维度退化；
- 自动设定阈值：首版没有依据，应由维护者在看过基线后配置。

---

## R12. 规模、性能与执行顺序

**Decision**：首版顺序执行查询；不并行。live 模式每条查询约 1–2 次嵌入调用（诊断遍与生产遍共用同一 `retrievalQuery` 时可缓存），语料摄取按文档串行；replay 模式只做本地向量查找。目标：live 10 篇/30 条 ≤ 10 分钟，replay ≤ 3 分钟。

**Rationale**：
- 顺序执行保证报告顺序与耗时统计稳定；
- 当前规模小，并行带来的复杂度与资源竞争不值得；
- 需要优化时可在 `EvaluationRetriever` 内部增加查询向量缓存，不改变契约。

**Alternatives considered**：
- 一开始并行：延迟指标不稳定，失败定位困难；
- 批量嵌入查询：langchain4j 的 `embedAll` 可用于摄取，但查询逐条更贴近真实链路。

---

## R13. 仓库卫生：spec 文件被全局 gitignore 排除

**Decision**：实现前在仓库根 `.gitignore` 增加 `!specs/`（或等效规则），确保 `specs/013-rag-evaluation/` 可被 Git 跟踪；以 `git check-ignore -v specs/013-rag-evaluation/spec.md` 验证。

**Rationale**：当前用户全局 `~/.gitignore_global` 包含 `specs`，导致新增 spec 工件不出现在 `git status` 中；宪法要求 `specs/` 下的 spec/plan/tasks/contracts 进入版本控制并不得被 gitignore 排除。已跟踪的历史文件不受影响，但本 feature 的新文件会丢失。

**Alternatives considered**：
- 修改全局 gitignore：影响用户其他仓库，越界；
- `git add -f`：每次新增文件都要强制添加，容易遗漏；
- 改目录名：破坏既有编号约定。

---

## 结论

所有技术未知项已解决，可以进入 Phase 1 设计与实现。关键约束是：**评测代码不进入生产、评测数据可版本控制、replay 无凭据可复现、live 显式按需、失败归因只依赖可观察数据**。
