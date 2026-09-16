# Quickstart: langchain4j 基础设施集成

**Feature**: 002-langchain4j-rag | **Date**: 2026-09-10

本期交付基础设施：langchain4j 1.20.0 依赖、OpenAI 兼容接口的对话/嵌入模型装配（自定义多模型配置）、PGVector 依赖与 PostgreSQL 数据源连接配置。**不含**文档摄取、检索、问答等业务能力。

## 1. 环境要求

- JDK 21、Git（沿用项目骨架要求，无需预装 Maven）
- 一个 OpenAI 兼容的模型服务（api-key、base-url、模型名）
- 一个可连接的 PostgreSQL 实例（本期仅验证连接，不建表）

## 2. 配置模型服务

在 `src/main/resources/application.yml`（或环境变量）中填写：

```yaml
unirag:
  ai:
    models:
      default:                # 默认模型组（必须），装配 @Primary 的 ChatModel / EmbeddingModel
        provider: open-ai
        base-url: https://api.deepseek.com/v1    # 换成你的 OpenAI 兼容服务地址
        api-key: ${OPENAI_API_KEY}               # 经环境变量注入，勿写入真实密钥
        chat-model: deepseek-chat
        embedding-model: embedding-model-name    # 若服务无嵌入模型可暂时省略
        timeout: 60s
```

密钥经环境变量注入：`export OPENAI_API_KEY=sk-...`

> 多模型扩展：`models` 下可追加其他命名组（如 `dashscope:`），本期仅完成配置绑定，不为其装配 Bean。

## 3. 配置 PostgreSQL 数据源

```yaml
spring:
  datasource:
    url: jdbc:migration.migration://localhost:5432/unirag
    username: unirag
    password: ${PG_PASSWORD}
```

连接配置错误时应用启动即失败并报出可读原因（HikariCP 启动期连接验证）。

## 4. 验证集成

```bash
./mvnw clean verify        # 构建 + 全部测试（无需真实凭据，测试使用模拟替身）
./mvnw spring-boot:run     # 启动；配置缺失/不可达时启动失败并指明缺失项
```

预期：构建零失败、测试全绿；配置完整时应用正常启动，配置缺失时在启动阶段快速失败。

## 5. 验收标准（本期）

- 编译通过
- 用法符合 langchain4j 官方核心 API 惯例（自定义装配）
- 代码 review 通过
