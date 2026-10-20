# Phase 0 Research: 异步文档摄取任务

## 现状与边界

当前上传链路在 `DocumentUploadController` 中以 `ApiResponse.ok(...)` 返回，`UploadService.finalizeUpload` 依次写 OpenDAL 原文、同步调用 `IngestionService.ingest`，最后写 `document_file` 元数据；这不是异步行为。`IngestionService` 当前在 JVM 内按租户和文档名加锁，并直接更新单个 `document` 行，现有文档状态为 `INGESTING`、`INGESTED`、`PARTIAL`、`FAILED`。`ApiResponse` 当前只包装 `data`，现有异常处理器的错误体为顶层 `error`，因此本功能的异步契约是未来 API 约定，不声称现有接口已经满足它。

`document` 当前是租户内 `doc_name` 唯一的单行模型，`document_file` 与其一对一；因此“版本”目前由 `document.version` 表示，而不是独立版本表。异步设计必须避免在新版本未就绪前覆盖旧版本的可检索语义；实现阶段需验证是否扩展现有表、增加版本表，或以兼容迁移完成同等保证。现有 `ingestion_run` 以 `document_id` 关联管线执行记录，不能假定它已经按 job 隔离。

## 决策 R1：队列模型与领取

- **Decision**：增加 PostgreSQL `document_ingestion_job` 持久化队列；worker 使用单条事务中的 `FOR UPDATE SKIP LOCKED` 领取可执行任务，并写入租约令牌、租约截止时间和处理中状态。
- **Rationale**：满足轻量化约束，无需 Kafka/RabbitMQ；事务锁避免多个进程领取同一行，数据库状态在重启后保留。
- **Alternatives considered**：Spring 内存队列会在重启时丢任务；固定线程直接扫描且不加锁会重复执行；新增消息代理超出本期范围。
- **Implementation boundary to verify**：SQL 方言、事务隔离级别、worker 扫描频率和批量大小要通过 Spring JDBC 或现有 MyBatis-Plus 风格的集成测试验证，不能只靠单元测试。

## 决策 R2：默认重试参数

- **Decision**：默认最大尝试次数为 `3`（包含首次执行），指数退避基数为 `30s`，退避为 `30s * 2^(attempt-1)`，并设置实现可配置的上限（建议 `15m`）；尝试耗尽进入 `FAILED`。
- **Rationale**：有限且可预测，能覆盖短暂模型/网络故障，又不会在开发库中无限重试。job 行保存实际 `attempt_count`、`next_attempt_at` 和最终错误，而不是依赖配置推导历史。
- **Alternatives considered**：默认 1 次不能恢复短暂故障；无限重试会掩盖永久配置错误；线性退避在连续故障时更容易造成重试拥塞。
- **Implementation boundary to verify**：哪些异常可重试必须由实现按现有管线异常分类确定；本研究不把所有 `FAILED` 都强行解释为暂时性。配置名称、上限和是否加入随机抖动须在实现前与现有 `@ConfigurationProperties` 风格对齐。

## 决策 R3：READY 语义

- **Decision**：对外 `READY` 只表示该 job 对应版本满足现有全部启用摄取管线的“可检索”条件；排队、执行中、部分成功和上传已保存均不是 READY。worker 将现有 `IngestionService` 的成功结果映射为 READY，失败或仅部分成功不得隐式升级为 READY。
- **Rationale**：现有服务的 `INGESTED` 表示全部管线成功，`PARTIAL` 表示部分成功；异步 API 需要一个稳定的前端语义，并明确区分原文已保存与索引可用。
- **Alternatives considered**：把文件落盘映射为 READY 会误导查询方；把 `PARTIAL` 映射为 READY 会违反“全部已启用管线处理成功”的验收语义；直接暴露内部 `INGESTED` 会把实现状态泄漏为公共契约。
- **Implementation boundary to verify**：空管线、NOOP、重复内容和管线部分成功在实现中要各有集成测试；现有同步结果的字段与 job 状态更新必须在同一业务流程中一致。

## 决策 R4：前端 API 契约

