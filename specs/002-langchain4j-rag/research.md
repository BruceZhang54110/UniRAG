# Research: langchain4j 基础设施集成

**Feature**: 002-langchain4j-rag | **Date**: 2026-09-10
**Input**: spec.md Clarifications 会话决议 + 用户对 6 个技术决策点的直接答复

## R1: langchain4j 版本管理

- **Decision**: 统一使用 langchain4j **1.20.0**（用户指定）；`pom.xml` 中定义 `<langchain4j.version>1.20.0</langchain4j.version>` 属性，所有 langchain4j artifact 引用该属性
- **Rationale**: 当前依赖数量少（core / open-ai / pgvector 三个），单一属性管理最简；后续依赖增多或引入传递依赖冲突时再评估导入 `langchain4j-bom`
- **Alternatives considered**: 导入 `dev.langchain4j:langchain4j-bom` —— 对三个 artifact 属过度设计，YAGNI

## R2: 组件装配方式（不使用 starter）

- **Decision**: **不引入任何 langchain4j Spring Boot starter**。引入核心库 `dev.langchain4j:langchain4j`（ChatModel / EmbeddingModel 抽象）与 `dev.langchain4j:langchain4j-open-ai`（OpenAI 兼容实现），以自定义 `@Configuration` 手写装配 Bean
- **Rationale**: 用户在 plan 阶段明确决策——为将来支持多种模型做准备。自定义装配使配置类可按"命名模型配置"动态构建多个模型实例；若用 starter 自动装配，其配置结构绑定单一模型组，未来扩展需绕开或推翻自动装配
- **Alternatives considered**: `langchain4j-open-ai-spring-boot-starter` 自动装配 —— 配置省心但违背用户的多模型扩展决策，已否决（取代 clarify 阶段"采用官方 starter 约定"的隐含决策，见 spec.md Clarifications）

## R3: 配置模型（自定义前缀、多命名模型组）

- **Decision**: 自定义配置前缀 `unirag.ai`，结构为 **Map<String, ModelGroup>** 支持多个命名模型配置组，其中键 `default` 为默认组；每组字段：`provider`（本期固定 `open-ai`）、`base-url`、`api-key`、`chat-model`、`embedding-model`、`timeout`、`max-tokens`、`log-requests/log-responses`。装配时从 `default` 组构建 `@Primary` 的 `ChatModel` 与 `EmbeddingModel` Bean
- **Rationale**: 未来接入多家模型（DashScope 专用协议、Ollama 等）时，仅需新增配置组与 builder 分支，Bean 命名/注入点不变
- **Alternatives considered**: starter 约定前缀 `langchain4j.open-ai.*` —— 已被用户否决；单模型平铺配置 —— 不满足多模型扩展要求

## R4: PGVector 依赖与 PostgreSQL 数据源

- **Decision**: 本期仅引入 `dev.langchain4j:langchain4j-pgvector`（不加版本，随 `langchain4j.version`）+ `spring-boot-starter-jdbc` + `org.postgresql:postgresql`（版本由 Spring Boot BOM 管理，用户确认）；`spring.datasource.*` 全部外部化配置
- **可行性确认**:
  - 不创建 `PgVectorEmbeddingStore` Bean 不影响应用启动——该组件为纯客户端库，仅在业务代码调用时建立连接；本期不建表、不装配存储组件的边界成立
  - `spring.datasource` 配置错误（地址不可达/凭据无效）时，HikariCP 默认 `initializationFailTimeout=1`，连接池在启动阶段即失败 → 满足 FR-005"启动快速失败并给出可读原因"
- **Alternatives considered**: Testcontainers 起真实 PG 验证建表读写 —— 超出本期"只到依赖+连接配置"的范围（用户已明确），留待 RAG 业务 feature

## R5: 无真实凭据的测试策略

- **Decision**: `@SpringBootTest` 上下文冒烟 + `@MockBean` 覆盖 `ChatModel` / `EmbeddingModel`，验证：Bean 可被注入、调用路径走通、返回可被消费；全程无真实凭据
- **注意事项**: `@SpringBootTest` 会触发 DataSource 自动装配。测试策略：为测试上下文提供测试专用数据源属性（或排除 DataSource 自动装配），保证无 PG 环境也能加载上下文——具体手段在 tasks 阶段细化（候选：测试 `application.yml` 指向测试库、`@MockBean DataSource`、`excludeAutoConfiguration`）
- **Alternatives considered**: 纯 Mockito 单元测试不启 Spring 上下文 —— 无法验证 `@ConfigurationProperties` 绑定与 `@Configuration` 装配本身，故保留上下文冒烟

## R6: 密钥脱敏（FR-007 / SC-004）

- **Decision**: `api-key` 一律经环境变量或外部配置注入；仓库配置样例仅含占位符；langchain4j 请求/响应日志默认关闭（`log-requests/log-responses` 默认 `false`），开启也不打印密钥；Spring Boot 默认不打印配置属性值
- **Rationale**: 满足 spec 密钥脱敏要求，零额外实现成本
