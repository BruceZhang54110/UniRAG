---
description: "Task list for feature: langchain4j 基础设施集成"
---

# Tasks: langchain4j 基础设施集成

**Input**: Design documents from `/specs/002-langchain4j-rag/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ quickstart.md ✅
**Contracts**: 无（本期不对外暴露接口，见 plan.md）

**Tests**: 包含测试任务。spec FR-003/FR-004 要求模型组件的自动化测试在无真实凭据下可运行；且加入 `spring-boot-starter-jdbc` 会使既有上下文测试因 DataSource 自动装配而失败，必须先行修复（红-绿）。

**Organization**: 任务按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件，无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3，对应 spec.md）
- 描述中均含确切文件路径

## Path Conventions

单项目 Maven 标准布局（见 plan.md）：

- 源码：`src/main/java/com/unirag/`、`src/main/resources/`
- 测试：`src/test/java/com/unirag/`、`src/test/resources/`
- 构建：`pom.xml`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖引入

- [ ] T001 修改 `pom.xml`：新增 `<langchain4j.version>1.20.0</langchain4j.version>` 属性；新增依赖 `dev.langchain4j:langchain4j`、`dev.langchain4j:langchain4j-open-ai`、`dev.langchain4j:langchain4j-pgvector`（三者版本引用 `${langchain4j.version}`）、`spring-boot-starter-jdbc`、`org.postgresql:postgresql`（runtime scope，版本由 Spring Boot BOM 管理）；执行 `./mvnw -q dependency:resolve` 确认依赖解析零错误

**Checkpoint**: langchain4j 1.20.0 与 PG 依赖就位。注意：此时 `UniRagApplicationTests` 将因 DataSource 自动装配缺少 `spring.datasource.url` 而失败——这是预期红态，由 Phase 2 修复

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事启动前必须完成的测试环境地基

**⚠️ CRITICAL**: 此阶段完成前不得开始任何用户故事工作

- [ ] T002 新增 `src/test/resources/application.yml`：为测试上下文配置 `spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`，使 `@SpringBootTest` 在无 PostgreSQL 环境可加载上下文（对应 research.md R5：测试不依赖真实 PG；数据源连通性由 quickstart 手工验证）
- [ ] T003 执行 `./mvnw test` 确认既有测试（`UniRagApplicationTests`、`HealthEndpointTest`）恢复全绿，修复任何残留失败

**Checkpoint**: 测试地基就绪——既有测试在新依赖下全绿，用户故事实现可以开始

---

## Phase 3: User Story 1 - 框架依赖引入与外部化配置 (Priority: P1) 🎯 MVP

**Goal**: langchain4j 与 PG 依赖就位，模型服务与数据源配置全部外部化，仓库无硬编码密钥

**Independent Test**: `./mvnw clean verify` 零错误全绿；`application.yml` 中密钥均为 `${...}` 环境变量占位符（自动化断言）；配置完整的 PG 环境下应用启动成功，配置错误时启动失败并报可读原因

### Tests for User Story 1

- [ ] T004 [US1] 新增 `src/test/java/com/unirag/ConfigHygieneTest.java`：断言 `src/main/resources/application.yml` 中所有密钥类配置项（api-key、password）的取值均为 `${...}` 占位符形式、不含真实密钥形态串；先写测试确认其红态（此时 yml 尚无这些键，断言失败）

### Implementation for User Story 1

- [ ] T005 [US1] 修改 `src/main/resources/application.yml`：新增 `unirag.ai.models.default` 配置组（`provider: open-ai`、`base-url: ${OPENAI_BASE_URL}`、`api-key: ${OPENAI_API_KEY}`、`chat-model: ${OPENAI_CHAT_MODEL}`、`embedding-model: ${OPENAI_EMBEDDING_MODEL}`、`timeout: 60s`，缺省组项可省略），新增 `spring.datasource.*`（`url: ${PG_JDBC_URL}`、`username: ${PG_USERNAME}`、`password: ${PG_PASSWORD}`）；均为占位符、无真实值；使 T004 转绿
- [ ] T006 [US1] 启动行为验证（手工验收，记录于 quickstart.md 备注）：环境变量齐备且 PG 可连时 `./mvnw spring-boot:run` 启动成功；`PG_JDBC_URL` 指向不可达地址时启动失败且错误信息含数据源地址（HikariCP 启动期验证，对应 FR-005/SC-003）

**Checkpoint**: US1 独立可用——依赖与配置样例就位，密钥卫生自动化验证通过

---

## Phase 4: User Story 2 - 核心组件装配与冒烟验证 (Priority: P2)

**Goal**: 自定义装配 `unirag.ai` 配置的 `ChatModel` / `EmbeddingModel` Bean（`default` 组、`@Primary`），无真实凭据的自动化测试验证装配与调用链路

**Independent Test**: 无模型凭据的环境执行 `./mvnw clean verify` 全绿：测试证实 Bean 按配置构建（类型与属性正确）、可被注入；配置缺失时启动失败并指明缺失项

### Tests for User Story 2

> **NOTE: 先写测试，确认未实现时失败（上下文无 Bean / 断言不成立），再补实现直至通过**

- [ ] T007 [P] [US2] 新增 `src/test/java/com/unirag/config/AiModelConfigurationTest.java`：纯单元测试（不启 Spring 上下文）——构造填充好的 `AiProperties`，调用装配方法，断言返回的 `ChatModel` 为 `OpenAiChatModel` 且 base-url/model-name/timeout 与配置一致、嵌入模型同理（对应 FR-003/FR-004 的无凭据验证）
- [ ] T008 [US2] 新增 `src/test/java/com/unirag/config/AiBeanSmokeTest.java`：`@SpringBootTest` + 测试属性注入完整 `unirag.ai.models.default` 配置，断言 `ChatModel` 与 `EmbeddingModel` Bean 存在、可注入且为 `@Primary`；另断言缺失 `default` 组时启动失败且异常信息指明缺失项

### Implementation for User Story 2

- [ ] T009 [P] [US2] 新增 `src/main/java/com/unirag/config/ModelGroup.java`：配置组 POJO/record，字段按 data-model.md（provider、baseUrl、apiKey、chatModel、embeddingModel、timeout 默认 60s、maxTokens、logRequests/logResponses 默认 false）
- [ ] T010 [P] [US2] 新增 `src/main/java/com/unirag/config/AiProperties.java`：`@ConfigurationProperties("unirag.ai")`，含 `Map<String, ModelGroup> models`
- [ ] T011 [US2] 新增 `src/main/java/com/unirag/config/AiModelConfiguration.java`：`@Configuration` + `@EnableConfigurationProperties(AiProperties.class)`；从 `models.get("default")` 用 `OpenAiChatModel.builder()` / `OpenAiEmbeddingModel.builder()` 构建 `@Primary` Bean；装配前校验 default 组存在且 base-url/api-key 非空，缺失即抛出含缺失项名称的 `IllegalStateException`（快速失败，对应 FR-005/SC-003）
- [ ] T012 [US2] 执行 `./mvnw clean verify` 确认全部测试绿、构建零错误（对应 FR-008）

**Checkpoint**: US1 + US2 独立可用——基础设施的核心价值（可注入的模型组件）已交付并验证

---

## Phase 5: User Story 3 - 集成文档与配置上手 (Priority: P3)

**Goal**: 新开发者仅凭 README 即可完成配置并验证

**Independent Test**: 未参与者仅按 README 章节完成环境变量填写并执行文档命令，输出与文档预期一致

### Implementation for User Story 3

- [ ] T013 [US3] 修改 `README.md`：新增「模型服务配置」章节，内容对齐 `specs/002-langchain4j-rag/quickstart.md`——配置项说明表（`unirag.ai.*`、`spring.datasource.*`）、环境变量导出示例、验证命令（`./mvnw clean verify` → `./mvnw spring-boot:run`）与预期输出、常见错误（缺失配置启动失败样例）
- [ ] T014 [US3] 走查验证：新开 shell 逐条执行 README 中每条命令（含环境变量未设置时的启动失败场景），核对实际输出与文档预期一致，发现不符即修正文档（对应 SC-001）

**Checkpoint**: 所有用户故事独立功能就绪

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 影响多个故事的收尾事项

- [ ] T015 [P] spec 合规总检：对照 `specs/002-langchain4j-rag/spec.md` 逐条核对 FR-001~FR-008 与 SC-001~SC-005，重跑 `./mvnw clean verify` 全绿、`git status` 干净；不一致处修正后复核
- [ ] T016 [P] 代码评审准备：核对 `config/` 包代码符合 langchain4j 1.20.0 官方核心 API 惯例（builder 用法，无 starter 残留）；全仓库 grep 确认无真实密钥串（`sk-`、密码字面量），核对日志不输出密钥（对应 FR-006/SC-004/SC-005）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖——可立即开始
- **Foundational (Phase 2)**: 依赖 T001（jdbc starter 引入导致测试红态）；T002 完成后 T003 验证
- **User Stories (Phase 3~5)**: 均依赖 Foundational 完成；US1/US2/US3 逻辑上顺承（配置样例 → 装配实现 → 文档定稿），不建议并行
- **Polish (Phase 6)**: 依赖 US1~US3 全部完成

### User Story Dependencies

- **User Story 1 (P1)**: Foundational 完成后即可开始（MVP：依赖 + 配置外部化）
- **User Story 2 (P2)**: 依赖 T005 的配置样例形态（装配代码与配置键一一对应），不依赖 US1 的验证记录
- **User Story 3 (P3)**: 依赖 T012（实测结果定稿）与 T006（启动行为记录）

### Within Each User Story

- 测试先于实现并先失败（T004 → T005；T007/T008 → T009~T011）
- 每个故事完成后再进入下一优先级

### Parallel Opportunities

- Phase 4: T009 与 T010 并行（不同文件）；T007 可与 T009/T010 并行（测试先于实现阶段内可同时写）
- Phase 6: T015 与 T016 并行
- 其余任务因文件依赖（pom.xml、application.yml、AiModelConfiguration.java）需顺序执行

---

## Parallel Example: User Story 2

```bash
# 测试与配置类骨架并行:
Task: "单元测试 AiModelConfigurationTest（不同文件，可与实现类并行起草）"
Task: "ModelGroup.java 与 AiProperties.java 并行编写"