- **Decision**：上传成功采用 `202 Accepted`，统一返回 `{ "data": { ... } }`，其中包含 `documentId`、`documentVersion`、`jobId`、`status: "QUEUED"` 和状态查询 URL；查询接口按租户返回 job 状态、阶段、尝试次数、时间、版本和安全错误摘要。前端本期不实现。
- **Rationale**：202 明确表示请求已接受而不是索引已就绪，且保持现有成功包装风格。契约另文定义，方便未来页面只依赖稳定字段。
- **Alternatives considered**：201 可表达资源创建，但容易被客户端误认为已可检索；同步 200 会重新引入上传超时和重复提交问题。
- **Implementation boundary to verify**：现有一次性和分块上传都必须进入同一异步收尾路径；旧客户端兼容策略、是否保留旧同步响应字段、任务查询路径命名需通过 MockMvc/契约测试确认。

## 决策 R5：租约与恢复

- **Decision**：领取时产生不可预测的 `lease_token`，写入 `leased_until`；成功/失败/重试更新必须带 job id、token 和当前状态条件。定时恢复扫描将 `PROCESSING` 且 `leased_until < now()` 的 job 重置为可执行状态；应用启动后也执行同一恢复逻辑。
- **Rationale**：进程崩溃、线程中断和容器重启都能恢复，不依赖内存锁。token 防止旧 worker 在租约过期后覆盖新 worker 的结果。
- **Alternatives considered**：只用布尔 `processing` 会永久卡死；只靠 JVM `ReentrantLock` 无法跨进程；人工修复不满足自动恢复验收。
- **Implementation boundary to verify**：租约长度、心跳续租是否需要、恢复扫描与领取的竞争、时钟精度必须由 PostgreSQL 集成测试覆盖。默认不引入心跳，单次摄取超出租约时实现应选择续租或足够长的可配置租约并验证。

## 决策 R6：幂等、版本与切换

- **Decision**：job 的幂等唯一身份至少包括 `tenant_id + document identity + document_version + content_fingerprint`，数据库唯一约束作为最终裁决；重复提交返回已存在的有效 job 或等价结果，不创建第二个可执行 job。新版本在自己的摄取成功并满足 READY 条件后才切换活动版本；失败时保留旧活动版本和原文。
- **Rationale**：客户端网络重试和 worker 重投都必须安全。当前 `document` 单行替换和 `document_file` 一对一结构不足以直接表达并存旧/新版本，因此实现需要显式的版本快照/活动版本方案，或等价的兼容改造。
- **Alternatives considered**：仅用 `(tenant_id, doc_name)` 唯一键会把合法新版本与重复提交混淆；上传后立即删除旧 blob 会破坏回滚；best-effort 删除旧版本不能作为切换正确性的依据。
- **Implementation boundary to verify**：需要决定版本表与现有 `document` 主表的关系、向量元数据如何标识版本、旧版本何时清理；并发新旧 job、同指纹 NOOP、唯一约束冲突和向量重复必须用真实 PostgreSQL 测试验证。不能假定现有 JVM 锁可替代数据库幂等。

## 决策 R7：失败信息与审计

- **Decision**：job 保存阶段、最后一次错误摘要、失败时间、尝试次数和状态变更时间；错误摘要截断并经过筛选，不保存异常堆栈、API key、数据库密码或原始配置。日志只记录稳定类型和标识。
- **Rationale**：沿用现有 `IngestionRun.error_message` 的安全约束和 `GlobalExceptionHandler` 的收敛策略，满足可观测性而不泄漏秘密。
- **Alternatives considered**：直接保存异常 message 可能包含凭据或连接串；只记录 FAILED 不足以诊断重试/租约恢复。
- **Implementation boundary to verify**：实际异常包装、阶段枚举、错误码映射和日志字段需在实现和敏感信息测试中确认。

## 决策 R8：迁移与测试策略

- **Decision**：新增 PostgreSQL Flyway 迁移，不修改已发布迁移；实体/Mapper 遵循 MyBatis-Plus，领取等需要精确 SQL 的场景可采用 Spring JDBC 或 XML Mapper。使用现有 `AbstractPostgresIntegrationTest` / `AbstractIngestionIT` / `AbstractUploadIT` 基础设施，新增 MockMvc 与真实 PostgreSQL 端到端测试。
- **Rationale**：项目已有 PostgreSQL、Flyway、MyBatis-Plus、Spring JDBC、OpenDAL 和统一测试基类；真实数据库才能验证 SKIP LOCKED、唯一约束、租约和迁移。
- **Alternatives considered**：H2 无法可信模拟 PostgreSQL 锁语义；新建持久化框架会增加复杂度；只测试 service 无法证明上传响应和数据库原子性。
- **Implementation boundary to verify**：迁移版本号、清理测试数据的外键顺序、OpenDAL blob 清理、异步 worker 的可控启动/停止方式需在实现中落地并验证。
