<!--
Sync Impact Report
==================
版本变更: 1.1.0 → 1.2.0
变更类型: MINOR（新增原则 VII"体量相称，复用优先"）
历史: 1.0.0 首次批准（2026-10-14，初始采用）→ 1.1.0（2026-10-23，新增原则 VI
     与"目标架构与包结构"章节）→ 1.2.0（2026-10-29，新增原则 VII）

新增原则:
  VII. 体量相称，复用优先

修改原则: 无（原则 I–VI 原文保留）

新增章节: 无

移除章节: 无

依赖产物一致性核查:
  ✅ .specify/templates/plan-template.md — Constitution Check 为通用占位并已含
     Complexity Tracking 表，与本原则"超体量实现须论证"的落点一致，无需修改
  ✅ .specify/templates/spec-template.md / tasks-template.md / checklist-template.md —
     无原则相关硬编码，无需修改
  ✅ .kimi-code/skills/speckit-*/SKILL.md — 未硬编码任何宪法条款编号，无需修改
  ✅ README.md / AGENTS.md — 均未引用具体宪法原则，无需同步
  ✅ Governance"合规审查"条款 — 已在宪法文件内同步将原则 VII 纳入 implement
     阶段复核清单（I、III、V、VI → I、III、V、VI、VII）

延期项: 无 TODO 占位符
-->

# UniRAG Constitution

## Core Principles

### I. 架构优先，拒绝冗余堆砌

**严禁为了把代码写得简单而暴力堆砌冗余代码。** 每一处变更 MUST 以 Java 高级开发 + 架构师标准审视。

- **复用优先于新增**：动手前 MUST 先确认这是否是既有能力的延伸；同一能力 MUST NOT 存在两份实现。
- **抽象由真实用例驱动**：只有一个用例时保持直白（YAGNI）；一旦确认存在第二个同类用例，MUST 抽出共享实现而非复制。
- **职责与依赖方向清晰**：分层边界明确、依赖单向、每个组件可独立测试。
- **禁止的实现方式**（示例而非穷举）：为绕过设计问题在多处重复同一段逻辑；用冗长的 if/else 分支堆砌取代多态或策略；把同一配置硬编码在多个位置；靠复制粘贴产出"看起来能跑"的新类。
- **复杂度必须有理由**：新增抽象、新增依赖、跨层调用 MUST 能说明"更简单的替代方案为何不够"，并记入 plan 的 Complexity Tracking。

理由：冗余代码的代价不在写的时候，而在每次修改都要同步改 N 处；这是本项目已知会拖垮演进速度的头号风险。

### II. 规格驱动交付

- 每个 feature MUST 先有 `spec.md`，经 clarify → plan → tasks 后方可进入实现；MUST NOT 跳过规格直接写代码。
- spec 中每条 FR 与 SC MUST 可被验证（具备可测的通过/失败判据），禁止"健壮""友好""专业"等无法判定的表述。
- 未决事项 MUST 显式标注归属阶段（plan 阶段定 / 后续 feature），MUST NOT 留含混表述。
- 实现产物 MUST 可追溯到 `tasks.md` 的具体条目。

理由：本项目 001–010 均以 spec / plan / tasks 三件套交付；规格是防止"实现漂移"的唯一锚点。

### III. 真实链路集成验证

- 行为 MUST 可通过 `./mvnw test` 验证（含 `*IT` 集成测试），并使用**真实 PostgreSQL + pgvector**。
- 外部模型依赖（嵌入、聊天、重排）MUST 以确定性替身注入；测试 MUST NOT 依赖任何外部服务凭据。
- MUST NOT 通过 mock 掉数据库或持久层来让集成测试"通过"。
- 断言 MUST 覆盖可观察的持久化结果（表内容、向量维度、状态流转），而非仅覆盖方法调用。

理由：多租户与向量检索的正确性只在真实数据库上成立；替身化的数据库会让测试与生产行为脱节。

