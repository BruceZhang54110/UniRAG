# Implementation Plan: langchain4j 基础设施集成

**Branch**: `002-langchain4j-rag` | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-langchain4j-rag/spec.md`

## Summary

在 feature 001 的 Spring Boot 3.5.16 / Java 21 骨架上集成 langchain4j 1.20.0 作为 RAG 主体框架的基础设施层：以**自定义 `@Configuration` + 自定义 `unirag.ai` 配置前缀**（多命名模型组，`default` 组装配 `@Primary` Bean）接入 OpenAI 兼容接口的对话模型与嵌入模型——不使用 starter 自动装配，为将来多模型扩展预留结构；同时引入 PGVector 依赖与 PostgreSQL 数据源连接配置（不建表、不装配存储组件）。验收以编译通过、符合 langchain4j 官方核心 API 惯例、代码 review 通过为准；测试全部使用模拟替身，无需真实凭据。

## Technical Context

**Language/Version**: Java 21（沿用骨架）
**Primary Dependencies**: langchain4j 1.20.0（`langchain4j` core + `langchain4j-open-ai` + `langchain4j-pgvector`，版本属性统一管理）；Spring Boot 3.5.16；PostgreSQL JDBC 驱动（BOM 管理）+ `spring-boot-starter-jdbc`
**Storage**: PostgreSQL——本期仅数据源连接配置，不建表、不装配 EmbeddingStore Bean
**Testing**: JUnit 5 + Spring Boot Test + Mockito（`@MockBean` 覆盖 ChatModel / EmbeddingModel / DataSource，无真实凭据全绿）
**Target Platform**: 开发者本地 / 内网单机（macOS、Linux JVM）
**Project Type**: web-service（骨架为 Web 服务；本期不新增任何 HTTP 端点）
**Performance Goals**: 本期无运行时性能指标（基础设施层）
**Constraints**: 不硬编码任何密钥/私有地址；无真实凭据时测试全绿；关键配置缺失启动快速失败并输出可读错误
**Scale/Scope**: 单机；配置支持多命名模型组（本期仅 `default` 组装配 Bean）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

⚠️ `.specify/memory/constitution.md` 仍为模板占位、未正式 ratified——无强制性 gate。沿用 feature 001 已确立的项目标准作为本 feature 的实际 gate：

| Gate | 标准 | 状态 |
|------|------|------|
| 构建零失败 | `./mvnw clean verify` 全绿 | ✅ 通过（设计满足，实施时验证） |
| 测试全绿且无需真实凭据 | 新增测试全部使用模拟替身 | ✅ 通过（R5 测试策略） |
| 无硬编码密钥 | 配置样例仅占位符，密钥走环境变量 | ✅ 通过（R6） |
| 无冲突决议残留 | spec 中"starter 约定"表述已替换为 plan 阶段自定义装配决议 | ✅ 通过（spec 已更新） |

**Phase 1 设计后复检**: 上述 gate 在设计产物（research.md / data-model.md / quickstart.md）中均得到落实，无违宪事项。

## Project Structure

### Documentation (this feature)

```text
specs/002-langchain4j-rag/
├── plan.md              # 本文件
├── research.md          # Phase 0 技术决策（R1~R6）
├── data-model.md        # 配置实体模型（本期无业务实体/表）
├── quickstart.md        # 配置上手文档
└── tasks.md             # Phase 2 输出（/speckit.tasks 生成，非本命令产物）
```

**Contracts**: 本期不对外暴露任何 HTTP/业务接口（spec 明确范围），故无 contracts/ 产物——接口契约留待 RAG 业务 feature。

### Source Code (repository root)

```text
pom.xml                                    # 新增 langchain4j.version=1.20.0 与三个依赖、jdbc + postgresql 驱动

src/main/java/com/unirag/
├── UniRagApplication.java                 # 既有（不变）
└── config/
    ├── AiProperties.java                  # @ConfigurationProperties("unirag.ai")：models Map<组名, ModelGroup>
    ├── ModelGroup.java                    # 单组配置：provider/base-url/api-key/chat-model/embedding-model/timeout 等
    └── AiModelConfiguration.java          # @Configuration：default 组构建 @Primary ChatModel / EmbeddingModel

src/main/resources/
└── application.yml                        # 结构样例（unirag.ai.* 与 spring.datasource.* 占位符，无真实密钥）

src/test/java/com/unirag/
├── UniRagApplicationTests.java            # 既有上下文冒烟（适配测试数据源策略）
└── config/
    └── AiModelConfigurationTest.java      # @MockBean 冒烟：Bean 可注入、调用链路走通
```

**Structure Decision**: 延续 feature 001 单项目标准布局；新增 `config` 包承载配置绑定与装配，测试镜像主代码结构。不引入分层架构（无业务代码），保持最小结构。

## Complexity Tracking

无违宪/越界复杂度，本表为空。多模型配置 Map 结构为用户的明确扩展性要求，属需求本身而非过度设计。
