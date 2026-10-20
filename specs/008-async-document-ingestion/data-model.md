# Data Model: 异步文档摄取任务

## 设计原则

本模型以租户为所有业务数据的隔离边界。`document_ingestion_job` 是持久化调度单元；原文仍由 `document_file` 元数据和 OpenDAL blob 保存；摄取结果仍由 `ingestion_run` 记录。当前 `document` 是租户内 `doc_name` 唯一的单行模型，`version` 是现有版本字段。本期实现必须通过迁移和兼容模型表达“新版本处理中、旧版本仍可检索”，不得在未验证前假定现有一对一 `document_file` 已支持历史版本。

## 实体

### document_ingestion_job

| 字段 | 类型/约束 | 说明 |
|---|---|---|
| `id` | `BIGSERIAL` PK | 任务标识，API 对外为字符串或数字，需固定契约 |
| `tenant_id` | `BIGINT NOT NULL` FK `tenant(id)` | 所有查询、领取和唯一性都带租户 |
| `document_id` | `BIGINT NOT NULL` FK `document(id)` | 关联文档逻辑对象/版本承载对象 |
| `document_version` | `INT NOT NULL` | 提交时锁定的版本；不得用当前 document.version 猜测历史版本 |
| `content_fingerprint` | `CHAR(64) NOT NULL` | 内容 SHA-256，幂等与审计身份 |
| `status` | `VARCHAR(16) NOT NULL` | `QUEUED`、`PROCESSING`、`RETRYING`、`READY`、`FAILED` |
| `stage` | `VARCHAR(32)` | `UPLOADED`、`CLAIMED`、`INGESTING`、`FINALIZING`、`READY`、`FAILED` 等有限枚举；具体实现不得返回任意异常文本 |
| `attempt_count` | `INT NOT NULL DEFAULT 0` | 已开始的执行次数，首次执行后为 1 |
| `max_attempts` | `INT NOT NULL` | 创建时快照配置，保证配置变更不改写历史语义 |
| `next_attempt_at` | `TIMESTAMP NOT NULL` | QUEUED 通常为创建时间，RETRYING 为退避后的时间 |
| `lease_token` | `VARCHAR(128)` nullable | 当前 worker 的随机租约令牌 |
| `leased_until` | `TIMESTAMP` nullable | 租约截止时间 |
| `last_error_code` | `VARCHAR(64)` nullable | 安全、稳定错误码 |
| `last_error_message` | `VARCHAR(500)` nullable | 清洗/截断后的可读摘要，不含 secret |
| `created_at` | `TIMESTAMP NOT NULL` | 创建时间 |
| `updated_at` | `TIMESTAMP NOT NULL` | 最近状态变更时间 |
| `started_at` | `TIMESTAMP` nullable | 最近一次实际执行开始时间 |
| `finished_at` | `TIMESTAMP` nullable | READY 或最终 FAILED 时间 |

建议额外保留 `retry_backoff_base_ms` 或在配置快照表中记录本次策略；如果只保留 `max_attempts`，实现必须明确重试策略变更对历史 job 的影响。

### document / document_file

- `document` 继续保存租户、逻辑名称、标题、指纹和现有状态，但异步实现不能把 `INGESTING` 的新版本误当作旧 READY 版本。
- `document_file` 当前以 `document_id` 为主键并保存 `storage_path`、文件名、MIME 和大小；实现需新增版本关联或等价快照字段/表，使不同版本的原文可同时保留，且 `storage_path` 使用租户前缀和版本化键。
- 活动可检索版本必须有明确唯一裁决（推荐活动版本指针或版本记录上的 `active` 约束）。只有 job READY 的版本才能成为活动版本；切换和旧版本停用/清理必须是可追踪操作。
- 旧版本失败保护：新 job 失败或重试期间，旧活动版本的内容、向量和文件不受删除影响。

### ingestion_run