# 之后再串行:
Task: "AiModelConfiguration.java（依赖 T009/T010 的类已存在）"
```

---

## Implementation Strategy

### MVP First (User Story 1 + 2)

1. 完成 Phase 1: Setup（T001）
2. 完成 Phase 2: Foundational（T002、T003）——关键，修复测试红态
3. 完成 Phase 3: User Story 1（T004~T006）
4. 完成 Phase 4: User Story 2（T007~T012）——**基础设施的核心交付：可注入的模型组件**
5. **STOP and VALIDATE**: `./mvnw clean verify` 无凭据全绿，启动行为符合预期
6. 此时已交付 spec 的核心价值：langchain4j 地基就绪，下一 feature 可直接写 RAG 业务

### Incremental Delivery

1. Setup + Foundational → 新依赖下既有测试恢复绿
2. US1 → 依赖与配置样例就位（外部化 + 密钥卫生）
3. US2 → 组件装配与冒烟验证（核心价值）
4. US3 → README 配置章节 + 走查
5. Polish → spec 合规总检与评审准备

---

## Notes

- [P] 任务 = 不同文件、无依赖
- [Story] 标签用于追溯任务到 spec.md 的用户故事
- T006 为手工验收任务（启动行为），结果记录于 quickstart.md 备注，供 T013/T014 文档定稿引用
- T002 的测试数据源排除策略仅用于测试环境；主环境数据源行为以 quickstart.md 为准
- 每个任务或逻辑任务组完成后提交
- 避免：模糊任务、同文件冲突、破坏故事独立性的跨故事依赖