### IV. 多租户隔离不可妥协

- 任何读写 MUST 按租户隔离；租户身份 MUST 来自请求上下文，MUST NOT 从请求体或可伪造参数推断。
- 跨租户访问 MUST 返回明确的 404 / 403 语义，MUST NOT 用空结果冒充"不存在"。
- 跨租户泄漏用例的通过率 MUST 为 100%。

理由：租户间数据泄漏是本类产品不可逆的事故；隔离必须由测试强制，而非依赖实现者自觉。

### V. 敏感信息零泄漏

- api-key、密码、令牌 MUST NOT 出现在日志、观测记录、错误信息、测试断言、示例文档或版本控制中。
- 排障与错误信息 MUST 只包含字段名与定位信息（租户、资源类型、资源 key），MUST NOT 包含配置值本身。
- 观测与错误路径 MUST 有测试守门（例如配置卫生检查）。

理由：本项目资源凭据按租户落库，最易从"顺手把配置打进日志"这条路径泄漏。

### VI. 三范式融合与查询路由

**统一入口，按问题形态选范式。** 任何问答请求 MUST 从唯一的 RAG 入口进入，由查询分类器在
传统 RAG（向量检索）、Graph RAG（多跳关系）、Agentic RAG（工具化推理）之间路由；
调用方 MUST NOT 直接绑定某一范式实现。

- **分类器**：MUST 输出可判定的意图（简单事实型 / 多跳关系型 / 复杂推理型）、置信度与判定依据，
  并 MUST 记录到检索观测中。分类 MUST 有明确预算（超时 + 降级），MUST NOT 因分类失败阻断问答。
  低置信度 MUST 回退到 Agentic RAG（能力最全），MUST NOT 静默选择某一范式。
- **统一契约**：三种范式 MUST 共享同一套请求 / 结果 / 引用契约（如 `RagRequest`、`RagResult`、
  引用与轨迹模型），MUST NOT 各自定义一套返回结构。
- **共享约束不可绕开**：租户隔离（原则 IV）、观测记录、评测口径对三条路径同等生效；
  任何范式 MUST NOT 自带权限旁路或日志旁路。
- **Graph RAG**：MUST 维护按租户隔离的实体—关系图，支持从命中 chunk 出发的多跳扩展；
  图存储 MUST 优先复用 PostgreSQL，引入独立图数据库 MUST 在 plan 的 Complexity Tracking 中论证。
- **Agentic RAG**：工具 MUST 以统一 `Tool` 契约暴露，至少覆盖 `vector_search`、`kg_query`、
  `web_search`；执行 MUST 有硬预算（最大步数、总超时、单工具调用上限）且每一步可审计；
  工具失败 MUST 触发降级，MUST NOT 中断整轮问答。
- **`web_search` 默认关闭**：仅当租户显式启用且问题确实需要外部信息时才可调用；
  MUST NOT 将租户私有内容作为查询发往外部检索。
- **渐进落地**：三范式 MUST 逐个以独立 feature 交付，MUST NOT 一次性引入图数据库、Agent 框架
  或替换现有向量存储；某范式未就绪时，MUST 由已就绪范式提供等价降级路径。

理由：企业级 RAG 的答案质量取决于检索策略与问题形态的匹配——单用向量检索答不了多跳关系，
单用 Agent 又会让简单事实题付出数倍延迟与成本。路由让系统在正确的问题上花正确的代价。

### VII. 体量相称，复用优先

**实现体量必须与功能体量相称；能复用既有架构与模式的，MUST NOT 为单一功能另起炉灶。**

- **体量红线**：简单功能 MUST NOT 以远超其复杂度的代码量实现（"几十行可完成的任务不引入框架、
  不铺多层抽象"）；新增代码量与功能体量明显不匹配时，MUST 先重新审视设计而非继续堆码。
