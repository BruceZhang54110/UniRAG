---
description: "Task list for feature: 初始化 Spring Boot 3 项目骨架"
---

# Tasks: 初始化 Spring Boot 3 项目骨架

**Input**: Design documents from `/specs/001-init-springboot-project/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅ quickstart.md ✅

**Tests**: 包含测试任务。spec 的 FR-006（健康端点可程序化验证）与 FR-008（自动化测试全绿）要求骨架自带测试。

**Organization**: 任务按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件，无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3，对应 spec.md）
- 描述中均含确切文件路径

## Path Conventions

单项目 Maven 标准布局（见 plan.md）：

- 源码：`src/main/java/com/unirag/`、`src/main/resources/`
- 测试：`src/test/java/com/unirag/`
- 构建：`pom.xml`、`mvnw`、`.mvn/wrapper/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 项目初始化与基础结构

- [X] T001 Create Maven project skeleton per plan.md: `pom.xml`（parent `spring-boot-starter-parent:3.5.16`，`java.version=21`，groupId/artifactId `com.unirag`/`unirag`，依赖 `spring-boot-starter-web`、`spring-boot-starter-actuator`、`spring-boot-starter-test` test scope）及标准目录 `src/main/java/com/unirag/`、`src/main/resources/`、`src/test/java/com/unirag/`
- [X] T002 [P] Add `.gitignore` at repository root using the official Spring Boot Java template（覆盖 `target/`、IDE 配置 `.idea/` `*.iml` `.vscode/`、日志 `*.log`、OS 隐藏文件 `.DS_Store` 等）
- [X] T003 [P] Add Maven Wrapper at repository root: `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`（Wrapper 3.3.x，distributionUrl 指向 Maven 3.9.x）；验证 `./mvnw -v` 可执行且无需预装 Maven

**Checkpoint**: `./mvnw` 命令可用，仓库忽略规则就位

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事启动前必须完成的核心骨架

**⚠️ CRITICAL**: 此阶段完成前不得开始任何用户故事工作

- [X] T004 Create application entry point `src/main/java/com/unirag/UniRagApplication.java` with `@SpringBootApplication` and standard `main` method calling `SpringApplication.run`
- [X] T005 [P] Create `src/main/resources/application.yml` exposing only the health endpoint: `management.endpoints.web.exposure.include: health`（端口保持默认 8080）
- [X] T006 Create context-load smoke test `src/test/java/com/unirag/UniRagApplicationTests.java` with `@SpringBootTest` asserting the application context loads；验证 `./mvnw test` 通过

**Checkpoint**: 基础就绪——应用可编译、上下文可加载、构建命令可用，用户故事实现可以开始

---

## Phase 3: User Story 1 - 可运行的项目骨架 (Priority: P1) 🎯 MVP

**Goal**: 开发者在本地完成构建并启动服务，健康端点可验证（spec US1 / FR-001~FR-003、FR-006）

**Independent Test**: 在干净环境执行 `./mvnw clean verify` 零错误，随后 `./mvnw spring-boot:run` 启动，`curl localhost:8080/actuator/health` 返回 `{"status":"UP"}`，启动到可响应 ≤ 30 秒

### Tests for User Story 1

- [X] T007 [US1] Contract test `src/test/java/com/unirag/HealthEndpointTest.java` per `contracts/health-endpoint.md`: `@SpringBootTest` + `TestRestTemplate` asserting `GET /actuator/health` returns HTTP 200 and body `status == "UP"`（先写测试，确认在未实现时失败，再补实现直至通过）

### Implementation for User Story 1

- [X] T008 [US1] Run full verification `./mvnw clean verify` and confirm zero errors, all tests green（对应 SC-003：≤5 分钟、零失败）
- [X] T009 [US1] Run startup verification: `./mvnw spring-boot:run`, measure time from launch to health endpoint responding, confirm ≤ 30 seconds and `{"status":"UP"}`（对应 SC-002、US1 验收场景 2）

**Checkpoint**: User Story 1 独立可用——构建、启动、健康验证全链路通过

---

## Phase 4: User Story 2 - 规范的 Git 版本管理 (Priority: P2)

**Goal**: 仓库含完整骨架基线提交，克隆后工作区干净（spec US2 / FR-004、FR-005）

**Independent Test**: 克隆仓库到空目录，`git status` 完全干净（无未跟踪文件），历史中可见包含完整骨架的基线提交，无 `target/` 等产物被跟踪

### Implementation for User Story 2

