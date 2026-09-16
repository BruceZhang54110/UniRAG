# Data Model: langchain4j 基础设施集成

**Feature**: 002-langchain4j-rag | **Date**: 2026-09-10

本期为基础设施集成，**无业务数据表、无业务实体**；仅定义配置实体。文档/片段/向量/知识库等业务实体属后续 RAG feature。

## 配置实体

### AiProperties（绑定前缀 `unirag.ai`）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `models` | `Map<String, ModelGroup>` | 是 | 命名模型配置组；键 `default` 为默认组，装配 `@Primary` Bean |

### ModelGroup（单个命名模型配置组）

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `provider` | String | 是 | `open-ai` | 模型供应方；本期仅支持 `open-ai`（OpenAI 兼容协议），保留字段为多模型扩展预留 |
| `base-url` | String | 是（default 组） | — | OpenAI 兼容服务地址，如 `https://api.deepseek.com/v1` |
| `api-key` | String | 是（default 组） | — | 服务密钥；仅经环境变量/外部配置注入，禁入仓库 |
| `chat-model` | String | 否 | — | 对话模型名；缺省时该组不装配 ChatModel |
| `embedding-model` | String | 否 | — | 嵌入模型名；缺省时该组不装配 EmbeddingModel |
| `timeout` | Duration | 否 | `60s` | 单次调用超时 |
| `max-tokens` | Integer | 否 | — | 对话最大输出 token |
| `log-requests` | Boolean | 否 | `false` | 是否记录请求日志（不含密钥） |
| `log-responses` | Boolean | 否 | `false` | 是否记录响应日志 |

### DataSource 配置（Spring 标准前缀 `spring.datasource`，非本项目自定义）

| 字段 | 必填 | 说明 |
|------|------|------|
| `url` | 是 | PostgreSQL JDBC 地址，如 `jdbc:postgresql://localhost:5432/unirag` |
| `username` | 是 | 连接用户名 |
| `password` | 是 | 连接密码（外部化注入） |
| `driver-class-name` | 否 | 缺省由 Boot 推断为 `org.postgresql.Driver` |
| `hikari.*` | 否 | 连接池参数（池大小、连接超时等），本期取默认值 |

## 校验规则

- `unirag.ai.models.default` 缺失或 `base-url` / `api-key` 为空 → **启动快速失败**，错误信息指明缺失项（对应 FR-005、SC-003）
- `spring.datasource.url` / `username` / `password` 缺失或连接不可达 → HikariCP 启动期失败，错误含数据源地址（对应 FR-005）
- 非 default 组配置不完整 → 不阻塞启动，仅该组不装配对应 Bean

## 关系

```
AiProperties (unirag.ai)
 └── models: Map<组名, ModelGroup>
       ├── "default"  → 装配 @Primary ChatModel / EmbeddingModel
       └── <其他组名>  → 本期仅完成配置绑定，不装配 Bean（多模型扩展预留）
```
