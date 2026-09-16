# Quickstart: UniRAG 快速上手

> 本文件是 plan 阶段的命令草稿；`README.md` 正式版本由实现任务生成（FR-007）。

## 环境要求

- JDK 21（`java -version` 核验）
- Git
- **无需**预装 Maven——仓库内置 Maven Wrapper（`./mvnw`）

## 三条命令完成构建 → 启动 → 验证

```bash
# 1. 编译 + 测试 + 打包（含全部校验）
./mvnw clean verify

# 2. 启动应用
./mvnw spring-boot:run

# 3. 验证健康（另开一个终端）
curl http://localhost:8080/actuator/health
# 期望输出: {"status":"UP"}
```

## 常用命令速查

| 目的 | 命令 |
|------|------|
| 仅编译 | `./mvnw compile` |
| 仅运行测试 | `./mvnw test` |
| 打包可执行 jar | `./mvnw package` |
| 运行打包产物 | `java -jar target/unirag-0.0.1-SNAPSHOT.jar` |
| 跳过测试打包 | `./mvnw package -DskipTests`（仅限本地调试） |
