# UniRAG

基于 Spring Boot 3.5.16（3.x 最新稳定版）+ Java 21 的项目骨架。

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | `java -version` 核验 |
| Git | 任意近期版本 | 克隆仓库用 |
| Maven | **无需安装** | 仓库内置 Maven Wrapper（`./mvnw`） |

## 快速上手（3 条命令）

```bash
# 1. 编译 + 测试 + 打包
./mvnw clean verify

# 2. 启动应用
./mvnw spring-boot:run

# 3. 验证健康（另开一个终端）
curl http://localhost:8080/actuator/health
```

第 3 步期望输出：

```json
{"status":"UP"}
```

## 常用命令速查

| 目的 | 命令 |
|------|------|
| 仅编译 | `./mvnw compile` |
| 仅运行测试 | `./mvnw test` |
| 打包可执行 jar | `./mvnw package` |
| 运行打包产物 | `java -jar target/unirag-0.0.1-SNAPSHOT.jar` |
| 清理构建产物 | `./mvnw clean` |

## 项目结构

```text
src/
├── main/
│   ├── java/com/unirag/
│   │   └── UniRagApplication.java         # 应用入口
│   └── resources/
│       └── application.yml              # 应用配置（默认端口 8080，仅暴露 health 端点）
└── test/
    └── java/com/unirag/
        ├── UniRagApplicationTests.java     # 上下文加载冒烟测试
        └── HealthEndpointTest.java         # /actuator/health 契约测试
```

## 技术基线

- Spring Boot **3.5.16**（父 POM 锁定）
- Java **21**
- Maven Wrapper（Maven 3.9.11，Wrapper 3.3.4）
- 依赖最小集：`spring-boot-starter-web`、`spring-boot-starter-actuator`、`spring-boot-starter-test`