- **复用优先**：动手前 MUST 盘点既有能力——共享阶段/扩展点、观测体系、资源路由、统一异常与响应、
  既有持久化设施；新功能 MUST 以既有架构的自然延伸方式落地。
- **超体量须论证**：确需超出直觉体量的实现（如为隔离性、可观测性、多租户预留的结构性代码），
  MUST 在 plan 的 Complexity Tracking 中说明"更简单的替代方案为何不够"（与原则 I 同一落点）。
- **评审标尺**：评审 MUST 把"新增代码行数 / 功能点数"是否异常作为检查项；体量异常且无论证的
  变更 MUST NOT 通过。

理由：代码量不匹配功能的背后几乎总是没有复用或过度设计；本项目按 spec/plan/tasks 小步交付，
每一 feature 的实现体量都应可被一眼审完。

## 技术栈与工程约束

- **语言与框架**：Java 21 + Spring Boot 3.5.16（父 POM 锁定版本），构建走 Maven Wrapper（`./mvnw`），MUST NOT 依赖系统安装的 Maven。
- **数据库**：PostgreSQL，向量能力依赖 pgvector 扩展。Schema 变更 MUST 经 Flyway 迁移落地；**已执行过的迁移脚本 MUST NOT 修改**（校验和不可变），变更一律新增版本。
- **原文存储**：MUST 经 Apache OpenDAL 统一抽象读写，后端由配置切换，业务代码 MUST NOT 感知后端差异。
- **租户资源**：嵌入模型、向量存储、LLM MUST 经 `tenant_resource_config` + Router 解析；MUST NOT 在代码中硬编码地址、模型名或凭据。
- **依赖最小集**：新增依赖 MUST 在 plan 中说明必要性；能用既有依赖实现的 MUST NOT 新增。

## 目标架构与包结构

**分层与依赖方向**：`common`（跨模块共享，无业务状态）← `infrastructure`（技术实现）
← 业务能力包（`tenant` / `ingestion` / `upload` / `retrieval` / `graph` / `agent`）
← `rag`（范式编排门面）。依赖 MUST 单向；`common` 与 `infrastructure` MUST NOT 反向依赖业务能力包。

```text
com.unirag
├── UniRagApplication.java
├── common/                       跨模块共享，不依赖业务包
│   ├── tenant/                   TenantContext、TenantContextInterceptor、WebConfig
│   ├── web/                      ApiResponse、ApiException、GlobalExceptionHandler
│   └── scheduling/               SchedulingConfig
├── infrastructure/               技术实现，不依赖业务包
│   ├── persistence/              现 infrastructure/mp：entity / mapper / service
│   ├── storage/                  现 com.unirag.storage：OpenDAL 抽象
│   └── embedding/                现 com.unirag.embedding：EmbeddingStore 装配
├── tenant/                       租户资源注册、路由、健康
├── ingestion/                    摄取管线与异步 worker
├── upload/                       上传 / 文档管理 HTTP 与编排
├── retrieval/                    范式一：传统 RAG（向量检索 + 共享 Stage 内核）
│   ├── pipeline/ stage/ model/ query/ rerank/ observation/ web/
├── graph/                        范式二：Graph RAG（多跳关系）              ← 新增
│   ├── model/                    实体、关系、子图、路径
│   ├── extraction/               实体 / 关系抽取
│   ├── store/                    图存储（PostgreSQL 落地）
│   └── retrieval/                kg_query、多跳扩展
├── agent/                        范式三：Agentic RAG（工具化推理）          ← 新增
│   ├── tool/                     Tool 契约 + vector_search / kg_query / web_search
│   ├── plan/                     规划与预算（步数、超时、调用上限）
│   └── loop/                     执行循环与审计
└── rag/                          范式编排（唯一问答入口）                    ← 新增
    ├── classify/                 QueryIntent、QueryClassifier
    ├── route/                    RagRouter、降级链
    └── model/                    跨范式契约：请求 / 结果 / 引用 / 轨迹
```

