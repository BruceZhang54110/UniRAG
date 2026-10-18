<!--
Sync Impact Report
==================
版本变更: 模板（未批准） → 1.0.0
变更类型: 首次批准（初始采用，确立 MAJOR 基线）
说明: 原文件与 .specify/templates/constitution-template.md 字节完全相同，即宪法从未被填写；
      本次为初始采用，而非对既有宪法的修订。

新增原则:
  I.   架构优先，拒绝冗余堆砌（来自用户指令）
  II.  规格驱动交付
  III. 真实链路集成验证
  IV.  多租户隔离不可妥协
  V.   敏感信息零泄漏

新增章节:
  - 技术栈与工程约束（原 [SECTION_2_NAME]）
  - 开发工作流与质量门（原 [SECTION_3_NAME]）

移除章节: 无（原文件仅含模板占位符与示例注释）

依赖产物一致性核查:
  ✅ .specify/templates/plan-template.md — Constitution Check 为通用占位
     ("[Gates determined based on constitution file]")，并已含 Complexity Tracking 表，
     天然对齐原则 I 的"复杂度必须说明理由"，无需修改
  ✅ .specify/templates/spec-template.md — 已含 Functional Requirements / Success Criteria /
     Assumptions 章节，满足原则 II，无需修改
  ✅ .specify/templates/tasks-template.md — 已含 Phase 划分与测试门，满足原则 II / III，无需修改
  ✅ .specify/templates/checklist-template.md — 无原则相关硬编码，无需修改
  ✅ .kimi-code/skills/speckit-*/SKILL.md（15 个命令文件） — 未发现过时的 agent 专有引用；
     唯一 "claude" 出现在指令内的示例路径列表中，属通用说明而非过时引用，无需修改
  ✅ README.md / AGENTS.md — 均未引用任何宪法原则，无需同步

延期项: 无（无 TODO 占位符遗留）
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

## 技术栈与工程约束

- **语言与框架**：Java 21 + Spring Boot 3.5.16（父 POM 锁定版本），构建走 Maven Wrapper（`./mvnw`），MUST NOT 依赖系统安装的 Maven。
- **数据库**：PostgreSQL，向量能力依赖 pgvector 扩展。Schema 变更 MUST 经 Flyway 迁移落地；**已执行过的迁移脚本 MUST NOT 修改**（校验和不可变），变更一律新增版本。
- **原文存储**：MUST 经 Apache OpenDAL 统一抽象读写，后端由配置切换，业务代码 MUST NOT 感知后端差异。
- **租户资源**：嵌入模型、向量存储、LLM MUST 经 `tenant_resource_config` + Router 解析；MUST NOT 在代码中硬编码地址、模型名或凭据。
- **依赖最小集**：新增依赖 MUST 在 plan 中说明必要性；能用既有依赖实现的 MUST NOT 新增。

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
- **合规审查**：每个 plan MUST 通过 Constitution Check；存在违反时 MUST 在 plan 的 Complexity Tracking 表中给出理由与被否决的更简单替代方案。implement 阶段 MUST 复核原则 I、III、V 的落实情况。
- 运行时开发指导以 `AGENTS.md` 与 `README.md` 为入口，二者 MUST NOT 与本宪法冲突。

**Version**: 1.0.0 | **Ratified**: 2026-10-14 | **Last Amended**: 2026-10-14