现有表按 `document_id + pipeline_id` 保存管线执行结果。异步 worker 复用 `IngestionService` 时，必须保证 run 能归属于本次 job/版本；优先增加 `job_id`（FK）或等价版本关联，并为 `(job_id, pipeline_id)` 建立唯一性，避免重投创建重复有效 run。现有 `error_message` 继续只保存截断安全摘要。若兼容旧数据暂时允许 `job_id` 为空，读取契约必须区分旧同步记录与异步记录。

## 状态机

```text
QUEUED --领取--> PROCESSING
PROCESSING --成功且满足全部可检索条件--> READY
PROCESSING --可重试失败且 attempt_count < max_attempts--> RETRYING
RETRYING --next_attempt_at 到期并成功领取--> PROCESSING
RETRYING --到达 max_attempts--> FAILED
PROCESSING --不可重试失败或最后一次失败--> FAILED
PROCESSING --租约过期--> QUEUED 或 RETRYING（不增加虚假的完成次数）
```

状态规则：

- `QUEUED` 只表示原文、文档版本和 job 已持久化；不表示可检索。
- `PROCESSING` 表示持有有效租约的 worker 正在处理。
- `RETRYING` 必须有未来 `next_attempt_at`；普通领取不得提前执行。
- `READY` 是终态，且必须对应活动版本切换成功。
- `FAILED` 是自动重试终态；本期不定义人工重试 API。
- 每次状态迁移必须校验当前状态和租约 token；过期 worker 的迟到写入不得覆盖新 worker。

## 索引与约束

建议迁移创建：

- `UNIQUE (tenant_id, document_id, document_version, content_fingerprint)`，作为重复提交的最终裁决；若同版本允许多次不同内容，版本分配和唯一键必须在同一事务内裁决。
- `FOREIGN KEY tenant_id`、`document_id`，以及 `ingestion_run.job_id`（若新增）。
- 可执行任务索引：`(status, next_attempt_at, leased_until)`，至少支持状态和时间范围扫描。
- 租户查询索引：`(tenant_id, id)` 或 `(tenant_id, document_id, document_version)`。
- `CHECK attempt_count >= 0`、`max_attempts > 0`，以及状态与租约/时间字段的基本一致性；复杂状态转换由事务代码保证。
- 文档活动版本的唯一约束必须防止同一租户/逻辑文档出现两个活动版本。

## SKIP LOCKED 领取

事务内按 `next_attempt_at <= CURRENT_TIMESTAMP` 选择 `QUEUED` 或到期 `RETRYING` 的任务，并对过期 `PROCESSING` 做恢复；使用 `FOR UPDATE SKIP LOCKED`，随后原子更新为 `PROCESSING`、生成 token、增加 `attempt_count` 并设置租约。提交后才执行耗时摄取。领取 SQL 和并发结果必须用真实 PostgreSQL 集成测试证明同一 job 同时最多被一个 worker 处理。

## 租户隔离

HTTP 查询必须从 `TenantContext` 获取租户，不接受客户端可覆盖的 tenant id；SQL 查询、job 创建、文档关联、领取和更新均须带租户条件或通过 FK 关系验证。不存在的 job 与其他租户 job 对调用方统一返回 NOT_FOUND，避免枚举。

## 幂等与版本规则

1. 上传事务先完成原文元数据、文档版本快照和 job 的可追踪持久化；任一失败回滚或执行可验证补偿，不能返回成功。
2. 重复提交按租户、逻辑文档、版本/版本分配规则和指纹命中唯一约束，返回已有有效 job，不重复排队。
3. worker 重投必须可安全重复；摄取写入、run、chunk/vector 清理和创建需按 job/版本幂等，不能依靠进程锁作为唯一保护。
4. 新版本只有在全部启用管线满足 READY 条件后才切换活动指针；切换前旧版本保持可检索。
5. 新版本失败保留旧活动版本和旧 blob；新版本 READY 后旧版本才可停用，物理 blob/vector 清理可异步执行且失败可重试。