**迁移对照**（MUST 是纯移动：仅改 package 与 import，不改行为）：

| 现路径 | 目标路径 | 时机 |
|--------|----------|------|
| `upload/support/TenantContext*`、`WebConfig` | `common/tenant` | 随 `rag` 门面首个 feature |
| `upload/support/ApiResponse`、`ApiException`、`GlobalExceptionHandler` | `common/web` | 随 `rag` 门面首个 feature |
| `upload/support/SchedulingConfig` | `common/scheduling` | 随 `rag` 门面首个 feature |
| `infrastructure/mp` | `infrastructure/persistence` | 随首个需要图存储的 feature |
| `com.unirag.storage` | `infrastructure/storage` | 随首个需要图存储的 feature |
| `com.unirag.embedding` | `infrastructure/embedding` | 随首个需要图存储的 feature |
| `retrieval/*` | 原地不动 | 作为传统 RAG 与共享检索内核 |

**包结构规则**：

- 新增类的归属 MUST 遵循上图：分类与路由进 `rag.*`，图相关进 `graph.*`，Agent 工具与循环进
  `agent.*`；MUST NOT 把新范式塞进 `retrieval`，也 MUST NOT 在 `com.unirag` 根下新增散落类。
- 一个能力一个包：同一能力的契约、实体与实现 MUST 同包；跨包调用 MUST 走公开接口，
  MUST NOT 直接使用他包内部类。
- `agent.tool` MUST 只通过 `retrieval` / `graph` 的公开只读接口取数，MUST NOT 直连 Mapper
  或向量表，以保证观测、租户隔离与降级逻辑不被绕过。
- 包移动 MUST 与触发它的 feature 同批交付并跑通全量测试；MUST NOT 单开"整理包结构"的 feature。
- 迁移完成前，新增代码 MUST NOT 继续向 `upload/support` 增加跨模块类。
- HTTP 适配器留在各自能力包（如 `retrieval/web`、`upload/*Controller`），
  MUST NOT 为"分层好看"集中到全局 controller 包。

## 开发工作流与质量门

- **流程顺序**：specify → clarify → plan → tasks → implement。跳过任一步 MUST 在交付说明中显式声明风险。
- **质量门**：任何变更在声明完成前 MUST 通过 `./mvnw test`（含集成测试）；测试红灯时 MUST NOT 声明完成。
- **规格产物纳入版本控制**：`specs/` 下的 spec / plan / tasks / contracts MUST 进入 Git，MUST NOT 被 gitignore 排除。
- **一致性维护**：修改被 `AGENTS.md` 或 `README.md` 记录的行为时，MUST 同步更新对应文档。
- **评审重点**：新增代码是否重复造轮子、是否引入无理由的抽象或依赖、是否有对应的验证用例。

## Governance

- 本宪法优先于其他开发实践与个人偏好；出现冲突时以本宪法条款为准。
- 修订 MUST 通过 `/speckit-constitution` 流程执行，并产出 Sync Impact Report（记录于本文件顶部）。
- **版本策略**（语义化）：
  - **MAJOR**：移除或重定义原则，导致既有做法不再合规。
  - **MINOR**：新增原则或章节，或对既有指导做实质性扩展。
  - **PATCH**：措辞澄清、错别字修正等非语义调整。
- **合规审查**：每个 plan MUST 通过 Constitution Check；存在违反时 MUST 在 plan 的 Complexity Tracking 表中给出理由与被否决的更简单替代方案。plan 的 Project Structure 段 MUST 与本宪法的"目标架构与包结构"一致。implement 阶段 MUST 复核原则 I、III、V、VI、VII 的落实情况。
- 运行时开发指导以 `AGENTS.md` 与 `README.md` 为入口，二者 MUST NOT 与本宪法冲突。

**Version**: 1.2.0 | **Ratified**: 2026-10-14 | **Last Amended**: 2026-10-29
