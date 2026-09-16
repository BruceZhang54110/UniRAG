# Feature Specification: 引入 langchain4j 基础设施集成

**Feature Branch**: `002-langchain4j-rag`
**Created**: 2026-09-10
**Status**: Draft
**Input**: User description: "引入langchain4j 作为本项目 RAG 主体框架"

## Clarifications

### Session 2026-09-10

- Q: 本期实施范围是什么？ → A: 仅 langchain4j 基础设施集成（依赖引入、外部化配置、核心组件装配、集成验证），不做具体 RAG 业务（文档摄取、向量检索、RAG 问答、业务 HTTP 接口），为后续 feature 打下基础
- Q: PGVector 向量存储是否纳入本期基础设施？ → A: B — 部分纳入：引入 PGVector 相关依赖并完成 PostgreSQL 数据源连接配置（含连接池），本期不建表、不装配向量存储组件，表结构与存储组件留待下一 feature。另确认：项目向量存储与普通业务数据存储统一使用 PostgreSQL
- Q: 本期「集成生效」如何验收？ → A: 以「编译通过 + 用法符合 langchain4j 官方示例教程惯例 + 代码 review 通过」为准；不要求真实凭据的手动冒烟、不新增验证端点
- Q: （plan 阶段修订）组件装配方式？ → A: 不使用 langchain4j Spring Boot starter 自动装配，改用自定义 Configuration + 自定义配置前缀，且配置模型支持多个命名模型配置，为将来支持多种模型做准备。此决议取代本会话前一条中"采用官方 starter 约定"的隐含决策

## User Scenarios & Testing *(mandatory)*

> **本期范围说明**：本 feature 只交付 RAG 框架的地基。文档摄取、检索增强问答及其业务接口属后续 feature，见 Clarifications 会话记录。

### User Story 1 - 框架依赖引入与外部化配置 (Priority: P1)

开发者将 langchain4j 及其 Spring Boot 集成引入现有项目，并以 OpenAI 兼容接口方式接入大语言模型与嵌入模型：密钥、服务地址、模型名、超时等全部通过外部配置注入，代码与仓库中不硬编码任何密钥或私有地址。引入后项目构建与既有测试保持零失败。

**Why this priority**: 依赖与配置是一切后续能力（含 RAG 业务）的地基，且是风险最高的环节——依赖冲突、配置形态一旦定错，返工成本最大，故最先落地。

**Independent Test**: 在干净环境执行 `./mvnw clean verify` 零错误、全部测试通过；配置样例文件中列出了全部配置项及说明，仓库中 grep 不到任何硬编码密钥。

**Acceptance Scenarios**:

1. **Given** 项目已按文档引入 langchain4j 依赖，**When** 开发者执行完整构建，**Then** 构建零错误、全部既有测试保持通过
2. **Given** 开发者拿到一份 OpenAI 兼容服务的密钥与地址，**When** 按配置样例填入外部配置，**Then** 无需改动任何代码即可完成接入准备
3. **Given** 仓库源码与配置样例，**When** 检查是否存在硬编码密钥，**Then** 找不到任何真实密钥（样例中仅有占位符）
4. **Given** PostgreSQL 连接配置正确，**When** 启动应用，**Then** 数据源初始化成功、应用正常启动；**Given** 连接配置错误（地址不可达或凭据无效），**When** 启动应用，**Then** 启动失败并输出可读原因

---

### User Story 2 - 核心组件装配与冒烟验证 (Priority: P2)

langchain4j 的对话模型组件与嵌入模型组件完成自动装配，可被业务代码注入使用；自动化测试用模拟替身验证"组件可注入、可发起调用、返回可被消费"，全程无需真实模型服务凭据。

**Why this priority**: 组件装配是"集成"二字的实质内容——依赖引入了但 Bean 装不起来等于没集成。冒烟验证同时确立"无真实凭据也可测试"的项目测试策略。

**Independent Test**: 在无模型服务凭据的环境执行 `./mvnw clean verify` 全绿：测试证实对话模型组件与嵌入模型组件均可注入、调用路径走通且结果可被断言（模拟替身替代真实服务）。本 story 的验收标准为：编译通过、用法符合 langchain4j 官方核心 API 惯例（自定义装配）、代码 review 通过。

**Acceptance Scenarios**:

1. **Given** 应用启动且模型服务配置完整，**When** 业务代码注入对话模型组件并发起一次对话调用，**Then** 调用成功并返回非空回复
2. **Given** 应用启动且配置完整，**When** 注入嵌入模型组件并对一段文本求向量，**Then** 返回维度稳定的向量结果
3. **Given** 无真实模型凭据的测试环境，**When** 执行自动化测试，**Then** 全部测试通过（通过模拟替身替代真实服务）

---

### User Story 3 - 集成文档与配置上手 (Priority: P3)

新开发者仅凭仓库文档即可完成环境准备：了解需要哪些配置项、如何获取与填写、如何运行验证命令确认集成生效。

