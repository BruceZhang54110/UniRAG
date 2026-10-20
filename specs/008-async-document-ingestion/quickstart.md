# Quickstart：异步文档摄取验证

本指南用于实现完成后的可执行验收，不包含完整实现、迁移 SQL 或测试套件代码。契约见 [`contracts/async-ingestion-api.md`](contracts/async-ingestion-api.md)，数据关系见 [`data-model.md`](data-model.md)。

## 前提

1. Java 21、Maven Wrapper 和可用 PostgreSQL 已安装。
2. PostgreSQL 已准备项目 `application.yml` 所需数据库、用户和密码；Flyway 用户可执行迁移。
3. pgvector/OpenDAL 所需本地配置可用，且 `document_blob` 能由迁移创建。
4. 测试租户、至少一条启用摄取管线、embedding store/model 测试替身已按现有 `AbstractIngestionIT` 约定准备。
5. 每个测试类使用全局唯一租户码，并按外键顺序清理 `document_ingestion_job`、`ingestion_run`、chunks、document_file/blob 和 document。

## 构建与测试命令

```bash
./mvnw -q test
./mvnw -q -Dtest='*Async*IT' test
./mvnw -q -Dtest='*Upload*IT,*Ingestion*IT' test
./mvnw -q spring-boot:run
```

第二、三条命令的实际测试类名以实现为准；不得为了通过命令而跳过 Spring Boot 集成测试。测试应使用真实 PostgreSQL 验证 Flyway、唯一约束、`FOR UPDATE SKIP LOCKED` 和租约恢复。

## 端到端场景

### 1. 上传立即返回 QUEUED

```bash
curl -i -H 'X-Tenant-Code: async-quickstart-a' \
  -F 'file=@./fixtures/handbook.md' \
  -F 'docName=handbook' \
  -F 'title=Handbook' \
  http://localhost:8080/api/v1/documents
```

预期：HTTP `202`；`data` 中有 `documentId`、`documentVersion`、`jobId`、`status=QUEUED` 和 `statusUrl`。在 worker 完成前请求已返回；数据库同时能查到原文元数据、版本和 job。

### 2. 查询直到 READY

```bash
curl -s -H 'X-Tenant-Code: async-quickstart-a' \
  http://localhost:8080/api/v1/documents/{documentId}/ingestion-jobs/{jobId}
```

预期先看到 `QUEUED`/`PROCESSING`，最终 `200` 且 `status=READY`、`stage=READY`；只在 READY 后断言该版本可检索。

### 3. 可恢复失败、退避与最终失败

使用现有测试替身让第一次管线执行失败、后续恢复：验证状态为 `RETRYING`，`attemptCount` 增加且 `nextAttemptAt` 在未来，随后同一 job 无需重新上传而进入 `READY`。再让每次尝试失败：验证默认最大尝试次数（3，包含首次）后为 `FAILED`，查询仍为 HTTP 200，错误仅为安全摘要且不再被普通 worker 领取。

### 4. 重启恢复

上传后让 worker 在租约内中断或停止进程，再启动应用。验证过期 `PROCESSING` job 被恢复并重新领取；重启前后的 `attemptCount`、指纹、版本和 `nextAttemptAt` 来自数据库而非内存。验证同一 job 同时最多一个 worker 执行。

### 5. 旧版本保留与切换

上传内容 A，等待版本 1 READY 并确认可检索；上传内容 B，确认响应为新版本 `QUEUED`，期间版本 1 仍可检索。让 B 失败到最终 `FAILED`，确认 A 和其 blob/vector 仍可用。再上传可成功的 B，确认只有 B READY 后活动版本才切换，旧版本才进入停用/清理流程；清理失败不得回滚活动切换。

### 6. 租户隔离

租户 A 创建 job 后，用租户 B 查询同一 URL。预期统一 HTTP `404` 和 `DOCUMENT_INGESTION_JOB_NOT_FOUND`，不得泄漏“任务存在但属于其他租户”。同时确认数据库查询与领取不会跨租户取数。

### 7. 重复提交

对同一租户、文档、版本/内容指纹重复发送相同上传并模拟客户端超时后的重试。预期返回/复用同一个有效 job，`document_ingestion_job` 不出现第二个可执行任务，`ingestion_run`、切片和向量不重复。不同指纹应创建新版本，但仍不能破坏旧 READY 版本。

## 验收检查

- `git diff --check` 无输出。
- Flyway 从干净数据库和现有数据库均成功迁移。
- MockMvc 验证 202、统一包装、404 隔离和错误字段；PostgreSQL 集成测试验证锁、租约、重试、幂等和版本切换。
- 日志及响应敏感信息测试确认没有 API key、数据库密码、连接串或原始异常堆栈。
