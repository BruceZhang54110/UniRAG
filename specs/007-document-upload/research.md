# Research: 文档上传与文件存储

**Feature**: 文档上传与文件存储 | **Date**: 2026-10-02 | **Plan**: [plan.md](./plan.md)

Phase 0 决策记录。关键事实来自 [OpenDAL Java 绑定官方文档](https://opendal.apache.org/docs/bindings/java/)（2026-10-02 抓取）与 [ServiceConfig.Postgresql Javadoc（0.50.x）](https://opendal.apache.org/docs/java/org/apache/opendal/ServiceConfig.Postgresql.html)。

## R1: OpenDAL 整合模式——官方 postgresql 服务直接作 v1 后端

**事实**：OpenDAL Java 绑定（`org.apache.opendal` 包，Maven Central 发布）提供 `ServiceConfig.Postgresql`：配置项为 `connectionString`（`postgresql://user:pass@host/db` 形式）、`table`、`keyField`、`valueField`、`root`；语义是 **path(key) → bytea(value)** 的键值存储。同期可选服务还覆盖 fs、memory、s3 等 60+ 种（[完整服务列表](https://opendal.apache.org/docs/java/org/apache/opendal/ServiceConfig.Postgresql.html) 的 Nested Class Summary）。

**Decision**: 不使用"Java 侧自写 SPI + OpenDAL 仅作未来实现"的折中方案，而是**直接以 OpenDAL `Operator` 为唯一存储抽象**：
- v1：`backend=postgresql`，连接串复用 `spring.datasource` 同源配置，blob 表 `document_blob(path text PK, data bytea)` 由 Flyway V5 建表（列名与 OpenDAL 默认 key/value 字段对齐）；
- 切换 fs / S3 兼容端点（RustFS 等）：仅改 `unirag.storage.backend` 及对应配置项，Operator 构建点不变，业务代码零改动（spec FR-002 的兑现方式）。

**Rationale**: 用户明确要求"Apache OpenDAL 整合 pg 存储文件"；官方 postgresql 服务恰好就是 key→bytea 模型，与"PG 存原文"决策精确对齐。若绕过 OpenDAL 自写 JdbcTemplate 存储层，等于保留两套存储语义，违背"统一抽象"初衷。

**Alternatives considered**: 自写 `DocumentStorage` SPI（PG 实现 + OpenDAL 留作未来）——拒绝（两套语义并存，OpenDAL 沦为摆设）；全部走 OpenDAL 但 v1 用 `fs` 后端——拒绝（绑死单机磁盘，与"随主库备份"目标冲突）。

## R2: OpenDAL Java 绑定的工程接入

**事实**（[官方文档](https://opendal.apache.org/docs/bindings/java/) + 用户确认的构建片段）：
- 主 artifact + **平台 classifier** 双依赖，classifier 由 `kr.motd.maven:os-maven-plugin:1.7.0` 构建扩展自动探测（macOS aarch64 → `osx-aarch_64`，Linux musl 需显式换 `linux-*-musl`）；
- 绑定版本独立于 Rust core，Javadoc 显示 0.50.x 系列（tasks 阶段引入时核实最新稳定版并锁定）；
- API：`Operator.of(scheme, configMap)` 构建；Java 绑定原生为异步 API（`CompletableFuture`），提供 Blocking 封装；`write/read/delete/exists/stat` 操作与 scheme 无关。

**Decision**:
- pom 按官方片段接入 os-maven-plugin + 双依赖；`${opendal.version}` 入 properties；
- 业务代码统一走 **Blocking API**（`operator.blocking()`），与 Spring MVC 同步模型一致，不在业务层散布 CompletableFuture；
- `StorageAutoConfiguration` 按 `unirag.storage.backend`（`postgresql` | `fs` | `s3`）构建单例 `Operator`，配置失败按启动失败处理（fail-fast，对齐 004 资源加载哲学）。

**Alternatives considered**: 仅引入 opendal 无 classifier 主包——拒绝（无原生库运行必崩）；异步 API 贯穿——拒绝（徒增复杂度，无并发需求）。

## R3: 租户定位约定——X-Tenant-Code 头（007 定义，006 复用）

**事实**：项目此前无 Web 层（006 尚未实现），spec Assumptions 将租户定位形态留待 plan。005 服务层入参统一为 `tenantCode`（`IngestRequest.tenantCode`）。

**Decision**: HTTP 层租户统一从 **`X-Tenant-Code` 请求头**解析（`TenantContext`，缺失/未知租户 → 400/404 + 可读原因，spec US1-4）。该约定由本 feature 首次落地并写入 contracts；006 实现检索/问答接口时直接复用同一解析器与错误语义（在 plan 中显式记录此跨 feature 约定，避免 006 重复发明）。

**Alternatives considered**: 路径段携带租户（`/t/{code}/...`）——拒绝（与文档标识路径冲突、URL 更易泄漏日志）；查询参数——拒绝（GET 下载以外的语义不统一、易被代理日志记录）。

## R4: 分块上传协议——固定分块 + PG 暂存 + 事务拼装

**事实**：spec/Clarifications 已定轻量固定分块（无 S3 multipart/tus）；OpenDAL postgresql 服务**不支持追加写语义**（只有整体 write），因此"边传边拼"不可行，必须暂存。

**Decision**（三阶段 + 两个新表，见 data-model.md）：
1. **初始化**：`POST /uploads` 携带（docName、title、totalSize、chunkSize、totalChunks、contentSha256）→ 建 `upload_session`（INIT）；
2. **分块**：`PUT /uploads/{id}/chunks/{seq}`（raw body）→ 写 `upload_chunk`；`(session_id, seq)` 唯一——**重复上传同块 = 幂等覆盖**，天然支持断点续传（客户端查询已收块列表后只补缺块）；
3. **完成**：`POST /uploads/{id}/complete` → 单事务内：按 seq 读全部块拼装 → SHA-256 与初始化声明比对（不符 → 409 列缺失/损坏块）→ OpenDAL write `document_file/{id}/{version}` → 建 `document` + `document_file` 行 → 删暂存块 → 提交后调用 `IngestionService.ingest(...)`（事务外，005 自身保证摄取幂等）；
4. **过期清理**：`ApplicationRunner` + 定时（默认每小时）删除 `expires_at < now()` 的未完成会话及其暂存块（默认保留 24h，可配置）。

**Alternatives considered**: 暂存块直接写 OpenDAL（每块一个对象）——拒绝（完成时仍需服务端读回拼装，PG 暂存表带事务与唯一约束更省心，且完成拼装可在单库事务内完成元数据一致性）；客户端本地拼好再传——拒绝（违背断点续传初衷）。

## R5: 与 005 摄取的衔接——拼装后同步调用 IngestionService

**事实**：`IngestionService.ingest(IngestRequest)` 已覆盖（tenantCode、docName、title、content、source）；指纹/NOOP/替换/并发串行化/启动恢复全部由 005 提供。

**Decision**: 上传链路只做"字节 → UTF-8 文本"的解码（v1 仅文本/Markdown），以 `source="upload"` 调用摄取；摄取结果映射回 HTTP 响应（逐管线 outcome）。spec Assumption"同步等待结果后返回"兑现；摄取失败但原文已存——文档状态如实 FAILED/PARTIAL，原文保留（spec 边界情况），客户端可重试 complete（幂等：指纹相同 → NOOP 或由 005 串行化裁决）。

**Alternatives considered**: 上传后异步摄取（MQ/线程池）——拒绝（零新中间件约束 + v1 无性能驱动）。

## R6: 文件校验——扩展名白名单 + 内容嗅探双重判断

**Decision**: 双重校验（spec FR-003）：
- **扩展名白名单**：`.txt` / `.md` / `.markdown`（可配置）；
- **内容嗅探**：必须可完整解码为 UTF-8 且无 NUL 字节（`CharsetDecoder` REPORT 模式 + 0x00 扫描）——挡住"改名伪装"的二进制文件；
- 上限（默认 50MB）在流接收前按 `Content-Length` 预检，接收中按实际大小终检（防 chunked 传输谎报）。

**Alternatives considered**: Tika 探测 MIME——拒绝（引入重依赖，与轻量级冲突）；仅扩展名——拒绝（不满足 FR-003"双重判断"）。

## R7: 原文存储键与版本化

**Decision**: OpenDAL 内键 = `document_file/{tenantCode}/{docName}/v{version}`——租户前缀隔离（视觉/审计友好）、版本化键使"替换=写新键"（旧版本由 005 替换语义清理：重摄取成功后可删 `v{n-1}` 键，删除失败仅记 WARN 不阻断）。`document_file.storage_path` 记录当前键。

## R8: 规模与 TOAST（回应"PG 存文件是否生产可用"）

**事实**：PG 单字段上限 1GB；TOAST 对超 ~2KB 的列自动压缩（lz4/pglz）并行外存储；`document_blob.data` 为大字段，主行仅存 18 字节指针。

**Decision**: 50MB/文件、GB~几十 GB 总量在该机制下无结构性风险（备份用 pg_dump 或物理备份均可）；规模到 TB 或需 CDN 直出时，切换 OpenDAL `s3` 后端（RustFS，见 2026-10-02 调研：MinIO 已归档，RustFS 为其 Rust 重实现，磁盘格式兼容）。该演进路径**不改变任何业务代码**（R1 的抽象红利）。

## 遗留未知项

无。所有 NEEDS CLARIFICATION 已解决。