**Why this priority**: 文档让前两个故事的价值可被复现。没有它，集成质量只能依赖原开发者口口相传。优先级最低是因为文档内容依赖 US1/US2 的最终配置形态定稿。

**Independent Test**: 一名未参与搭建的开发者仅依据 README 完成配置填写并执行文档给出的验证命令，无需额外求助。

**Acceptance Scenarios**:

1. **Given** 新开发者 clone 仓库，**When** 阅读 README 配置章节，**Then** 能明确知道需要填写哪些配置项、从何处获取
2. **Given** 配置已填写，**When** 执行文档中的验证命令，**Then** 看到与文档描述一致的预期输出

---

### Edge Cases

- 关键配置（如模型服务密钥/地址）缺失时，应用启动应快速失败并输出指明缺失项的可读错误
- 密钥无效或服务不可达时，调用失败应抛出/返回清晰错误，而非裸堆栈或无限等待
- 超时配置缺失时应采用合理默认值，且默认值有文档说明
- 日志中不得打印完整 API 密钥（脱敏要求）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在现有构建中引入 langchain4j 框架及其 Spring Boot 集成依赖，引入后项目构建与既有测试保持零失败
- **FR-002**: 大语言模型与嵌入模型服务的接入 MUST 通过 OpenAI 兼容接口实现：服务地址、密钥、模型名称、超时时间均通过外部配置注入，代码与配置样例中不硬编码任何真实密钥或私有地址
- **FR-003**: 对话模型组件 MUST 完成装配并可供注入调用；其自动化测试 MUST 在无需真实模型凭据的情况下可运行（通过模拟替身验证注入与调用链路）
- **FR-004**: 嵌入模型组件 MUST 完成装配并可供注入调用；其自动化测试同样 MUST 无需真实凭据即可运行
- **FR-005**: 系统 MUST 引入 PGVector 相关依赖并完成 PostgreSQL 数据源的连接配置（地址、凭据、连接池参数均外部化）；向量数据与业务数据统一存储于 PostgreSQL。本期 MUST NOT 建表、MUST NOT 装配向量存储组件，但数据源配置错误时 MUST 在启动阶段失败并给出可读原因
- **FR-006**: 系统 MUST 对可预期的配置失败快速失败并给出可读错误：关键配置缺失时在启动阶段即报错并指明缺失项
- **FR-007**: 系统 MUST 保证密钥类配置不出现在日志输出中（脱敏）
- **FR-008**: 全部既有自动化测试（含本 feature 新增测试）MUST 通过

**明确不在本期范围**（后续 feature 交付）：

- 文档摄取、切分、向量化入库（原 FR-004 业务部分）
- 向量检索与检索增强问答链路（原 FR-005）
- 摄取/问答等业务 HTTP 接口（原 FR-006）
- 向量存储数据表初始化与向量存储组件装配（本期仅到"依赖 + 连接配置"为止）

### Key Entities *(include if feature involves data)*

- **模型服务配置（Model Config）**: 连接大模型与嵌入模型（OpenAI 兼容接口）所需的密钥、地址、模型名、超时、日志开关等配置项；按对话与嵌入分别成组
- **数据源配置（DataSource Config）**: 连接 PostgreSQL 所需的地址、凭据、连接池参数等配置项；向量数据与业务数据共用同一 PostgreSQL 实例（是否同库同实例由规划阶段定，本期至少保证连接配置就位）
- 业务实体（文档、片段、向量、问答会话、知识库）属后续 RAG 业务 feature，本期仅保证基础设施不阻碍其建模

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 新开发者仅凭仓库文档，在 10 分钟内完成模型服务配置填写并执行文档验证命令确认集成生效
- **SC-002**: 全部自动化测试在无真实外部服务凭据的环境下可运行且全绿；构建零失败
- **SC-003**: 关键配置缺失时，应用在 1 次启动尝试内即失败，并输出指明缺失项的可读错误信息
- **SC-004**: 日志与配置样例中检查不到任何完整密钥串
- **SC-005**: 本 feature 新增代码通过与 langchain4j 官方核心 API 一致的用法评审（自定义装配、code review 通过），无偏离官方惯例的用法

## Assumptions

- 本期范围仅限基础设施：langchain4j 依赖、外部化配置、对话/嵌入模型组件装配与验证手段；RAG 业务（摄取、检索、问答、业务接口）为下一 feature
- 模型服务与嵌入模型服务均由使用方自备，通过 OpenAI 兼容接口接入
- 向量数据与业务数据统一存储于 PostgreSQL；本期仅完成 PGVector 相关依赖引入与数据源连接配置，建表与存储组件装配属下一 feature
- 沿用 feature 001 建立的项目骨架（Spring Boot 3 + Java 21 + Maven Wrapper），本 feature 在其上叠加
- 组件装配采用自定义 Configuration Bean（不使用 langchain4j starter 自动装配），配置项使用自定义前缀并支持多个命名模型配置，为将来接入多种模型预留扩展
- 用户明确指定 langchain4j 为框架选型，此点已确定、不重复澄清
