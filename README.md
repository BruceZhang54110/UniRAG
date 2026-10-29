# UniRAG

**多租户、可观测、可演进、轻量化的企业级 RAG 服务底座**

UniRAG 是一个基于 **Java 21 + Spring Boot 3 + PostgreSQL/pgvector + LangChain4j** 的 RAG 服务项目。它不是单次演示脚本，而是面向真实业务的知识库服务骨架：从文档上传、摄取、检索、问答到观测与评测，都按可维护、可扩展、可验证的方式组织。中间件优先使用 PostgreSQL 承载，追求架构与依赖的轻量化。

项目当前已提供传统向量 RAG 的完整生产路径，并内置查询分类与统一 RAG 路由，为后续接入 Graph RAG、Agentic RAG 预留了清晰扩展点。

---

## 为什么选 UniRAG

- **多租户隔离内建**：租户上下文、资源解析、向量表、检索、问答与观测均按租户隔离。
- **中间件轻量化**：各类中间件优先使用 PostgreSQL 承载（向量、原文存储、观测表等），追求架构与中间件依赖的轻量级。
- **资源可插拔**：嵌入模型、向量存储、LLM、重排模型、查询改写模型都按租户资源配置。
- **链路可观测**：统一观测入口可查每次检索/问答阶段，也可查目标范式、实际范式与降级原因。
- **架构可演进**：`rag` 层负责范式编排，未来 Graph/Agentic 只需提供真实执行器，不需要重建问答入口。
- **质量可回归**：核心链路基于真实 PostgreSQL/pgvector 集成测试，检索质量支持冻结评测集回放。

> Graph RAG 与 Agentic RAG 当前仅保留路由契约与扩展点，未用空实现冒充已落地能力。

---

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | `java -version` 核验 |
| PostgreSQL | 16+ | 默认连接 `localhost:5432/uni_rag_db` |
| pgvector | 已安装 | Flyway 会自动启用扩展 |
| Maven | 无需安装 | 仓库内置 Maven Wrapper |

### 三条命令启动

```bash
# 1. 编译、测试、打包
./mvnw clean verify

# 2. 启动服务
./mvnw spring-boot:run

# 3. 验证健康状态
curl http://localhost:8080/actuator/health
```

期望输出：

```json
{"status":"UP"}
```

### 常用命令

| 目的 | 命令 |
|---|---|
| 仅编译 | `./mvnw compile` |
| 仅运行测试 | `./mvnw test` |
| 打包可执行 Jar | `./mvnw package` |
| 运行 Jar | `java -jar target/unirag-0.0.1-SNAPSHOT.jar` |
| 清理构建产物 | `./mvnw clean` |

---

## 核心能力

- **文档知识库**：文本 / Markdown 上传、断点续传、摄取管线、版本与状态流转。
- **多租户向量检索**：按租户配置独立向量表，支持多粒度、多表检索。
- **企业级问答链路**：查询改写、向量检索、跨表归并、重排、提示词组装、模型回答。
- **查询分类与 RAG 路由**：识别简单事实、多跳关系、复杂推理问题，并按能力可用性选择执行路径。
- **统一观测**：按 traceId 查询阶段耗时、状态、命中、错误原因与路由详情。
- **离线评测**：冻结数据集与向量夹具支持无外部凭据回放，适合质量回归。

---

## 文档导航

- 功能规格与实现计划：[`specs/`](specs/)
- 查询分类与统一路由：[`specs/014-rag-query-routing/`](specs/014-rag-query-routing/)
- 检索评测说明：[`eval/datasets/README.md`](eval/datasets/README.md)
- 项目治理原则：[`constitution`](.specify/memory/constitution.md)

---

## 评测回放

评测是按需触发的离线任务，不属于默认 `./mvnw test` 质量门。

```bash
# 无外部凭据，使用冻结向量夹具回放
./mvnw test -Peval -Dtest=RagEvaluationIT \
  -Deval.dataset=baseline -Deval.mode=replay
```

报告输出到 `eval/reports/`，已确认基线保存在 `eval/baselines/`。

---

## Roadmap

- **Graph RAG**：基于租户隔离的实体—关系图与 `kg_query` 执行器。
- **Agentic RAG**：`vector_search` / `kg_query` / `web_search` 工具化执行循环。
- **模型辅助分类**：在现有确定性规则分类器之上引入 shadow / canary 模式。
- **企业集成**：更细粒度的资源治理、权限策略、监控面板与部署模板。

---

## 技术栈

| 层次 | 技术 |
|---|---|
| Runtime | Java 21, Spring Boot 3.5 |
| 数据库 | PostgreSQL, pgvector, Flyway, MyBatis Plus |
| RAG / AI | LangChain4j, OpenAI-compatible models, BGE rerank |
| 原文存储 | Apache OpenDAL：PostgreSQL / FS / S3 |
| 观测 | Spring Actuator, Micrometer, 统一观测表 |
| 测试 | JUnit 5, AssertJ, Mockito, MockMvc, 真实 PostgreSQL IT |