- [X] T010 [US2] Create baseline git commit staging all scaffold files (pom.xml, mvnw, .mvn/, .gitignore, src/main/**, src/test/**) with message describing the Spring Boot 3.5.16 skeleton; ensure build artifacts (`target/`) and IDE files are untracked（对应 FR-004）
- [X] T011 [US2] Clean-clone verification: clone the repository to a fresh temporary directory, run `git status` confirming a fully clean worktree, and verify no build artifacts or IDE configs are tracked（对应 FR-005、SC-004）

**Checkpoint**: User Stories 1 AND 2 均可独立工作——骨架可运行且版本管理规范

---

## Phase 5: User Story 3 - 清晰的上手文档 (Priority: P3)

**Goal**: 新开发者仅凭仓库文档完成环境准备、构建与启动（spec US3 / FR-007）

**Independent Test**: 一名未参与搭建的开发者仅依据 README 完成环境检查 → `./mvnw clean verify` → 启动 → 健康验证，无需额外求助

### Implementation for User Story 3

- [X] T012 [US3] Write `README.md` at repository root per FR-007 and `quickstart.md`: 环境要求（JDK 21、Git，明确说明无需预装 Maven）、不超过 3 条 Wrapper 命令（`./mvnw clean verify` → `./mvnw spring-boot:run` → `curl localhost:8080/actuator/health`）、期望输出示例、常用命令速查表
- [X] T013 [US3] Validate quickstart walkthrough: execute every command in README.md from a fresh shell in dependency-cached conditions, confirm each step's actual output matches documented expectations, fix any mismatch in the document（对应 SC-005、SC-006）

**Checkpoint**: 所有用户故事独立功能就绪

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 影响多个故事的收尾事项

- [X] T014 [P] Measure and record quickstart timings: total time from clone to health verification (SC-001 ≤10 min), full build time (SC-003 ≤5 min), startup time (SC-002 ≤30 s); note results in a comment or doc update if any target is at risk
- [X] T015 Final spec compliance sweep: re-run `./mvnw clean verify` (all green per FR-008), confirm clean `git status`, and walk through FR-001~FR-007 and SC-001~SC-006 in `specs/001-init-springboot-project/spec.md` marking each satisfied

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖——可立即开始（T002、T003 可并行）
- **Foundational (Phase 2)**: 依赖 T001（pom 与目录）；T004~T006 相互可部分并行
- **User Stories (Phase 3~5)**: 均依赖 Foundational 完成；US2/US3 之间以及它们与 US1 可并行（不同产物：运行链路 vs 提交与文档）
- **Polish (Phase 6)**: 依赖 US1~US3 全部完成

### User Story Dependencies

- **User Story 1 (P1)**: Foundational 完成后即可开始，无其他故事依赖（MVP）
- **User Story 2 (P2)**: Foundational 完成后即可开始；逻辑上在 US1 骨架文件齐备后提交最有意义，但验证逻辑独立
- **User Story 3 (P3)**: 依赖 T008/T009 的命令实测结果（文档需记录真实输出），其余独立

### Within Each User Story

- 测试先于实现并先失败（T007 先于 T008 验收）
- 每个故事完成后再进入下一优先级或并行推进

### Parallel Opportunities

- Phase 1: T002 与 T003 并行（不同文件）
- Phase 2: T005 与 T004 并行（配置文件 vs Java 源码）
- Phase 3~5: US2（T010、T011）与 US3（T012、T013）可与 US1 收尾并行
- Phase 6: T014 与 T015 并行

---

## Parallel Example: User Story 1

```bash
# Phase 1 并行:
Task: "Add .gitignore at repository root ..."
Task: "Add Maven Wrapper at repository root ..."

# Phase 2 并行:
Task: "Create application entry point src/main/java/com/unirag/UniRagApplication.java"
Task: "Create src/main/resources/application.yml ..."
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup
2. 完成 Phase 2: Foundational（关键——阻塞所有故事）
3. 完成 Phase 3: User Story 1（含 T007~T009 验收）
4. **STOP and VALIDATE**: 独立测试 US1——构建、启动、健康端点全链路
5. 此时已交付 spec 的核心价值：可运行骨架

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. US1 → 独立验证（MVP：可运行骨架）
3. US2 → 基线提交 + 干净克隆验证
4. US3 → README + 文档走查验证
5. Polish → 指标测量与 spec 合规总检

### Parallel Team Strategy

多人协作时：

1. 全员完成 Setup + Foundational
2. Foundational 完成后：
   - 开发者 A: US1（运行链路验收）
   - 开发者 B: US2（Git 基线与克隆验证）
   - 开发者 C: US3（README 起草，待 A 的实测输出后定稿）
3. 各故事独立完成并汇合

---

## Notes

- [P] 任务 = 不同文件、无依赖
- [Story] 标签用于追溯任务到 spec.md 的用户故事
- 每个用户故事应可独立完成与验证
- T007 测试须在验收前确认失败过（红-绿）
- 每个任务或逻辑任务组完成后提交
- 避免：模糊任务、同文件冲突、破坏故事独立性的跨故事依赖
