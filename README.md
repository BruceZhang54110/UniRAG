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

## 多租户向量存储（004）

应用在启动时自动完成向量环境准备（Flyway V3 幂等启用 pgvector 扩展），并按租户资源配置为每个租户创建独立的向量表与 `EmbeddingStore` Bean：

- 配置：在 `tenant_resource_config` 中插入 `resource_type = 'EMBEDDING_STORE'` 的记录（JSON 含 host/port/database/user/password/table/dimension/create-table，Schema 见 specs/003 contracts）
- 获取：`tenantResourceRouter.get(tenantCode, ResourceType.EMBEDDING_STORE, key, EmbeddingStore.class)`
- 保证：加载期校验表名合法性、表存在性与维度一致性；不同租户配置同名表时后加载者被隔离拒绝；就绪保证条款见 [specs/004 contracts](specs/004-pgvector-embedding-store/contracts/embedding-store-guarantees.md)

同一租户可配置多个向量存储资源（不同 key/表/维度），支撑粗细粒度等多嵌入表场景。

## 文档上传与存储（007）

文档上传接口支持纯文本和 Markdown：小文件通过 multipart 一次性上传，大文件通过固定分块协议断点续传。上传完成后会自动触发租户启用的摄取管线，并提供原文下载、文档列表和详情查询。

原文通过 Apache OpenDAL 统一存储抽象，默认写入 PostgreSQL `bytea` 键值表；存储后端可通过 `unirag.storage.backend` 切换为文件系统或 S3。租户由 `X-Tenant-Code` 请求头定位，上传限制和会话清理周期见 `application.yml` 中的 `unirag.upload.*` 配置。

## 技术基线

- Spring Boot **3.5.16**（父 POM 锁定）
- Java **21**
- Maven Wrapper（Maven 3.9.11，Wrapper 3.3.4）
- 依赖最小集：`spring-boot-starter-web`、`spring-boot-starter-actuator`、`spring-boot-starter-test`
